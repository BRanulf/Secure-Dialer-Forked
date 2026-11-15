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
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.example.R
import java.io.File
import java.util.Locale

/**
 * Built-in and user-imported soundboard effects.
 *
 * Built-ins are generated from audio files placed in the `app/` directory at build
 * time (see `GenerateSoundboardEffectsTask`). Imported effects live in
 * `filesDir/Soundboard` and are decoded on demand by [AnsweringMachinePcm].
 */
object SoundboardEffects {

    private const val DIR_NAME = "Soundboard"
    private const val CACHE_DIR = "SoundboardAssets"
    private const val MAX_IMPORT_BYTES = 32L * 1024L * 1024L

    private val supportedExtensions = setOf(
        "wav", "mp3", "m4a", "aac", "ogg", "flac", "opus"
    )

    data class SoundboardEffect(
        val id: String,
        val name: String,
        val builtIn: Boolean,
        val file: File? = null,
        val resId: Int = 0,
        val extension: String = ""
    )

    sealed class ImportResult {
        data class Success(val effect: SoundboardEffect) : ImportResult()
        data class Failure(val reason: ImportFailure) : ImportResult()
    }

    enum class ImportFailure {
        UNSUPPORTED,
        TOO_LARGE,
        IO
    }

    fun all(context: Context): List<SoundboardEffect> {
        val builtIns = builtInEffects(context)
        val imported = importedEffects(context)
        return builtIns + imported
    }

    fun builtInEffects(context: Context): List<SoundboardEffect> {
        val names = try {
            context.resources.getStringArray(R.array.soundboard_builtin_names)
        } catch (_: Exception) {
            emptyArray()
        }
        val extensions = try {
            context.resources.getStringArray(R.array.soundboard_builtin_extensions)
        } catch (_: Exception) {
            emptyArray()
        }
        val result = mutableListOf<SoundboardEffect>()
        val array = try {
            context.resources.obtainTypedArray(R.array.soundboard_builtin_resources)
        } catch (_: Exception) {
            null
        } ?: return result
        try {
            for (index in 0 until array.length()) {
                val resId = array.getResourceId(index, 0)
                if (resId == 0) continue
                result.add(
                    SoundboardEffect(
                        id = "builtin:$resId",
                        name = names.getOrNull(index)?.takeIf { it.isNotBlank() }
                            ?: "Effect ${index + 1}",
                        builtIn = true,
                        resId = resId,
                        extension = extensions.getOrNull(index).orEmpty()
                    )
                )
            }
        } catch (_: Exception) {
        } finally {
            try { array.recycle() } catch (_: Exception) {}
        }
        return result
    }

    fun importedEffects(context: Context): List<SoundboardEffect> {
        val dir = soundboardDir(context)
        val files = try {
            dir.listFiles()
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        return files.asSequence()
            .filter { it.isFile && it.length() > 0L }
            .filter { it.extension.lowercase(Locale.ROOT) in supportedExtensions }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
            .map { file ->
                SoundboardEffect(
                    id = "import:${file.name}",
                    name = file.nameWithoutExtension,
                    builtIn = false,
                    file = file,
                    extension = file.extension.lowercase(Locale.ROOT)
                )
            }
            .toList()
    }

    /**
     * Returns a regular file path for a built-in effect. `app_process` and the PCM
     * decoder need real files; APK raw resources only expose streams.
     */
    @Synchronized
    fun playableFile(context: Context, effect: SoundboardEffect): File? {
        if (!effect.builtIn) {
            return effect.file?.takeIf { it.isFile && it.length() > 0L }
        }

        val cacheDir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
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
        val extension = effect.extension.ifBlank { "audio" }
        val versionSuffix = "_${versionCode}_${apkTime}."
        val prefix = "${effect.resId}_${versionCode}_${apkTime}"
        cacheDir.listFiles()?.forEach { cached ->
            if (cached.isFile && !cached.name.contains(versionSuffix)) {
                try { cached.delete() } catch (_: Exception) {}
            }
        }
        val target = File(cacheDir, "$prefix.$extension")
        if (target.isFile && target.length() > 0L) {
            return target
        }

        return try {
            context.resources.openRawResource(effect.resId).use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }
            target.takeIf { it.isFile && it.length() > 0L }
        } catch (_: Exception) {
            try { target.delete() } catch (_: Exception) {}
            null
        }
    }

    /**
     * Copies a SAF-picked effect into app-private storage. Blocking; call on IO.
     */
    fun importFromUri(context: Context, uri: Uri): ImportResult {
        val resolver = context.contentResolver
        val displayName = queryDisplayName(context, uri) ?: "sound"
        val extension = inferExtension(context, uri, displayName)
        if (extension !in supportedExtensions) {
            return ImportResult.Failure(ImportFailure.UNSUPPORTED)
        }

        val declaredLength = try {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        } catch (_: Exception) {
            -1L
        }
        if (declaredLength > MAX_IMPORT_BYTES) {
            return ImportResult.Failure(ImportFailure.TOO_LARGE)
        }

        val dir = soundboardDir(context)
        val base = uniqueBaseName(
            dir,
            sanitizeBase(displayName.substringBeforeLast('.', displayName)),
            extension
        )
        val target = File(dir, "$base.$extension")

        var copied = 0L
        var tooLarge = false
        try {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        copied += read
                        if (copied > MAX_IMPORT_BYTES) {
                            tooLarge = true
                            break
                        }
                        output.write(buffer, 0, read)
                    }
                }
            } ?: return ImportResult.Failure(ImportFailure.IO)
        } catch (_: Exception) {
            try { target.delete() } catch (_: Exception) {}
            return ImportResult.Failure(ImportFailure.IO)
        }

        if (tooLarge) {
            try { target.delete() } catch (_: Exception) {}
            return ImportResult.Failure(ImportFailure.TOO_LARGE)
        }
        if (!target.isFile || target.length() <= 0L) {
            try { target.delete() } catch (_: Exception) {}
            return ImportResult.Failure(ImportFailure.IO)
        }

        return ImportResult.Success(
            SoundboardEffect(
                id = "import:${target.name}",
                name = target.nameWithoutExtension,
                builtIn = false,
                file = target,
                extension = extension
            )
        )
    }

    fun deleteImported(context: Context, effect: SoundboardEffect): Boolean {
        if (effect.builtIn) return false
        val dir = soundboardDir(context)
        val file = effect.file ?: return false
        return try {
            file.parentFile?.canonicalPath == dir.canonicalPath && file.delete()
        } catch (_: Exception) {
            false
        }
    }

    private fun soundboardDir(context: Context): File {
        return File(context.filesDir, DIR_NAME).apply { mkdirs() }
    }

    private fun sanitizeBase(value: String): String {
        val cleaned = value
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim()
            .trim('.')
            .take(64)
            .ifBlank { "sound" }
        return cleaned
    }

    private fun uniqueBaseName(dir: File, base: String, extension: String): String {
        if (!File(dir, "$base.$extension").exists()) return base
        var index = 2
        while (File(dir, "$base $index.$extension").exists()) {
            index++
        }
        return "$base $index"
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun inferExtension(context: Context, uri: Uri, displayName: String): String {
        val fromName = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (fromName in supportedExtensions) return fromName
        val mime = context.contentResolver.getType(uri)?.lowercase(Locale.ROOT)
        val direct = when (mime) {
            "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
            "audio/mpeg" -> "mp3"
            "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
            "audio/aac", "audio/aacp" -> "aac"
            "audio/ogg", "audio/opus", "audio/x-opus+ogg" -> "ogg"
            "audio/flac", "audio/x-flac" -> "flac"
            else -> ""
        }
        if (direct.isNotEmpty()) return direct
        val fromMime = mime?.let {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(it)?.lowercase(Locale.ROOT)
        }
        return fromMime?.takeIf { it in supportedExtensions }.orEmpty()
    }
}
