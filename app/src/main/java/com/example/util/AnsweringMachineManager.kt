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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.VideoProfile
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.CallManager
import com.example.MainActivity
import com.example.R
import com.example.data.AppDatabase
import com.example.model.CallRecording
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Local answering machine.
 *
 * Flow:
 * 1. An incoming call rings; if enabled, a configurable timer starts.
 * 2. When the timer fires (or the user taps the in-call answering-machine button), the
 *    call is answered by the app, the microphone uplink is muted, and a root recorder
 *    starts capturing the caller's side.
 * 3. A greeting is injected into the call's uplink through the vendor mixer when the
 *    device/root setup supports it. Otherwise the caller-side message is still recorded
 *    and the user is notified that the greeting could not be played to the caller.
 * 4. Recording stops when the caller hangs up or the configured maximum length is
 *    reached; the message is saved in the normal recordings list.
 *
 * The answering machine requires root because Android exposes no public API to answer
 * and inject audio into a carrier call.
 */
object AnsweringMachineManager {

    private const val PREFS = "dialer_prefs"
    private const val KEY_ENABLED = "is_answering_machine_enabled"
    private const val KEY_TIMEOUT = "answering_machine_timeout_seconds"
    private const val KEY_MAX_MESSAGE = "answering_machine_max_seconds"

    const val DEFAULT_TIMEOUT_SECONDS = 30
    const val DEFAULT_MAX_MESSAGE_SECONDS = 180

    private const val CHANNEL_ID = "answering_machine_channel"
    private const val NOTIFICATION_ROOT_MISSING = 7101
    private const val NOTIFICATION_GREETING_UNAVAILABLE = 7102
    private const val NOTIFICATION_MESSAGE_SAVED = 7103
    private const val NOTIFICATION_RECORDING_FAILED = 7104

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val startLock = Any()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private var pendingAutoAnswerCall: Call? = null
    private var pendingAutoAnswerJob: Job? = null

    private var activeCall: Call? = null
    private var activeJob: Job? = null
    private var recordingSession: RootCallRecordingSession? = null

    @Volatile
    private var musicSession: RootIncallMusicSession? = null

    @Volatile
    private var lastGreetingError: String? = null

    @Volatile
    private var starting = false

    @Volatile
    private var takeoverRequested = false

    @Volatile
    private var callEnded = false

    @Volatile
    private var greetingContinuation: kotlinx.coroutines.CancellableContinuation<Boolean>? = null

    @Volatile
    private var greetingTimeoutJob: Job? = null

    @Volatile
    private var greetingPlayer: MediaPlayer? = null

    private var lastRootNotificationAt = 0L
    private var lastGreetingNotificationAt = 0L

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    }

    fun timeoutSeconds(context: Context): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_TIMEOUT, DEFAULT_TIMEOUT_SECONDS)
            .coerceIn(5, 300)
    }

    fun maxMessageSeconds(context: Context): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MAX_MESSAGE, DEFAULT_MAX_MESSAGE_SECONDS)
            .coerceIn(15, 900)
    }

    fun isBusy(): Boolean = starting || _isActive.value || activeJob?.isActive == true

    /**
     * Arms the auto-answer timer for a ringing call. Safe to call repeatedly for the
     * same call; a second call to this method does not reset an existing timer.
     */
    fun scheduleAutoAnswer(context: Context, call: Call) {
        if (!isEnabled(context)) return
        if (call.state != Call.STATE_RINGING) return
        val knownRootStatus = RootShell.lastKnownStatus()
        if (knownRootStatus == RootStatus.DENIED || knownRootStatus == RootStatus.UNAVAILABLE) {
            return
        }
        if (pendingAutoAnswerCall === call && pendingAutoAnswerJob?.isActive == true) return

        cancelAutoAnswer()
        pendingAutoAnswerCall = call

        val appContext = context.applicationContext
        pendingAutoAnswerJob = scope.launch {
            try {
                // Warm up the root grant in parallel with the ring timer. The first
                // root feature use surfaces the root manager prompt while the phone
                // is still ringing, instead of after it has answered.
                if (RootShell.lastKnownStatus() == RootStatus.UNKNOWN) {
                    scope.launch { RootShell.ensureAccess(appContext, prepare = true) }
                }

                delay(timeoutSeconds(appContext) * 1000L)

                if (pendingAutoAnswerCall !== call ||
                    call.state != Call.STATE_RINGING ||
                    isBusy()
                ) {
                    return@launch
                }
                startInternal(appContext, call)
            } finally {
                if (pendingAutoAnswerCall === call) {
                    pendingAutoAnswerCall = null
                    pendingAutoAnswerJob = null
                }
            }
        }
    }

    fun cancelAutoAnswer(call: Call? = null) {
        if (call != null && pendingAutoAnswerCall !== call) return
        pendingAutoAnswerJob?.cancel()
        pendingAutoAnswerJob = null
        pendingAutoAnswerCall = null
    }

    /**
     * User-initiated start from the incoming-call screen.
     * Returns true when a start attempt has been queued.
     */
    fun trigger(context: Context, call: Call? = CallManager.currentCall.value): Boolean {
        val target = call ?: return false
        if (target.state != Call.STATE_RINGING) return false
        if (!isEnabled(context)) return false
        if (isBusy()) return false

        val appContext = context.applicationContext
        val knownRootStatus = RootShell.lastKnownStatus()
        if (knownRootStatus == RootStatus.DENIED || knownRootStatus == RootStatus.UNAVAILABLE) {
            Toast.makeText(
                appContext,
                appContext.getString(R.string.root_required_toast),
                Toast.LENGTH_LONG
            ).show()
            return false
        }

        Toast.makeText(
            appContext,
            appContext.getString(R.string.answering_machine_starting),
            Toast.LENGTH_SHORT
        ).show()

        scope.launch { startInternal(appContext, target) }
        return true
    }

    /**
     * Stops the answering machine and hands the live call back to the user.
     * Any part of the caller's message recorded so far is saved.
     */
    fun takeOver(context: Context) {
        if (!isBusy()) return
        takeoverRequested = true
        musicSession?.requestStop()
        mainHandler.post { finishGreeting(false) }
        // Give the user the microphone back immediately; the recorder is stopped
        // asynchronously by the session loop.
        try {
            val call = activeCall
            if (call?.state == Call.STATE_ACTIVE || call?.state == Call.STATE_HOLDING) {
                CallManager.setMuted(false)
            }
        } catch (_: Exception) {}
    }

    fun onCallStateChanged(call: Call) {
        if (call.state != Call.STATE_RINGING) {
            cancelAutoAnswer(call)
        }
        if (call.state == Call.STATE_DISCONNECTED || call.state == Call.STATE_DISCONNECTING) {
            if (activeCall === call) {
                callEnded = true
                musicSession?.requestStop()
                mainHandler.post { finishGreeting(false) }
            }
        }
    }

    private suspend fun startInternal(context: Context, call: Call) {
        if (call.state != Call.STATE_RINGING) return
        synchronized(startLock) {
            if (starting || _isActive.value) return
            starting = true
        }

        try {
            if (!isEnabled(context)) return

            val knownRootStatus = RootShell.lastKnownStatus()
            if (knownRootStatus == RootStatus.DENIED || knownRootStatus == RootStatus.UNAVAILABLE) {
                notifyRootMissing(context)
                return
            }

            val root = RootShell.ensureAccess(context, prepare = true)
            if (!root.granted) {
                notifyRootMissing(context)
                return
            }

            if (call.state != Call.STATE_RINGING) return

            // Claim the call before answering so CallManager's auto-record path never
            // starts a competing recorder on the STATE_ACTIVE transition.
            _isActive.value = true
            activeCall = call
            takeoverRequested = false
            callEnded = false

            try {
                call.answer(VideoProfile.STATE_AUDIO_ONLY)
            } catch (e: Exception) {
                _isActive.value = false
                activeCall = null
                notifyRecordingFailed(context)
                return
            }
            // Mute immediately after answering so the room is never transmitted,
            // even during the brief CONNECTING window. A mute failure must not
            // abort the answering machine.
            try {
                CallManager.setMuted(true)
            } catch (_: Exception) {}

            starting = false
            activeJob = scope.launch { runSession(context.applicationContext, call) }
        } finally {
            starting = false
            if (_isActive.value && activeCall === call && activeJob?.isActive != true) {
                // Answer failed before the session job was created.
                if (call.state == Call.STATE_RINGING) {
                    _isActive.value = false
                    activeCall = null
                }
            }
        }
    }

    private suspend fun runSession(context: Context, call: Call) {
        val number = CallManager.callerNumber.value.ifBlank {
            call.details?.handle?.schemeSpecificPart ?: ""
        }
        val displayName = CallManager.callerName.value.ifBlank {
            number.ifBlank { context.getString(R.string.unknown) }
        }

        var session: RootCallRecordingSession? = null
        var recordingFailed = false

        try {
            // 1. Wait for the call to become active after answering.
            val becameActive = waitForActive(call, 12_000L)
            if (!becameActive) {
                if (call.state == Call.STATE_ACTIVE) {
                    // Extremely fast transition; proceed.
                } else {
                    return
                }
            }

            // 2. A carrier call that was auto-answered must never transmit room audio.
            CallManager.setMuted(true)

            // 3. Start capturing the caller's side before playing the greeting so the
            //    message is never missed.
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val rootSession = RootCallRecordingSession(context, number, timestamp)
            session = rootSession
            recordingSession = rootSession

            val startResult = rootSession.start(
                preferredSource = 3, // VOICE_DOWNLINK – the caller, never the local mic
                maxDurationSeconds = maxMessageSeconds(context) + 120L
            )
            if (!startResult.success) {
                recordingFailed = true
                notifyRecordingFailed(context)
                try {
                    call.disconnect()
                } catch (_: Exception) {}
                return
            }

            // 4. Best-effort greeting injection into the uplink. Recording continues
            //    regardless so the caller's message is never lost.
            if (!takeoverRequested && !callEnded) {
                val greetingPlayed = try {
                    playUplinkGreeting(context)
                } catch (_: Exception) {
                    false
                }
                if (!greetingPlayed) {
                    notifyGreetingUnavailable(context, lastGreetingError)
                }
            }

            // 5. Record the caller's message.
            val deadline = System.currentTimeMillis() +
                    maxMessageSeconds(context) * 1000L
            while (!takeoverRequested && !callEnded &&
                call.state != Call.STATE_DISCONNECTED &&
                call.state != Call.STATE_DISCONNECTING &&
                System.currentTimeMillis() < deadline
            ) {
                delay(250L)
            }

            if (!takeoverRequested && !callEnded &&
                System.currentTimeMillis() >= deadline &&
                call.state != Call.STATE_DISCONNECTED &&
                call.state != Call.STATE_DISCONNECTING
            ) {
                try {
                    call.disconnect()
                } catch (_: Exception) {}
            }
        } finally {
            mainHandler.post { finishGreeting(false) }
            try {
                RootUplinkAudioInjector.disable(context)
            } catch (_: Exception) {}
            try {
                musicSession?.stop()
            } catch (_: Exception) {}
            musicSession = null

            val stopped = session?.stop()
            recordingSession = null

            if (stopped?.file != null && stopped.file.exists() && stopped.file.length() > 1024L) {
                saveRecording(
                    context = context,
                    number = number,
                    displayName = displayName,
                    durationSeconds = stopped.durationSeconds.coerceAtLeast(1L),
                    file = stopped.file
                )
            } else if (!recordingFailed && session != null) {
                notifyRecordingFailed(context)
            }

            if (call.state == Call.STATE_ACTIVE || call.state == Call.STATE_HOLDING) {
                try {
                    CallManager.setMuted(false)
                } catch (_: Exception) {}
            }

            _isActive.value = false
            activeCall = null
            activeJob = null
            starting = false
            takeoverRequested = false
        }
    }

    /**
     * Preferred path: play the greeting through the AOSP "incall music" output
     * (Telephony Tx), which modern Qualcomm/AOSP audio policies expose. The root
     * helper runs as UID 0 so it may select the telephony TX device during a call.
     * Vendor mixer controls (tinymix) remain as a fallback.
     */
    private suspend fun playUplinkGreeting(context: Context): Boolean {
        if (takeoverRequested || callEnded) return false
        lastGreetingError = null

        val greetingPcm = AnsweringMachinePcm.ensure(context)
        if (takeoverRequested || callEnded) return false
        if (greetingPcm == null) {
            lastGreetingError = "The greeting audio could not be decoded"
        } else {
            val session = RootIncallMusicSession(context, greetingPcm.file)
            // Publish before start so takeover/hang-up can stop a helper that is
            // still initialising.
            musicSession = session
            val result = session.start(maxSeconds = 120L)
            if (result.success) {
                return true
            }
            lastGreetingError = result.message ?: "Telephony Tx playback failed"
            musicSession = null
        }

        if (takeoverRequested || callEnded) return false
        if (playGreetingThroughMixer(context)) {
            lastGreetingError = null
            return true
        }
        if (lastGreetingError.isNullOrBlank()) {
            lastGreetingError = "No Telephony Tx route or vendor mixer control is available"
        }
        return false
    }

    private suspend fun playGreetingThroughMixer(context: Context): Boolean {
        val injected = RootUplinkAudioInjector.enable(context)
        if (!injected) return false
        return try {
            playGreeting(context)
        } finally {
            try {
                RootUplinkAudioInjector.disable(context)
            } catch (_: Exception) {}
        }
    }

    private suspend fun waitForActive(call: Call, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            when (call.state) {
                Call.STATE_ACTIVE -> return true
                Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> return false
                else -> delay(100L)
            }
        }
        return call.state == Call.STATE_ACTIVE
    }

    /**
     * Plays the greeting through MediaPlayer. The caller hears it only when the
     * vendor mixer uplink route was successfully enabled by [RootUplinkAudioInjector].
     */
    private suspend fun playGreeting(context: Context): Boolean = withContext(Dispatchers.Main) {
        val source = AnsweringMachineGreeting.resolve(context)
        suspendCancellableCoroutine { continuation ->
            greetingContinuation = continuation

            val player = MediaPlayer()
            greetingPlayer = player
            val finished = AtomicBoolean(false)

            fun complete(result: Boolean) {
                if (!finished.compareAndSet(false, true)) return
                greetingTimeoutJob?.cancel()
                greetingTimeoutJob = null
                try {
                    if (player.isPlaying) player.stop()
                } catch (_: Exception) {}
                try {
                    player.release()
                } catch (_: Exception) {}
                if (greetingPlayer === player) greetingPlayer = null
                greetingContinuation = null
                if (continuation.isActive) continuation.resume(result)
            }

            continuation.invokeOnCancellation { complete(false) }

            try {
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                player.setVolume(1f, 1f)
                AnsweringMachineGreeting.setDataSource(context, player, source)
                player.setOnCompletionListener { complete(true) }
                player.setOnErrorListener { _, _, _ ->
                    complete(false)
                    true
                }
                player.prepare()
                player.start()

                val duration = try {
                    player.duration.toLong()
                } catch (_: Exception) {
                    0L
                }
                greetingTimeoutJob = scope.launch {
                    delay((duration + 5_000L).coerceIn(5_000L, 120_000L))
                    mainHandler.post { complete(false) }
                }
            } catch (_: Exception) {
                complete(false)
            }
        }
    }

    private fun finishGreeting(played: Boolean) {
        val continuation = greetingContinuation
        greetingContinuation = null
        greetingTimeoutJob?.cancel()
        greetingTimeoutJob = null
        try {
            greetingPlayer?.let { player ->
                try {
                    if (player.isPlaying) player.stop()
                } catch (_: Exception) {}
                try {
                    player.release()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {
        } finally {
            greetingPlayer = null
        }
        if (continuation != null && continuation.isActive) {
            continuation.resume(played)
        }
    }

    private fun saveRecording(
        context: Context,
        number: String,
        displayName: String,
        durationSeconds: Long,
        file: java.io.File
    ) {
        try {
            val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.resources.configuration.locales[0]
            } else {
                @Suppress("DEPRECATION")
                context.resources.configuration.locale
            }
            val timestamp = SimpleDateFormat("MMM d, HH:mm", locale).format(Date())
            val recording = CallRecording(
                number = number.ifBlank { "Unknown" },
                name = context.getString(R.string.answering_machine_recording_name, displayName),
                timestamp = timestamp,
                duration = durationSeconds.coerceAtLeast(1L),
                filePath = file.absolutePath,
                note = context.getString(R.string.answering_machine_note)
            )

            scope.launch {
                try {
                    val db = AppDatabase.getDatabase(context)
                    if (db.dialerDao().getCallRecordingByPath(file.absolutePath) == null) {
                        db.dialerDao().insertCallRecording(recording)
                    }
                } catch (_: Exception) {}
            }

            if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean("is_auto_export_recordings_enabled", true)
            ) {
                CallAudioRecorder.exportRecordingToPublicDownloads(context, file)
            }

            notifyMessageSaved(context, displayName)
        } catch (_: Exception) {}
    }

    private fun notifyRootMissing(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastRootNotificationAt < 60_000L) return
        lastRootNotificationAt = now
        notify(
            context,
            NOTIFICATION_ROOT_MISSING,
            context.getString(R.string.answering_machine_title),
            context.getString(R.string.answering_machine_root_missing)
        )
    }

    private fun notifyGreetingUnavailable(context: Context, detail: String? = null) {
        val now = System.currentTimeMillis()
        if (now - lastGreetingNotificationAt < 60_000L) return
        lastGreetingNotificationAt = now
        val base = context.getString(R.string.answering_machine_greeting_unavailable)
        val text = if (detail.isNullOrBlank()) base else "$base\n$detail"
        notify(
            context,
            NOTIFICATION_GREETING_UNAVAILABLE,
            context.getString(R.string.answering_machine_title),
            text
        )
    }

    private fun notifyRecordingFailed(context: Context) {
        notify(
            context,
            NOTIFICATION_RECORDING_FAILED,
            context.getString(R.string.answering_machine_title),
            context.getString(R.string.answering_machine_recording_failed)
        )
    }

    private fun notifyMessageSaved(context: Context, displayName: String) {
        notify(
            context,
            NOTIFICATION_MESSAGE_SAVED,
            context.getString(R.string.answering_machine_title),
            context.getString(R.string.answering_machine_message_saved, displayName)
        )
    }

    private fun notify(context: Context, id: Int, title: String, text: String) {
        try {
            ensureChannel(context)
            val contentIntent = PendingIntent.getActivity(
                context,
                9000 + id,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .build()
            if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                NotificationManagerCompat.from(context).notify(id, notification)
            }
        } catch (_: Exception) {}
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.answering_machine_channel),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.answering_machine_channel_desc)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }
}
