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
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.widget.Toast
import com.example.CallManager
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinates soundboard playback and the hold-to-bleep action.
 *
 * Both features need the same root uplink bridge: the soundboard sends short PCM
 * files, the bleep sends a continuous 1000 Hz tone. Keeping one daemon alive per
 * call makes the second and later actions effectively instant.
 */
object SoundboardManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionLock = Any()
    private val startMutex = Mutex()
    private val lifecycleToken = AtomicLong(0L)

    private val _isPreparing = MutableStateFlow(false)
    val isPreparing: StateFlow<Boolean> = _isPreparing.asStateFlow()

    private val _playingEffectId = MutableStateFlow<String?>(null)
    val playingEffectId: StateFlow<String?> = _playingEffectId.asStateFlow()

    private val _isBleeping = MutableStateFlow(false)
    val isBleeping: StateFlow<Boolean> = _isBleeping.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    @Volatile
    private var session: RootUplinkAudioSession? = null

    @Volatile
    private var effectJob: Job? = null

    @Volatile
    private var bleepJob: Job? = null

    @Volatile
    private var bleepActive = false

    @Volatile
    private var muteBeforeBleep = false

    fun clearError() {
        _lastError.value = null
    }

    /**
     * Warms the root bridge when the call is active so the first bleep is instant.
     * Safe to call repeatedly.
     */
    fun prepare(context: Context) {
        if (!SoundboardPreferences.isEnabled(context)) return
        if (AnsweringMachineManager.isBusy()) return
        val appContext = context.applicationContext
        scope.launch { ensureSession(appContext) }
    }

    fun playEffect(context: Context, effect: SoundboardEffects.SoundboardEffect) {
        val appContext = context.applicationContext
        if (!SoundboardPreferences.isEnabled(appContext)) return
        if (AnsweringMachineManager.isBusy()) {
            toast(appContext, R.string.soundboard_route_failed)
            return
        }

        effectJob?.cancel()
        _playingEffectId.value = effect.id
        effectJob = scope.launch {
            val source = SoundboardEffects.playableFile(appContext, effect)
            if (source == null) {
                failEffect(effect, appContext, R.string.soundboard_play_failed)
                return@launch
            }
            val pcm = AnsweringMachinePcm.ensure(appContext, source, "effect")
            if (pcm == null) {
                failEffect(effect, appContext, R.string.soundboard_play_failed)
                return@launch
            }

            if (_playingEffectId.value != effect.id) return@launch
            val durationMs = (pcm.file.length() / 32L).coerceIn(50L, 180_000L)
            val ready = ensureSession(appContext)
            if (_playingEffectId.value != effect.id) return@launch
            val sent = ready && session?.playPcm(pcm.file) == true
            if (sent) {
                _lastError.value = null
            } else {
                _lastError.value = appContext.getString(R.string.soundboard_route_failed)
                toast(appContext, R.string.soundboard_route_failed)
            }

            if (SoundboardPreferences.isMonitorEnabled(appContext)) {
                LocalAudioMonitor.playPcm(appContext, pcm.file)
            }

            delay(durationMs + 250L)
            if (_playingEffectId.value == effect.id) {
                _playingEffectId.value = null
            }
        }
    }

    /**
     * Called while the bleep button is held. The microphone is muted immediately;
     * the root tone may take a moment on the very first use.
     */
    fun startBleep(context: Context) {
        if (bleepActive) return
        val appContext = context.applicationContext
        if (!SoundboardPreferences.isEnabled(appContext)) return
        if (AnsweringMachineManager.isBusy()) {
            toast(appContext, R.string.bleep_route_failed)
            return
        }

        bleepActive = true
        _isBleeping.value = true
        muteBeforeBleep = CallManager.audioState.value?.isMuted ?: false
        try {
            CallManager.setMuted(true)
        } catch (_: Exception) {
        }

        effectJob?.cancel()
        effectJob = null
        _playingEffectId.value = null
        LocalAudioMonitor.stop()
        if (SoundboardPreferences.isBleepMonitorEnabled(appContext)) {
            LocalAudioMonitor.startTone(appContext, 1_000)
        }

        bleepJob = scope.launch {
            val ready = ensureSession(appContext)
            if (!bleepActive) return@launch
            // Stop anything the soundboard was playing before starting the tone.
            session?.stopPlayback()
            if (!bleepActive) return@launch
            val sent = ready && session?.playTone(1_000) == true
            if (!bleepActive) {
                // The button was released while the root command was being written.
                session?.stopPlayback()
                return@launch
            }
            if (sent) {
                _lastError.value = null
            } else {
                _lastError.value = appContext.getString(R.string.bleep_route_failed)
                toast(appContext, R.string.bleep_route_failed)
            }
        }
    }

    fun stopBleep(context: Context) {
        if (!bleepActive && !_isBleeping.value) return
        bleepActive = false
        _isBleeping.value = false
        bleepJob?.cancel()
        bleepJob = null
        LocalAudioMonitor.stop()
        // Send the stop synchronously so a quick press-release-press sequence cannot
        // let a delayed stop command silence the next tone.
        session?.stopPlayback()
        restoreMute()
    }

    /**
     * Stops local monitoring, the uplink tone/sound and the root daemon.
     * Call when the call screen leaves composition or the call ends.
     */
    fun stopAll(context: Context) {
        lifecycleToken.incrementAndGet()
        val restore = bleepActive
        bleepActive = false
        _isBleeping.value = false
        bleepJob?.cancel()
        bleepJob = null
        effectJob?.cancel()
        effectJob = null
        _playingEffectId.value = null
        LocalAudioMonitor.stop()
        if (restore) restoreMute()

        val toShutdown = synchronized(sessionLock) {
            val current = session
            session = null
            current
        }
        if (toShutdown != null) {
            scope.launch { toShutdown.shutdown() }
        }
    }

    private suspend fun ensureSession(context: Context): Boolean {
        val token = lifecycleToken.get()
        return startMutex.withLock {
            val existing = session
            if (existing?.isStarted == true) return true

            if (CallManager.currentCall.value?.state != Call.STATE_ACTIVE) {
                return false
            }

            _isPreparing.value = true
            try {
                if (AnsweringMachineManager.isBusy()) {
                    _lastError.value = context.getString(R.string.soundboard_route_failed)
                    return false
                }

                val granted = RootShell.isGranted() ||
                    RootShell.ensureAccess(context, prepare = false).granted
                if (!granted) {
                    _lastError.value = context.getString(R.string.root_required_toast)
                    return false
                }

                val next = RootUplinkAudioSession(context)
                val result = next.start()
                if (result.success) {
                    // The call may have ended while the root process was starting.
                    if (lifecycleToken.get() != token) {
                        next.shutdown()
                        return false
                    }
                    val previous = synchronized(sessionLock) {
                        val old = session
                        session = next
                        old
                    }
                    previous?.shutdown()
                    _lastError.value = null
                    true
                } else {
                    _lastError.value = result.message
                    false
                }
            } finally {
                _isPreparing.value = false
            }
        }
    }

    private fun failEffect(
        effect: SoundboardEffects.SoundboardEffect,
        context: Context,
        messageRes: Int
    ) {
        if (_playingEffectId.value == effect.id) {
            _playingEffectId.value = null
        }
        _lastError.value = context.getString(messageRes)
        toast(context, messageRes)
    }

    private fun restoreMute() {
        val call = CallManager.currentCall.value
        if (call == null ||
            call.state !in listOf(
                Call.STATE_ACTIVE,
                Call.STATE_HOLDING,
                Call.STATE_DIALING,
                Call.STATE_CONNECTING
            )
        ) {
            return
        }
        try {
            CallManager.setMuted(muteBeforeBleep)
        } catch (_: Exception) {
        }
    }

    private fun toast(context: Context, messageRes: Int) {
        mainHandler.post {
            Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_LONG).show()
        }
    }
}
