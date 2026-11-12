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
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One system-level (root) recording session.
 *
 * Strategy:
 * 1. Start [RootAudioServer] in a separate `app_process` launched through `su`.
 *    That process runs as UID 0, which is what makes VOICE_CALL / VOICE_DOWNLINK
 *    capture possible on most rooted devices.
 * 2. If that fails, run the same helper inside the app process after the root-granted
 *    CAPTURE_AUDIO_OUTPUT app-op (works on some ROMs where AudioFlinger honours app-ops).
 * 3. If both fail, the caller falls back to the standard MediaRecorder engine.
 */
class RootCallRecordingSession(
    context: Context,
    phoneNumber: String,
    timestamp: String
) {
    companion object {
        private const val PREFERRED_SAMPLE_RATE = 16_000
        private const val PREFERRED_CHANNELS = 1
        private const val NORMAL_MAX_SECONDS = 4 * 60 * 60L
        private const val ROOT_START_TIMEOUT_MS = 3_500L
        private const val IN_PROCESS_START_TIMEOUT_MS = 2_500L
        private const val STATUS_POLL_INTERVAL_MS = 50L

        fun sourceLabel(source: Int): String = when (source) {
            4 -> "VOICE_CALL"
            3 -> "VOICE_DOWNLINK"
            2 -> "VOICE_UPLINK"
            else -> "SOURCE_$source"
        }
    }

    data class StartResult(
        val success: Boolean,
        val message: String? = null,
        val source: Int = 0,
        val sampleRate: Int = PREFERRED_SAMPLE_RATE,
        val channels: Int = PREFERRED_CHANNELS
    )

    data class StopResult(
        val file: File?,
        val durationSeconds: Long,
        val source: Int
    )

    private enum class Mode { NONE, IN_PROCESS, ROOT_PROCESS }

    private val appContext = context.applicationContext
    private val cleanNumber = phoneNumber.filter { it.isDigit() }.ifEmpty { "Unknown" }
    private val baseName = "REC_${cleanNumber}_$timestamp"

    private val recordingDir = File(appContext.filesDir, "CallRecordings").apply { mkdirs() }
    private val workDir = File(appContext.cacheDir, "RootRecordings").apply { mkdirs() }
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val pcmFile = File(recordingDir, "$baseName.pcm")
    private val wavFile = File(recordingDir, "$baseName.wav")
    private val statusFile = File(workDir, "$baseName.status")
    private val stopFile = File(workDir, "$baseName.stop")
    private val pidFile = File(workDir, "$baseName.pid")
    private val logFile = File(workDir, "$baseName.log")

    private var mode = Mode.NONE
    private var inProcessThread: Thread? = null
    private var usedSource = 0
    private var usedSampleRate = PREFERRED_SAMPLE_RATE
    private var usedChannels = PREFERRED_CHANNELS
    private var maxSeconds = NORMAL_MAX_SECONDS

    @Volatile
    private var released = false

    @Synchronized
    fun start(preferredSource: Int, maxDurationSeconds: Long = NORMAL_MAX_SECONDS): StartResult {
        if (released) return StartResult(false, "Session already released")
        maxSeconds = maxDurationSeconds.coerceIn(5L, NORMAL_MAX_SECONDS)
        prepareTempFiles()

        // 1. Full root process (UID 0 via su + app_process).
        if (RootShell.lastKnownStatus() == RootStatus.GRANTED ||
            RootShell.lastKnownStatus() == RootStatus.UNKNOWN
        ) {
            val launch = launchRootProcess(preferredSource)
            if (launch.timedOut) {
                cleanupTempFiles()
                deleteQuietly(pcmFile)
                return StartResult(false, "Root request timed out")
            }
            val result = awaitRecording(ROOT_START_TIMEOUT_MS)
            if (result.success) {
                mode = Mode.ROOT_PROCESS
                usedSource = result.source
                usedSampleRate = result.sampleRate
                usedChannels = result.channels
                return result
            }
            killRootProcess()
            deleteQuietly(statusFile)
            deleteQuietly(logFile)
            deleteQuietly(pidFile)
            deleteQuietly(pcmFile)
        }

        // 2. In-process fallback: same native capture path but running under the app UID
        //    with the root-granted CAPTURE_AUDIO_OUTPUT app-op.
        prepareTempFiles()
        launchInProcess(preferredSource)
        val result = awaitRecording(IN_PROCESS_START_TIMEOUT_MS)
        if (result.success) {
            mode = Mode.IN_PROCESS
            usedSource = result.source
            usedSampleRate = result.sampleRate
            usedChannels = result.channels
            return result
        }
        stopInProcessThread(timeoutMs = 450L)
        cleanupTempFiles()
        deleteQuietly(pcmFile)
        return StartResult(false, result.message ?: "Call audio capture is unavailable on this device")
    }

    @Synchronized
    fun stop(): StopResult {
        if (released) return StopResult(null, 0L, usedSource)
        released = true

        // Ask the recorder to finish gracefully. The helper checks this file on every
        // read iteration, so it normally takes effect within a few tens of milliseconds.
        try {
            stopFile.writeText("stop")
        } catch (_: Exception) {}

        // Keep this method fast: it is called from the main thread when a call ends.
        // The normal helper exits within ~100 ms of seeing the stop marker; anything
        // slower is cleaned up on a background thread instead of blocking the UI.
        var stopped = false
        when (mode) {
            Mode.IN_PROCESS -> {
                stopped = stopInProcessThread(timeoutMs = 450L)
            }
            Mode.ROOT_PROCESS -> {
                stopped = waitForStatusState("stopped", 450L)
            }
            Mode.NONE -> {
                stopped = true
            }
        }

        val wav = convertPcmToWav()
        val duration = if (wav != null && wav.exists()) {
            val dataBytes = (wav.length() - 44L).coerceAtLeast(0L)
            val bytesPerSecond = (usedSampleRate * usedChannels * 2L).coerceAtLeast(1L)
            (dataBytes / bytesPerSecond).coerceAtLeast(1L)
        } else {
            0L
        }

        if (stopped) {
            cleanupTempFiles()
            deleteQuietly(pcmFile)
            deleteQuietly(pidFile)
        } else {
            cleanupScope.launch {
                if (mode == Mode.ROOT_PROCESS) {
                    killRootProcess()
                } else {
                    interruptInProcessThread()
                }
                delay(250L)
                cleanupTempFiles()
                deleteQuietly(pcmFile)
                deleteQuietly(pidFile)
            }
        }
        if (wav == null) deleteQuietly(wavFile)

        return StopResult(wav, duration, usedSource)
    }

    /**
     * Non-blocking stop signal used when the UI stops a recording while the root
     * helper is still starting up. The startup coroutine performs the cleanup.
     */
    fun requestStop() {
        try {
            stopFile.writeText("stop")
        } catch (_: Exception) {}
    }

    /** Kills any helper process and removes all temporary files without producing a WAV. */
    @Synchronized
    fun abort() {
        if (released) return
        released = true
        try {
            stopFile.writeText("stop")
        } catch (_: Exception) {}
        stopInProcessThread(timeoutMs = 450L)
        killRootProcess()
        cleanupTempFiles()
        deleteQuietly(pcmFile)
    }

    private fun prepareTempFiles() {
        deleteQuietly(statusFile)
        deleteQuietly(stopFile)
        deleteQuietly(pidFile)
        deleteQuietly(logFile)
        deleteQuietly(pcmFile)
        deleteQuietly(wavFile)
        try {
            workDir.mkdirs()
            recordingDir.mkdirs()
        } catch (_: Exception) {}
    }

    private fun launchRootProcess(preferredSource: Int): RootShellResult {
        val helperClasspath = RootHelperDex.prepare(appContext)
            ?: return RootShellResult(
                exitCode = -1,
                output = "Root helper dex could not be extracted",
                timedOut = false
            )
        val args = "${quote(pcmFile.absolutePath)} ${quote(statusFile.absolutePath)} " +
                "${quote(stopFile.absolutePath)} $preferredSource $PREFERRED_SAMPLE_RATE " +
                "$PREFERRED_CHANNELS $maxSeconds"
        val command = "CLASSPATH=${quote(helperClasspath)} app_process /system/bin " +
                "com.example.util.RootAudioServer $args " +
                "> ${quote(logFile.absolutePath)} 2>&1 & echo \$! > ${quote(pidFile.absolutePath)}"
        return RootShell.runSu(command, timeoutMs = 15_000L)
    }

    private fun launchInProcess(preferredSource: Int) {
        val args = arrayOf(
            pcmFile.absolutePath,
            statusFile.absolutePath,
            stopFile.absolutePath,
            preferredSource.toString(),
            PREFERRED_SAMPLE_RATE.toString(),
            PREFERRED_CHANNELS.toString(),
            maxSeconds.toString()
        )
        val thread = Thread({
            try {
                RootAudioServer.run(args)
            } catch (_: Throwable) {
                try {
                    statusFile.writeText("state=error\nerror=in-process recorder crashed")
                } catch (_: Exception) {}
            }
        }, "SecureDialer-RootAudioRecorder")
        thread.isDaemon = true
        inProcessThread = thread
        thread.start()
    }

    private fun awaitRecording(timeoutMs: Long): StartResult {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var lastError: String? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            val status = readStatus()
            when (status["state"]) {
                "recording" -> {
                    val source = status["source"]?.toIntOrNull() ?: 0
                    val rate = status["rate"]?.toIntOrNull() ?: PREFERRED_SAMPLE_RATE
                    val channels = status["channels"]?.toIntOrNull() ?: PREFERRED_CHANNELS
                    return StartResult(true, null, source, rate, channels)
                }
                "error" -> {
                    lastError = status["error"]?.takeIf { it.isNotBlank() }
                    return StartResult(false, lastError ?: "Call audio capture failed")
                }
                "stopped" -> {
                    return StartResult(false, lastError ?: "Recorder stopped before capture began")
                }
            }
            try {
                Thread.sleep(STATUS_POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return StartResult(false, "Interrupted while starting root recorder")
            }
        }
        // No status yet: surface a short diagnostic from the root helper log when possible.
        val logHint = readLastLogLine()
        return StartResult(
            false,
            lastError ?: logHint ?: "Timed out starting the root call recorder"
        )
    }

    private fun waitForStatusState(expected: String, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (readStatus()["state"] == expected) return true
            val thread = inProcessThread
            if (mode == Mode.IN_PROCESS && thread != null && !thread.isAlive) {
                return readStatus()["state"] == expected
            }
            try {
                Thread.sleep(STATUS_POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return readStatus()["state"] == expected
    }

    /**
     * Waits briefly for the in-process helper to observe the stop marker.
     * Returns true when the thread has finished. A long join here would block
     * the main thread when a call ends, so the caller schedules the rest on IO.
     */
    private fun stopInProcessThread(timeoutMs: Long): Boolean {
        val thread = inProcessThread ?: return true
        try {
            thread.join(timeoutMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (!thread.isAlive) {
            inProcessThread = null
            return true
        }
        return false
    }

    private fun interruptInProcessThread() {
        val thread = inProcessThread ?: return
        try {
            thread.interrupt()
            thread.join(300L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (!thread.isAlive) inProcessThread = null
    }

    private fun killRootProcess() {
        val pid = try {
            if (pidFile.exists()) pidFile.readText().trim() else ""
        } catch (_: Exception) {
            ""
        }
        if (pid.matches(Regex("\\d+"))) {
            RootShell.runSu("kill -TERM $pid", timeoutMs = 2_000L)
            try {
                Thread.sleep(250L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            RootShell.runSu("kill -9 $pid", timeoutMs = 2_000L)
        }
    }

    private fun readStatus(): Map<String, String> {
        val text = try {
            if (statusFile.exists()) statusFile.readText() else ""
        } catch (_: Exception) {
            ""
        }
        if (text.isBlank()) return emptyMap()
        val result = mutableMapOf<String, String>()
        for (line in text.lineSequence()) {
            val index = line.indexOf('=')
            if (index > 0) {
                result[line.substring(0, index).trim()] = line.substring(index + 1).trim()
            }
        }
        return result
    }

    private fun readLastLogLine(): String? {
        return try {
            if (!logFile.exists()) return null
            val lines = logFile.readLines()
            lines.asReversed().firstOrNull { it.isNotBlank() }?.take(180)
        } catch (_: Exception) {
            null
        }
    }

    private fun convertPcmToWav(): File? {
        return try {
            if (!pcmFile.exists()) return null
            val dataLength = pcmFile.length()
            if (dataLength < 1_024L) return null

            val sampleRate = usedSampleRate.coerceIn(8_000, 48_000)
            val channels = usedChannels.coerceIn(1, 2)
            val byteRate = sampleRate * channels * 2
            val blockAlign = channels * 2
            val dataSize = dataLength.coerceAtMost(Int.MAX_VALUE.toLong() - 44L).toInt()

            FileOutputStream(wavFile).use { output ->
                output.write(buildWavHeader(dataSize, sampleRate, channels, byteRate, blockAlign))
                FileInputStream(pcmFile).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var remaining = dataSize
                    while (remaining > 0) {
                        val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                }
                output.flush()
            }
            if (wavFile.exists() && wavFile.length() > 44L) wavFile else null
        } catch (_: Exception) {
            deleteQuietly(wavFile)
            null
        }
    }

    private fun buildWavHeader(
        dataSize: Int,
        sampleRate: Int,
        channels: Int,
        byteRate: Int,
        blockAlign: Int
    ): ByteArray {
        val header = ByteArray(44)
        fun putAscii(offset: Int, value: String) {
            value.toByteArray(Charsets.US_ASCII).copyInto(header, offset)
        }
        fun putIntLE(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value ushr 8) and 0xFF).toByte()
            header[offset + 2] = ((value ushr 16) and 0xFF).toByte()
            header[offset + 3] = ((value ushr 24) and 0xFF).toByte()
        }
        fun putShortLE(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        }

        putAscii(0, "RIFF")
        putIntLE(4, 36 + dataSize)
        putAscii(8, "WAVE")
        putAscii(12, "fmt ")
        putIntLE(16, 16)
        putShortLE(20, 1) // PCM
        putShortLE(22, channels)
        putIntLE(24, sampleRate)
        putIntLE(28, byteRate)
        putShortLE(32, blockAlign)
        putShortLE(34, 16) // bits per sample
        putAscii(36, "data")
        putIntLE(40, dataSize)
        return header
    }

    private fun cleanupTempFiles() {
        deleteQuietly(statusFile)
        deleteQuietly(stopFile)
        deleteQuietly(logFile)
        deleteQuietly(pidFile)
    }

    private fun deleteQuietly(file: File) {
        val deleted = try {
            !file.exists() || file.delete()
        } catch (_: Exception) {
            false
        }
        if (!deleted && RootShell.isGranted()) {
            // Files created by the root helper are normally unlinkable by the app
            // because it owns the parent directory. Keep a root fallback for
            // unusual SELinux policies, without ever blocking the caller.
            cleanupScope.launch {
                RootShell.runSu("rm -f ${quote(file.absolutePath)}", timeoutMs = 1_500L)
            }
        }
    }

    private fun quote(value: String): String = RootShell.shQuote(value)

    fun baseNameForDisplay(): String = baseName
}
