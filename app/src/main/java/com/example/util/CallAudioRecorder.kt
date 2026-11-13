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

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import com.example.R
import com.example.model.CallRecording
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

enum class RecordingCompressionProfile(
    val key: String,
    val title: String,
    val description: String,
    val bitRate: Int,
    val sampleRate: Int,
    val estMbPerHour: Double
) {
    COMPACT(
        key = "COMPACT",
        title = "Compact Voice (High Compression)",
        description = "24 kbps AAC • ~11 MB/hr • Maximum storage savings",
        bitRate = 24000,
        sampleRate = 16000,
        estMbPerHour = 10.8
    ),
    BALANCED(
        key = "BALANCED",
        title = "Balanced Standard (Recommended)",
        description = "48 kbps AAC • ~22 MB/hr • Crystal-clear voice clarity",
        bitRate = 48000,
        sampleRate = 16000,
        estMbPerHour = 21.6
    ),
    HIGH_FIDELITY(
        key = "HIGH_FIDELITY",
        title = "High Fidelity (Studio)",
        description = "96 kbps AAC @ 44.1 kHz • ~43 MB/hr • Rich acoustic fidelity",
        bitRate = 96000,
        sampleRate = 44100,
        estMbPerHour = 43.2
    );

    companion object {
        fun fromKey(key: String?): RecordingCompressionProfile {
            return entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: BALANCED
        }
    }
}

enum class RecordingEngine(val key: String) {
    /** MediaRecorder-based, works without root, but can usually only capture the local side. */
    STANDARD("STANDARD"),

    /** Root/system-level AudioRecord capture with automatic Standard fallback. */
    ROOT("ROOT");

    companion object {
        fun fromKey(key: String?): RecordingEngine {
            return entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: STANDARD
        }
    }
}

object CallAudioRecorder {

    const val PREF_RECORDING_ENGINE = "recording_engine"

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDuration = MutableStateFlow(0)
    val recordingDuration: StateFlow<Int> = _recordingDuration.asStateFlow()

    private val _lastRootSource = MutableStateFlow(0)
    val lastRootSource: StateFlow<Int> = _lastRootSource.asStateFlow()

    private var mediaRecorder: MediaRecorder? = null
    private var currentOutputFile: File? = null
    private var timerJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isStoppingOrStopped = AtomicBoolean(false)

    private var rootSession: RootCallRecordingSession? = null
    private var rootStartJob: Job? = null
    private var rootStartGeneration = 0
    private var rootStarting = false

    @Volatile
    private var cachedLastResult: RecordingResult? = null

    data class RecordingResult(val file: File?, val durationSeconds: Long)

    // =====================================================================
    // Engine selection
    // =====================================================================

    fun getSelectedEngine(context: Context): RecordingEngine {
        val prefs = context.getSharedPreferences("dialer_prefs", Context.MODE_PRIVATE)
        val key = prefs.getString(PREF_RECORDING_ENGINE, null)
            ?: context.getSharedPreferences("secure_dialer_prefs", Context.MODE_PRIVATE)
                .getString(PREF_RECORDING_ENGINE, RecordingEngine.STANDARD.key)
        return RecordingEngine.fromKey(key)
    }

    fun setSelectedEngine(context: Context, engine: RecordingEngine) {
        context.getSharedPreferences("dialer_prefs", Context.MODE_PRIVATE)
            .edit().putString(PREF_RECORDING_ENGINE, engine.key).apply()
        context.getSharedPreferences("secure_dialer_prefs", Context.MODE_PRIVATE)
            .edit().putString(PREF_RECORDING_ENGINE, engine.key).apply()
    }

    fun isRootEngineSelected(context: Context): Boolean =
        getSelectedEngine(context) == RecordingEngine.ROOT

    // =====================================================================
    // Start / stop
    // =====================================================================

    fun startRecording(
        context: Context,
        phoneNumber: String,
        profile: RecordingCompressionProfile = getSelectedCompressionProfile(context),
        allowRoot: Boolean = true
    ): Boolean = synchronized(this) {
        if (_isRecording.value) return false
        cachedLastResult = null
        isStoppingOrStopped.set(false)

        val engine = if (allowRoot) getSelectedEngine(context) else RecordingEngine.STANDARD
        if (engine == RecordingEngine.ROOT) {
            when (RootShell.lastKnownStatus()) {
                RootStatus.DENIED, RootStatus.UNAVAILABLE -> {
                    showToastLater(context, R.string.root_recording_fallback)
                    return startStandardRecordingLocked(context, phoneNumber, profile)
                }
                else -> {}
            }
            return startRootRecordingLocked(context, phoneNumber, profile)
        }

        return startStandardRecordingLocked(context, phoneNumber, profile)
    }

    /**
     * Starts the root engine optimistically so callers never block on a `su` handshake.
     * If the root helper cannot capture call audio, the recording transparently falls
     * back to the standard MediaRecorder engine.
     */
    private fun startRootRecordingLocked(
        context: Context,
        phoneNumber: String,
        profile: RecordingCompressionProfile
    ): Boolean {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val session = RootCallRecordingSession(context, phoneNumber, timestamp)

        rootSession = session
        rootStarting = true
        _isRecording.value = true
        _recordingDuration.value = 0
        startTimerLocked()

        val generation = ++rootStartGeneration
        rootStartJob = ioScope.launch {
            val result = session.start(preferredSource = 4)
            var shouldAbort = false
            var shouldFallback = false
            var succeeded = false

            synchronized(this@CallAudioRecorder) {
                if (generation != rootStartGeneration ||
                    rootSession !== session ||
                    isStoppingOrStopped.get()
                ) {
                    shouldAbort = true
                } else {
                    rootStarting = false
                    if (result.success) {
                        succeeded = true
                        _lastRootSource.value = result.source
                    } else {
                        rootSession = null
                        timerJob?.cancel()
                        timerJob = null
                        _isRecording.value = false
                        _recordingDuration.value = 0
                        shouldFallback = true
                    }
                }
            }

            // Cleanup and standard-engine fallback must not hold the recorder lock:
            // abort() may invoke su, and standard startup talks to MediaRecorder.
            if (shouldAbort) {
                session.abort()
                return@launch
            }

            if (succeeded) {
                if (result.source == 3) {
                    showToastLater(context, R.string.root_recording_remote_only)
                }
                return@launch
            }

            if (shouldFallback) {
                showToastLater(context, R.string.root_recording_fallback)
                synchronized(this@CallAudioRecorder) {
                    if (generation == rootStartGeneration && !isStoppingOrStopped.get()) {
                        startStandardRecordingLocked(context, phoneNumber, profile)
                    }
                }
            }
        }
        return true
    }

    private fun startStandardRecordingLocked(
        context: Context,
        phoneNumber: String,
        profile: RecordingCompressionProfile
    ): Boolean {
        try {
            val recordDir = File(context.filesDir, "CallRecordings").apply {
                if (!exists()) mkdirs()
            }

            val cleanNum = phoneNumber.filter { it.isDigit() }.ifEmpty { "Unknown" }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            // FIXED: Always generate a unique file so historical recordings for the same contact are never overwritten
            val outputFile = File(recordDir, "REC_${cleanNum}_$timestamp.m4a").apply {
                if (!exists()) {
                    try { createNewFile() } catch (_: Exception) {}
                }
            }

            var recorder: MediaRecorder? = null
            var success = false

            val candidateConfigs = listOf(
                Pair(MediaRecorder.AudioSource.VOICE_COMMUNICATION, profile.sampleRate),
                Pair(MediaRecorder.AudioSource.MIC, profile.sampleRate),
                Pair(MediaRecorder.AudioSource.VOICE_RECOGNITION, profile.sampleRate),
                Pair(MediaRecorder.AudioSource.DEFAULT, 16000)
            )

            for ((src, sampleRate) in candidateConfigs) {
                try {
                    @Suppress("DEPRECATION")
                    val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        MediaRecorder(context)
                    } else {
                        MediaRecorder()
                    }

                    rec.apply {
                        setAudioSource(src)
                        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        setAudioSamplingRate(sampleRate)
                        setAudioEncodingBitRate(profile.bitRate)
                        setOutputFile(outputFile.absolutePath)
                        prepare()
                        start()
                    }
                    recorder = rec
                    success = true
                    break
                } catch (_: Exception) {
                    try { recorder?.release() } catch (_: Exception) {}
                    recorder = null
                }
            }

            if (!success || recorder == null) {
                try { if (outputFile.exists() && outputFile.length() == 0L) outputFile.delete() } catch (_: Exception) {}
                _isRecording.value = false
                _recordingDuration.value = 0
                return false
            }

            mediaRecorder = recorder
            currentOutputFile = outputFile
            _isRecording.value = true
            _recordingDuration.value = 0
            startTimerLocked()

            return true
        } catch (_: Exception) {
            try {
                mediaRecorder?.release()
            } catch (_: Exception) {}
            mediaRecorder = null
            currentOutputFile = null
            _isRecording.value = false
            _recordingDuration.value = 0
            return false
        }
    }

    private fun startTimerLocked() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive) {
                delay(1000)
                _recordingDuration.value += 1
            }
        }
    }

    fun stopRecording(): RecordingResult = synchronized(this) {
        val session = rootSession
        if (session != null) {
            // Invalidate any in-flight root startup before releasing the session.
            val startupStillRunning = rootStarting
            rootStartGeneration++
            rootSession = null
            rootStarting = false
            isStoppingOrStopped.set(true)
            timerJob?.cancel()
            timerJob = null

            val timerDuration = _recordingDuration.value.toLong()
            _isRecording.value = false
            _recordingDuration.value = 0

            // Never block the main thread behind a `su`/app_process startup poll.
            // The background startup coroutine sees the generation bump and aborts
            // the half-started helper on its own.
            if (startupStillRunning) {
                // Signal the helper without waiting for startup to finish.
                try {
                    session.requestStop()
                } catch (_: Exception) {}
                cachedLastResult = RecordingResult(null, 0L)
                return cachedLastResult!!
            }

            val result = session.stop()
            val file = result.file
            val finalResult = if (file != null && file.exists() && file.length() > 1024L) {
                val duration = if (result.durationSeconds > 0L) {
                    result.durationSeconds
                } else {
                    timerDuration.coerceAtLeast(1L)
                }
                RecordingResult(file, duration)
            } else {
                RecordingResult(null, 0L)
            }

            cachedLastResult = finalResult
            return finalResult
        }

        if (!_isRecording.value || !isStoppingOrStopped.compareAndSet(false, true)) {
            return cachedLastResult ?: RecordingResult(null, 0L)
        }

        timerJob?.cancel()
        timerJob = null

        val finalDuration = _recordingDuration.value.toLong()
        val file = currentOutputFile

        try {
            mediaRecorder?.let { recorder ->
                try { recorder.stop() } catch (_: Exception) {}
                try { recorder.reset() } catch (_: Exception) {}
                try { recorder.release() } catch (_: Exception) {}
            }
        } finally {
            mediaRecorder = null
            _isRecording.value = false
            _recordingDuration.value = 0
            currentOutputFile = null
        }

        val result = if (file != null && file.exists()) {
            if (file.length() <= 128L) {
                try { file.delete() } catch (_: Exception) {}
                RecordingResult(null, 0L)
            } else {
                RecordingResult(file, finalDuration.coerceAtLeast(1L))
            }
        } else {
            RecordingResult(null, 0L)
        }

        cachedLastResult = result
        return result
    }

    fun isRootStartupInProgress(): Boolean = rootStarting

    private fun showToastLater(context: Context, stringRes: Int) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                Toast.makeText(appContext, appContext.getString(stringRes), Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
        }
    }

    // =====================================================================
    // Preferences
    // =====================================================================

    fun getSelectedCompressionProfile(context: Context): RecordingCompressionProfile {
        // FIXED: Check dialer_prefs first to eliminate settings drift, with secure_dialer_prefs fallback
        val prefs = context.getSharedPreferences("dialer_prefs", Context.MODE_PRIVATE)
        val key = prefs.getString("recording_compression_profile", null)
            ?: context.getSharedPreferences("secure_dialer_prefs", Context.MODE_PRIVATE)
                .getString("recording_compression_profile", RecordingCompressionProfile.BALANCED.key)
        return RecordingCompressionProfile.fromKey(key)
    }

    fun setCompressionProfile(context: Context, profile: RecordingCompressionProfile) {
        context.getSharedPreferences("dialer_prefs", Context.MODE_PRIVATE)
            .edit().putString("recording_compression_profile", profile.key).apply()
        context.getSharedPreferences("secure_dialer_prefs", Context.MODE_PRIVATE)
            .edit().putString("recording_compression_profile", profile.key).apply()
    }

    // =====================================================================
    // File discovery / recovery / export
    // =====================================================================

    fun cleanupCorruptOrEmptyFiles(context: Context): Int {
        cleanupStaleRootArtifacts(context)
        var cleaned = 0
        for (file in getRecordedFiles(context)) {
            if (file.exists() && file.length() <= 256L) {
                try { if (file.delete()) cleaned++ } catch (_: Exception) {}
            }
        }
        return cleaned
    }

    /**
     * Removes abandoned root-recorder scratch files. A live PCM file is continuously
     * modified, so only artifacts older than six hours (longer than the helper's
     * four-hour safety limit) are eligible.
     */
    private fun cleanupStaleRootArtifacts(context: Context) {
        val cutoff = System.currentTimeMillis() - 6L * 60L * 60L * 1000L
        try {
            File(context.filesDir, "CallRecordings").listFiles()?.forEach { file ->
                if (file.isFile &&
                    file.extension.equals("pcm", ignoreCase = true) &&
                    file.lastModified() < cutoff
                ) {
                    try { file.delete() } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
        try {
            File(context.cacheDir, "RootRecordings").listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < cutoff) {
                    try { file.delete() } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    fun getRecordedFiles(context: Context): List<File> {
        val dirs = listOfNotNull(
            File(context.filesDir, "CallRecordings"),
            File(context.getExternalFilesDir(null), "CallRecordings"),
            File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "CallRecordings"),
            File(context.cacheDir, "CallRecordings")
        )
        return dirs.filter { it.exists() }
            .flatMap { dir ->
                dir.listFiles()?.filter { f ->
                    f.isFile && (
                        f.extension.equals("m4a", true) ||
                        f.extension.equals("mp4", true) ||
                        f.extension.equals("wav", true)
                    )
                }?.toList() ?: emptyList()
            }
            .distinctBy { it.name }
            .sortedByDescending { it.lastModified() }
    }

    fun isWaveFile(file: File): Boolean = file.extension.equals("wav", true)

    fun mimeTypeFor(file: File): String = when (file.extension.lowercase(Locale.ROOT)) {
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "ogg", "opus" -> "audio/ogg"
        "flac" -> "audio/flac"
        "aac" -> "audio/aac"
        else -> "audio/mp4"
    }

    fun recoverRecordingsFromDisk(context: Context): List<CallRecording> {
        cleanupStaleRootArtifacts(context)
        val files = getRecordedFiles(context)
        val result = mutableListOf<CallRecording>()
        val displayFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
        val nameParseFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

        for (file in files) {
            if (!file.exists() || file.length() <= 128L) continue

            val name = file.nameWithoutExtension
            var extractedNumber = "Unknown"
            var timestampStr = displayFormat.format(Date(file.lastModified()))

            if (name.startsWith("REC_")) {
                val parts = name.removePrefix("REC_").split("_")
                if (parts.size >= 3) {
                    extractedNumber = parts[0]
                    try {
                        nameParseFormat.parse(parts[1] + "_" + parts[2])?.let {
                            timestampStr = displayFormat.format(it)
                        }
                    } catch (_: Exception) {}
                } else if (parts.size == 2) {
                    try {
                        nameParseFormat.parse(parts[0] + "_" + parts[1])?.let {
                            timestampStr = displayFormat.format(it)
                        }
                    } catch (_: Exception) {
                        extractedNumber = parts[0]
                    }
                }
            } else {
                val digits = name.filter { it.isDigit() }
                if (digits.length >= 7) extractedNumber = digits
            }

            var durationSeconds = 1L
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                if (!durationStr.isNullOrEmpty()) {
                    durationSeconds = ((durationStr.toLongOrNull() ?: 1000L) / 1000L).coerceAtLeast(1L)
                }
            } catch (_: Exception) {
            } finally {
                // FIXED: Explicitly releases native C++ media decoder client to prevent file descriptor leaks
                try { retriever.release() } catch (_: Exception) {}
            }

            result.add(
                CallRecording(
                    number = extractedNumber,
                    name = if (extractedNumber == "Unknown") {
                        context.getString(R.string.call_recording_default_name)
                    } else {
                        extractedNumber
                    },
                    timestamp = timestampStr,
                    duration = durationSeconds,
                    filePath = file.absolutePath
                )
            )
        }
        return result
    }

    fun exportRecordingToPublicDownloads(context: Context, sourceFile: File): Boolean {
        if (!sourceFile.exists()) return false
        try {
            val fileName = sourceFile.name
            val mimeType = mimeTypeFor(sourceFile)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/SecureDialer")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues) ?: return false
                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(sourceFile).use { input -> input.copyTo(out) }
                }
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
                return true
            } else {
                @Suppress("DEPRECATION")
                val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SecureDialer").apply {
                    if (!exists()) mkdirs()
                }
                val destFile = File(publicDir, fileName)
                FileInputStream(sourceFile).use { input ->
                    FileOutputStream(destFile).use { output -> input.copyTo(output) }
                }
                MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), arrayOf(mimeType), null)
                return true
            }
        } catch (_: Exception) {
            return false
        }
    }

    fun exportAllRecordingsToDownloads(context: Context): Int {
        var count = 0
        for (f in getRecordedFiles(context)) {
            if (f.exists() && f.length() > 128L) {
                if (exportRecordingToPublicDownloads(context, f)) count++
            }
        }
        return count
    }
}
