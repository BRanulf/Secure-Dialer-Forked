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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

enum class RootStatus {
    UNKNOWN,
    CHECKING,
    GRANTED,
    DENIED,
    UNAVAILABLE
}

data class RootCheckResult(
    val status: RootStatus,
    val message: String? = null
) {
    val granted: Boolean get() = status == RootStatus.GRANTED
}

data class RootShellResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean = false
) {
    val succeeded: Boolean get() = !timedOut && exitCode == 0
}

/**
 * Minimal, privacy-safe wrapper around the device's `su` binary.
 *
 * The dialer uses root only when the user explicitly enables a root-powered feature:
 * - system-level call recording (AudioRecord via a root `app_process` helper)
 * - the answering machine (greeting injection + caller-message recording)
 *
 * No shell output is ever logged: command output can contain phone numbers or file paths.
 */
object RootShell {

    private const val PREFS = "secure_dialer_prefs"
    private const val KEY_WAS_GRANTED = "root_access_previously_granted"

    private val _status = MutableStateFlow(RootStatus.UNKNOWN)
    val status: StateFlow<RootStatus> = _status.asStateFlow()

    private val ioExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "SecureDialer-RootShell").apply { isDaemon = true }
    }

    private val suCandidates = listOf(
        "su",
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/debug_ramdisk/su",
        "/su/bin/su"
    )

    @Volatile
    private var preparedPackage: String? = null

    @Volatile
    private var lastDeniedReason: String? = null

    fun lastKnownStatus(): RootStatus = _status.value

    fun isGranted(): Boolean = _status.value == RootStatus.GRANTED

    /**
     * True when a previous root check succeeded. Used at process start to warm the
     * cached status without showing a root prompt on every launch.
     */
    fun wasGrantedBefore(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_WAS_GRANTED, false)
    }

    private fun persistGranted(context: Context, granted: Boolean) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_WAS_GRANTED, granted)
                .apply()
        } catch (_: Exception) {}
    }

    fun denialReason(): String? = lastDeniedReason

    /**
     * Runs the root check on an IO dispatcher. When [prepare] is true and root is granted,
     * also grants this package the capture app-ops used by the in-process fallback recorder.
     */
    suspend fun ensureAccess(
        context: Context,
        prepare: Boolean = true
    ): RootCheckResult = withContext(Dispatchers.IO) {
        checkBlocking(context.applicationContext, prepare)
    }

    @Synchronized
    fun checkBlocking(context: Context, prepare: Boolean = true): RootCheckResult {
        _status.value = RootStatus.CHECKING
        val appContext = context.applicationContext

        val identity = runSu("id", timeoutMs = 12_000)
        val output = identity.output
        val rootGranted = identity.succeeded && output.contains("uid=0")

        if (!rootGranted) {
            val binaryMissing = identity.exitCode < 0 ||
                    output.contains("not found", ignoreCase = true) ||
                    output.contains("No such file", ignoreCase = true) ||
                    output.contains("inaccessible", ignoreCase = true)
            lastDeniedReason = when {
                identity.timedOut -> "Root request timed out"
                binaryMissing -> "su binary not available"
                else -> "Root access was denied"
            }
            _status.value = if (binaryMissing) RootStatus.UNAVAILABLE else RootStatus.DENIED
            persistGranted(appContext, false)
            return RootCheckResult(_status.value, lastDeniedReason)
        }

        lastDeniedReason = null
        _status.value = RootStatus.GRANTED
        persistGranted(appContext, true)
        if (prepare) {
            prepareCaptureAccess(appContext)
        }
        return RootCheckResult(RootStatus.GRANTED, null)
    }

    /**
     * Allows the app UID to open call audio sources on ROMs that honour the
     * CAPTURE_AUDIO_OUTPUT app-op. This is a best-effort convenience for the
     * in-process fallback; the root `app_process` helper does not depend on it.
     */
    private fun prepareCaptureAccess(context: Context) {
        val pkg = context.packageName
        if (preparedPackage == pkg) return

        val quotedPackage = shQuote(pkg)
        // Different OEM `appops` builds accept either the symbolic constant or the
        // fully-qualified op name, so try both and ignore failures.
        runSu("cmd appops set $quotedPackage CAPTURE_AUDIO_OUTPUT allow", 4_000)
        runSu("cmd appops set $quotedPackage android:capture_audio_output allow", 4_000)
        runSu("cmd appops set $quotedPackage RECORD_AUDIO allow", 3_000)
        runSu("pm grant $quotedPackage android.permission.CAPTURE_AUDIO_OUTPUT", 3_000)
        preparedPackage = pkg
    }

    /**
     * Executes [command] through `su -c`. Always returns; callers must inspect
     * [RootShellResult.exitCode] / [RootShellResult.timedOut].
     */
    fun runSu(command: String, timeoutMs: Long = 8_000): RootShellResult {
        var lastError: IOException? = null
        for (candidate in suCandidates) {
            try {
                return runProcess(listOf(candidate, "-c", command), timeoutMs)
            } catch (e: IOException) {
                lastError = e
            }
        }
        return RootShellResult(
            exitCode = -1,
            output = lastError?.message ?: "su binary not found",
            timedOut = false
        )
    }

    private fun runProcess(command: List<String>, timeoutMs: Long): RootShellResult {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        val outputFuture = ioExecutor.submit<String> {
            try {
                process.inputStream.bufferedReader().use { it.readText() }
            } catch (_: Exception) {
                ""
            }
        }
        val waitFuture = ioExecutor.submit<Int> {
            try {
                process.waitFor()
            } catch (_: Exception) {
                -1
            }
        }

        return try {
            val exitCode = waitFuture.get(timeoutMs, TimeUnit.MILLISECONDS)
            val output = try {
                outputFuture.get(1_500, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                ""
            }
            RootShellResult(exitCode, output, false)
        } catch (_: TimeoutException) {
            try {
                process.destroy()
            } catch (_: Exception) {}
            try {
                process.waitFor(250, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {}
            RootShellResult(-1, "Root command timed out", true)
        } catch (e: Exception) {
            try {
                process.destroy()
            } catch (_: Exception) {}
            RootShellResult(-1, e.message ?: "Root command failed", false)
        }
    }

    fun shQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun fileExists(path: String): Boolean = try {
        File(path).exists()
    } catch (_: Exception) {
        false
    }
}
