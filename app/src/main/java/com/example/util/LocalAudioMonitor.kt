/*
 * Copyright (C) 2026 MovStore
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.example.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.telecom.CallAudioState
import com.example.CallManager
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Plays the same PCM that is injected into the uplink through the device's local
 * call output, so the user can hear a reference of what the caller receives.
 *
 * A separate track (instead of reusing the root helper's Telephony Tx track) is
 * required because the Telephony Tx device is not audible on the handset. This
 * track explicitly prefers the current call route (earpiece, speaker, headset or
 * Bluetooth) and therefore never steals the carrier uplink.
 */
object LocalAudioMonitor {

    private const val SAMPLE_RATE = 16_000
    private const val FRAME_BYTES = 2
    private const val CHUNK_FRAMES = 320 // 20 ms

    private val lock = Any()
    private var playback: Playback? = null

    /**
     * Plays a raw 16-bit little-endian mono PCM file once.
     * Returns false when no local audio output could be opened.
     */
    fun playPcm(context: Context, file: File, volume: Float = 0.8f): Boolean {
        if (!file.isFile || file.length() < FRAME_BYTES) return false
        return start(context, volume) { track, current ->
            BufferedInputStream(FileInputStream(file), 64 * 1024).use { input ->
                val bytes = ByteArray(CHUNK_FRAMES * FRAME_BYTES)
                val samples = ShortArray(CHUNK_FRAMES)
                var carry = -1
                while (current()) {
                    val read = input.read(bytes)
                    if (read <= 0) break
                    var offset = 0
                    var frames = 0
                    if (carry >= 0) {
                        if (read < 1) break
                        samples[frames++] = (((bytes[0].toInt() and 0xFF) shl 8) or carry).toShort()
                        offset = 1
                        carry = -1
                    }
                    while (offset + 1 < read) {
                        val lo = bytes[offset].toInt() and 0xFF
                        val hi = bytes[offset + 1].toInt()
                        samples[frames++] = ((hi shl 8) or lo).toShort()
                        offset += 2
                    }
                    if (offset < read) {
                        carry = bytes[offset].toInt() and 0xFF
                    }
                    if (frames > 0 && track.write(samples, 0, frames) < 0) {
                        break
                    }
                }
            }
        }
    }

    /**
     * Plays a continuous sine tone until [stop] is called.
     * Returns false when no local audio output could be opened.
     */
    fun startTone(
        context: Context,
        frequencyHz: Int = 1_000,
        volume: Float = 0.8f
    ): Boolean {
        val frequency = frequencyHz.coerceIn(200, 4_000)
        return start(context, volume) { track, current ->
            val samples = ShortArray(CHUNK_FRAMES)
            var phase = 0.0
            while (current()) {
                phase = ToneSamples.fill(
                    samples,
                    phase,
                    frequency,
                    SAMPLE_RATE,
                    amplitude = 0.30
                )
                if (track.write(samples, 0, samples.size) < 0) {
                    break
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        val previous = synchronized(lock) {
            val current = playback
            playback = null
            current
        }
        previous?.requestStop()
    }

    @Synchronized
    private fun start(
        context: Context,
        volume: Float,
        writer: (AudioTrack, () -> Boolean) -> Unit
    ): Boolean {
        stop()
        val appContext = context.applicationContext
        val track = createTrack(appContext, volume) ?: return false

        val next = Playback(track)
        synchronized(lock) {
            playback = next
        }
        try {
            track.play()
        } catch (_: Exception) {
            next.requestStop()
            releaseQuietly(track)
            return false
        }

        val thread = Thread({
            try {
                writer(track) { !next.stopped }
            } catch (_: Exception) {
            } finally {
                releaseQuietly(track)
                synchronized(lock) {
                    if (playback === next) playback = null
                }
            }
        }, "SecureDialer-LocalMonitor")
        thread.isDaemon = true
        thread.start()
        return true
    }

    private fun createTrack(context: Context, volume: Float): AudioTrack? {
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return null

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(max(minBuffer * 2, 8_192))
                .build()
        } catch (_: Exception) {
            null
        } ?: return null

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            releaseQuietly(track)
            return null
        }
        preferredDevice(context)?.let { device ->
            try {
                track.setPreferredDevice(device)
            } catch (_: Exception) {}
        }
        try {
            track.setVolume(volume.coerceIn(0f, 1f))
        } catch (_: Exception) {}
        return track
    }

    private fun preferredDevice(context: Context): AudioDeviceInfo? {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return null
        val route = CallManager.audioState.value?.route
        val types = when (route) {
            CallAudioState.ROUTE_SPEAKER -> intArrayOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            CallAudioState.ROUTE_EARPIECE -> intArrayOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
            CallAudioState.ROUTE_WIRED_HEADSET -> intArrayOf(
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            )
            CallAudioState.ROUTE_BLUETOOTH -> intArrayOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            )
            else -> intArrayOf(
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            )
        }
        val devices = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        } catch (_: Exception) {
            return null
        }
        for (type in types) {
            devices.firstOrNull { it.type == type }?.let { return it }
        }
        return null
    }

    private fun releaseQuietly(track: AudioTrack) {
        try {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
        } catch (_: Exception) {}
        try {
            track.release()
        } catch (_: Exception) {}
    }

    private class Playback(val track: AudioTrack) {
        @Volatile
        var stopped = false

        fun requestStop() {
            stopped = true
            try {
                track.pause()
            } catch (_: Exception) {}
            try {
                track.flush()
            } catch (_: Exception) {}
        }
    }
}

/**
 * Pure sine-wave generation, separated so the 1000 Hz bleep can be unit-tested.
 */
internal object ToneSamples {

    fun fill(
        buffer: ShortArray,
        startPhase: Double,
        frequencyHz: Int,
        sampleRate: Int,
        amplitude: Double
    ): Double {
        val step = 2.0 * PI * frequencyHz / sampleRate
        var phase = startPhase
        val peak = Short.MAX_VALUE * amplitude.coerceIn(0.0, 1.0)
        for (index in buffer.indices) {
            val sample = (sin(phase) * peak).toInt().coerceIn(-32_768, 32_767)
            buffer[index] = sample.toShort()
            phase += step
            if (phase >= 2.0 * PI) {
                phase -= 2.0 * PI
            }
        }
        return phase
    }
}
