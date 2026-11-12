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
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import com.example.R
import java.io.File
import java.util.Locale

/**
 * Resolves the answering-machine greeting.
 *
 * Priority:
 * 1. Audio chosen by the user in Settings (copied into app-private storage).
 * 2. A custom greeting file dropped in the project root at build time and packaged by
 *    the `generateAnsweringMachineGreeting` Gradle task.
 * 3. Built-in localized default greeting (English or Simplified Chinese).
 */
object AnsweringMachineGreeting {

    private const val PREFS = "dialer_prefs"
    private const val KEY_PATH = "answering_machine_greeting_path"
    private const val KEY_NAME = "answering_machine_greeting_display_name"
    private const val DIR_NAME = "AnsweringMachine"

    sealed class Source {
        data class LocalFile(val file: File) : Source()
        data class RawResource(val resId: Int) : Source()
    }

    fun selectedFile(context: Context): File? {
        val path = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PATH, null) ?: return null
        val file = File(path)
        return file.takeIf { it.isFile && it.length() > 0L }
    }

    fun selectedDisplayName(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_NAME, null)
        if (!name.isNullOrBlank()) return name
        return selectedFile(context)?.name
    }

    fun resolve(context: Context): Source {
        selectedFile(context)?.let { return Source.LocalFile(it) }

        // The build task always packages a marker telling us whether the greeting
        // resource came from a user file in the project root or from our fallback.
        if (hasCustomBundledGreeting(context)) {
            return Source.RawResource(R.raw.answering_machine_greeting)
        }

        val language = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            context.resources.configuration.locales[0].language
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale.language
        }
        return if (language.equals("zh", ignoreCase = true)) {
            Source.RawResource(R.raw.answering_machine_greeting_default_zh)
        } else {
            Source.RawResource(R.raw.answering_machine_greeting)
        }
    }

    private fun hasCustomBundledGreeting(context: Context): Boolean {
        return try {
            context.resources.openRawResource(R.raw.answering_machine_greeting_custom)
                .bufferedReader()
                .use { it.readText().trim() == "1" }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Copies a SAF-picked audio file into app-private storage so it survives reboots and
     * URI-permission expiry. Call from an IO dispatcher.
     */
    fun importFromUri(context: Context, uri: Uri): File? {
        return try {
            val resolver = context.contentResolver
            val displayName = queryDisplayName(context, uri) ?: "greeting"
            val extension = inferExtension(context, uri, displayName)
            val safeBase = displayName.substringBeforeLast('.', displayName)
                .replace(Regex("[^A-Za-z0-9_-]"), "_")
                .take(48)
                .ifBlank { "greeting" }

            val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "$safeBase.$extension")

            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            } ?: return null

            if (!target.exists() || target.length() <= 0L) {
                target.delete()
                return null
            }

            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PATH, target.absolutePath)
                .putString(KEY_NAME, displayName)
                .apply()
            target
        } catch (_: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PATH)
            .remove(KEY_NAME)
            .apply()
        try {
            File(context.filesDir, DIR_NAME).listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {}
    }

    /**
     * Returns a real file path for the greeting. The root uplink helper cannot read
     * an APK raw-resource descriptor, so bundled greetings are copied to the cache
     * with a detected audio extension. User-selected files are already on disk.
     */
    fun playableFile(context: Context): File? {
        selectedFile(context)?.let { return it }

        val source = resolve(context) as? Source.RawResource ?: return null
        return try {
            val dir = File(context.cacheDir, "AnsweringMachinePlay").apply { mkdirs() }
            val versionCode = try {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
            } catch (_: Exception) {
                0L
            }
            val apkTime = try {
                File(context.applicationInfo.sourceDir).lastModified()
            } catch (_: Exception) {
                0L
            }
            val prefix = "bundled_${source.resId}_${versionCode}_${apkTime}"

            dir.listFiles()?.firstOrNull { file ->
                file.isFile && file.length() > 0L && file.name.startsWith("$prefix.")
            }?.let { return it }

            val input = context.resources.openRawResource(source.resId)
            input.use { stream ->
                val header = ByteArray(16)
                val read = stream.read(header)
                val extension = detectAudioExtension(header, read)
                val output = File(dir, "$prefix.$extension")
                output.outputStream().use { out ->
                    if (read > 0) out.write(header, 0, read)
                    stream.copyTo(out, bufferSize = 64 * 1024)
                }
                output.takeIf { it.isFile && it.length() > 0L }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun detectAudioExtension(header: ByteArray, length: Int): String {
        if (length >= 4) {
            when (String(header, 0, 4, Charsets.US_ASCII)) {
                "RIFF" -> return "wav"
                "OggS" -> return "ogg"
                "fLaC" -> return "flac"
            }
        }
        if (length >= 3 &&
            header[0] == 'I'.code.toByte() &&
            header[1] == 'D'.code.toByte() &&
            header[2] == '3'.code.toByte()
        ) {
            return "mp3"
        }
        if (length >= 8 && String(header, 4, 4, Charsets.US_ASCII) == "ftyp") {
            return "m4a"
        }
        if (length >= 2) {
            val b0 = header[0].toInt() and 0xFF
            val b1 = header[1].toInt() and 0xFF
            if (b0 == 0xFF && (b1 == 0xFB || b1 == 0xF3 || b1 == 0xF2)) return "mp3"
            if (b0 == 0xFF && (b1 == 0xF1 || b1 == 0xF9)) return "aac"
        }
        return "audio"
    }

    /**
     * Configures [player] for a greeting source. Must be called before prepare().
     */
    fun setDataSource(context: Context, player: MediaPlayer, source: Source) {
        when (source) {
            is Source.LocalFile -> player.setDataSource(source.file.absolutePath)
            is Source.RawResource -> {
                val descriptor = context.resources.openRawResourceFd(source.resId)
                if (descriptor != null) {
                    descriptor.use {
                        player.setDataSource(
                            it.fileDescriptor,
                            it.startOffset,
                            it.length
                        )
                    }
                } else {
                    // Compressed/non-file-backed raw resources are still playable
                    // through the android.resource URI scheme.
                    player.setDataSource(
                        context,
                        Uri.parse("android.resource://${context.packageName}/${source.resId}")
                    )
                }
            }
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) cursor.getString(index) else null
                    } else null
                }
        } catch (_: Exception) {
            null
        }
    }

    private fun inferExtension(context: Context, uri: Uri, displayName: String): String {
        val fromName = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (fromName in setOf("wav", "mp3", "m4a", "aac", "ogg", "flac", "opus")) {
            return fromName
        }
        return when (context.contentResolver.getType(uri)?.lowercase(Locale.ROOT)) {
            "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
            "audio/mpeg" -> "mp3"
            "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
            "audio/aac" -> "aac"
            "audio/ogg", "audio/opus" -> "ogg"
            "audio/flac" -> "flac"
            else -> "audio"
        }
    }
}
