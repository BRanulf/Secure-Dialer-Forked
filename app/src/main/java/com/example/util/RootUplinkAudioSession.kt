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
import java.io.File

/**
 * App-side controller for [RootUplinkAudioDaemon].
 *
 * Unlike [RootIncallMusicSession], which launches a fresh root process for one
 * greeting, this session keeps a single daemon alive for the duration of a call.
 * Soundboard taps and the hold-to-bleep button send a command file instead of
 * paying the `su` + `app_process` startup cost for every sound.
 */
class RootUplinkAudioSession(context: Context) {

    companion object {
        private const val START_TIMEOUT_MS = 10_000L
        private const val SHUTDOWN_TIMEOUT_MS = 1_500L
        private const val STATUS_POLL_MS = 50L
        // The daemon exits by itself as a safety net if the app process dies. Use a
        // generous window so a long conference call does not lose the bridge.
        private const val DEFAULT_LIFETIME_SECONDS = 14_400L
    }

    data class StartResult(
        val success: Boolean,
        val routed: String? = null,
        val message: String? = null
    )

    private val appContext = context.applicationContext
    private val workDir = File(context.cacheDir, "IncallUplink").apply { mkdirs() }
    private val sessionId =
        "${System.currentTimeMillis()}_${Integer.toHexString(hashCode())}"
    private val statusFile = File(workDir, "$sessionId.status")
    private val commandFile = File(workDir, "$sessionId.command")
    private val commandTempFile = File(workDir, "$sessionId.command.tmp")
    private val logFile = File(workDir, "$sessionId.log")
    private val pidFile = File(workDir, "$sessionId.pid")

    @Volatile
    private var released = false

    @Volatile
    private var started = false

    @Volatile
    private var routed: String? = null

    private var sequence = 0L

    val isStarted: Boolean get() = started && !released

    /** Blocking; call from an IO dispatcher. */
    @Synchronized
    fun start(maxSeconds: Long = DEFAULT_LIFETIME_SECONDS): StartResult {
        if (released) {
            return StartResult(false, message = "session released")
        }
        if (started) {
            return StartResult(true, routed, null)
        }

        cleanupTempFiles()
        deleteQuietly(pidFile)

        val classpath = RootHelperDex.prepare(appContext)
            ?: return StartResult(false, message = "root helper dex is unavailable")

        val command = "CLASSPATH=${quote(classpath)} app_process /system/bin " +
                "com.example.util.RootUplinkAudioDaemon " +
                "${quote(statusFile.absolutePath)} " +
                "${quote(commandFile.absolutePath)} " +
                "${maxSeconds.coerceIn(60L, 14_400L)} " +
                "> ${quote(logFile.absolutePath)} 2>&1 & " +
                "echo \$! > ${quote(pidFile.absolutePath)}"

        val launch = RootShell.runSu(command, timeoutMs = 15_000L)
        if (launch.timedOut) {
            cleanupTempFiles()
            deleteQuietly(pidFile)
            return StartResult(false, message = "root request timed out")
        }

        val deadline = SystemClock.elapsedRealtime() + START_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val status = readStatus()
            when (status["state"]) {
                "ready", "playing" -> {
                    started = true
                    routed = status["routed"]
                    return StartResult(true, routed, null)
                }
                "error" -> {
                    val message = status["error"].orEmpty().ifBlank {
                        "Telephony Tx audio failed"
                    }
                    killIfAlive()
                    cleanupTempFiles()
                    deleteQuietly(pidFile)
                    return StartResult(false, message = message)
                }
            }
            try {
                Thread.sleep(STATUS_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }

        val logHint = readLastLogLine()
        val message = logHint?.takeIf { it.isNotBlank() }
            ?: "timed out starting Telephony Tx audio"
        killIfAlive()
        cleanupTempFiles()
        deleteQuietly(pidFile)
        return StartResult(false, message = message)
    }

    /** Non-blocking command; playback starts on the next daemon poll. */
    @Synchronized
    fun playPcm(file: File): Boolean {
        if (!file.isFile || file.length() < 2L) return false
        return sendCommand("play", file.absolutePath, 0)
    }

    /** Starts a continuous tone that plays until [stopPlayback] or [shutdown]. */
    fun playTone(frequency: Int = 1_000): Boolean {
        return sendCommand("tone", "", frequency.coerceIn(200, 4_000))
    }

    fun stopPlayback(): Boolean {
        return sendCommand("stop", "", 0)
    }

    @Synchronized
    private fun sendCommand(op: String, path: String, frequency: Int): Boolean {
        if (!started || released) return false
        val nextSequence = ++sequence
        val content = buildString {
            appendLine("seq=$nextSequence")
            appendLine("op=$op")
            if (path.isNotEmpty()) appendLine("path=$path")
            if (frequency > 0) appendLine("frequency=$frequency")
        }
        return writeAtomically(commandTempFile, commandFile, content)
    }

    private fun writeAtomically(temp: File, target: File, content: String): Boolean {
        return try {
            temp.writeText(content)
            makeWorldReadable(temp)
            if (target.exists()) target.delete()
            if (temp.renameTo(target)) {
                makeWorldReadable(target)
                true
            } else {
                target.writeText(content)
                makeWorldReadable(target)
                deleteQuietly(temp)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun makeWorldReadable(file: File) {
        try {
            file.setReadable(true, false)
        } catch (_: Exception) {}
    }

    /** Blocking cleanup; call from an IO dispatcher. */
    @Synchronized
    fun shutdown() {
        if (released) return
        released = true
        started = false

        val hadProcess = pidFile.exists()
        if (hadProcess) {
            // Best effort: stop the tone/sound and ask the daemon to exit.
            sequence++
            writeAtomically(
                commandTempFile,
                commandFile,
                "seq=$sequence\nop=shutdown\n"
            )
        }

        var stopped = !hadProcess
        val deadline = SystemClock.elapsedRealtime() + SHUTDOWN_TIMEOUT_MS
        while (!stopped && SystemClock.elapsedRealtime() < deadline) {
            if (readStatus()["state"] == "stopped") {
                stopped = true
                break
            }
            try {
                Thread.sleep(STATUS_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        if (!stopped) {
            killIfAlive()
        }
        cleanupTempFiles()
        deleteQuietly(pidFile)
    }

    private fun killIfAlive() {
        val pid = readPid() ?: return
        // Check liveness in the same root shell so a recycled PID is never killed.
        RootShell.runSu(
            "kill -0 $pid >/dev/null 2>&1 && kill -9 $pid",
            timeoutMs = 2_000L
        )
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

    private fun readPid(): String? {
        return try {
            if (!pidFile.exists()) return null
            pidFile.readText().trim().takeIf { it.matches(Regex("\\d+")) }
        } catch (_: Exception) {
            null
        }
    }

    private fun readLastLogLine(): String? {
        return try {
            if (!logFile.exists()) return null
            logFile.readLines().asReversed().firstOrNull { it.isNotBlank() }?.take(180)
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanupTempFiles() {
        deleteQuietly(statusFile)
        deleteQuietly(commandFile)
        deleteQuietly(commandTempFile)
        deleteQuietly(logFile)
    }

    private fun deleteQuietly(file: File) {
        try {
            if (file.exists()) file.delete()
        } catch (_: Exception) {}
    }

    private fun quote(value: String): String = RootShell.shQuote(value)
}
