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
 * App-side launcher for [RootIncallMusicPlayer].
 *
 * The helper runs as UID 0 through `su` + `app_process`, selects the
 * Telephony Tx output device and plays the greeting into the carrier call's
 * uplink. This is the supported AOSP "incall music" path and does not require
 * vendor mixer controls or tinymix.
 */
class RootIncallMusicSession(
    context: Context,
    private val greetingFile: File
) {
    companion object {
        private const val START_TIMEOUT_MS = 6_000L
        private const val STOP_TIMEOUT_MS = 1_200L
        private const val STATUS_POLL_MS = 50L
    }

    data class StartResult(
        val success: Boolean,
        val routed: String? = null,
        val message: String? = null
    )

    private val appContext = context.applicationContext
    private val workDir = File(context.cacheDir, "IncallMusic").apply { mkdirs() }
    private val sessionId = "${System.currentTimeMillis()}_${greetingFile.name.hashCode()}"
    private val statusFile = File(workDir, "$sessionId.status")
    private val stopFile = File(workDir, "$sessionId.stop")
    private val logFile = File(workDir, "$sessionId.log")
    private val pidFile = File(workDir, "$sessionId.pid")

    @Volatile
    private var released = false

    /** Blocking; call from an IO dispatcher. */
    @Synchronized
    fun start(maxSeconds: Long = 120L): StartResult {
        if (released) return StartResult(false, message = "session released")
        cleanupTempFiles()
        if (!greetingFile.isFile || greetingFile.length() <= 0L) {
            return StartResult(false, message = "greeting file is missing")
        }

        val classpath = RootHelperDex.prepare(appContext)
            ?: return StartResult(false, message = "root helper dex is unavailable")

        val command = "CLASSPATH=${quote(classpath)} app_process /system/bin " +
                "com.example.util.RootIncallMusicPlayer " +
                "${quote(greetingFile.absolutePath)} " +
                "${quote(statusFile.absolutePath)} " +
                "${quote(stopFile.absolutePath)} " +
                "${maxSeconds.coerceIn(5L, 300L)} " +
                "> ${quote(logFile.absolutePath)} 2>&1 & " +
                "echo \$! > ${quote(pidFile.absolutePath)}"

        val launch = RootShell.runSu(command, timeoutMs = 15_000L)
        if (launch.timedOut) {
            cleanupTempFiles()
            return StartResult(false, message = "root request timed out")
        }

        val deadline = SystemClock.elapsedRealtime() + START_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val status = readStatus()
            when (status["state"]) {
                "playing", "done" -> {
                    val routed = status["routed"]
                    return StartResult(true, routed, null)
                }
                "error" -> {
                    val message = status["error"].orEmpty().ifBlank {
                        "Telephony Tx playback failed"
                    }
                    requestStop()
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
            ?: "timed out starting Telephony Tx playback"
        // The helper may still be loading; signal it to stop and clean up.
        requestStop()
        killIfAlive()
        cleanupTempFiles()
        deleteQuietly(pidFile)
        return StartResult(false, message = message)
    }

    /** Non-blocking stop signal for takeover/hang-up paths. */
    fun requestStop() {
        try {
            stopFile.writeText("stop")
        } catch (_: Exception) {}
    }

    /** Blocking cleanup; call from an IO dispatcher. */
    @Synchronized
    fun stop() {
        if (released) return
        released = true
        requestStop()

        var finished = readStatus()["state"] == "done"
        val deadline = SystemClock.elapsedRealtime() + STOP_TIMEOUT_MS
        while (!finished && SystemClock.elapsedRealtime() < deadline) {
            if (readStatus()["state"] == "done") {
                finished = true
                break
            }
            try {
                Thread.sleep(STATUS_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }

        // Never send a signal to a reused PID after the helper already exited.
        if (!finished) {
            killIfAlive()
        }
        cleanupTempFiles()
        deleteQuietly(pidFile)
    }

    private fun killIfAlive() {
        val pid = readPid() ?: return
        // The shell condition checks liveness in the same root command so a
        // recycled PID can never be killed by the app.
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
        deleteQuietly(stopFile)
        deleteQuietly(logFile)
    }

    private fun deleteQuietly(file: File) {
        try {
            if (file.exists()) file.delete()
        } catch (_: Exception) {}
    }

    private fun quote(value: String): String = RootShell.shQuote(value)
}
