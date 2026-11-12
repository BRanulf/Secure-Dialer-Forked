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
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Locates the dex file that contains [RootAudioServer] and extracts it for
 * `app_process`.
 *
 * A plain `PathClassLoader` created by `app_process` is not guaranteed to scan every
 * `classesN.dex` in a large multidex APK. Packaging the helper into a tiny standalone
 * dex avoids that ambiguity entirely: we only need the dex that actually contains the
 * helper class (plus any classes that dex happens to share with the app).
 */
object RootHelperDex {

    private const val PREFS = "secure_dialer_prefs"
    private const val KEY_APK_SIZE = "root_helper_dex_apk_size"
    private const val KEY_APK_TIME = "root_helper_dex_apk_time"
    private const val KEY_PATHS = "root_helper_dex_paths"

    /**
     * Every standalone helper launched through app_process must be found. The
     * dex distribution differs between debug (one class per dex) and release
     * (R8 merges classes), so all matching dex files are extracted.
     */
    private val CLASS_MARKERS = arrayOf("RootAudioServer", "RootIncallMusicPlayer")

    @Synchronized
    fun prepare(context: Context): String? {
        val apk = File(context.applicationInfo.sourceDir)
        if (!apk.isFile) return null

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cachedPaths = prefs.getString(KEY_PATHS, null)
        if (!cachedPaths.isNullOrBlank() &&
            prefs.getLong(KEY_APK_SIZE, -1L) == apk.length() &&
            prefs.getLong(KEY_APK_TIME, -1L) == apk.lastModified()
        ) {
            val cachedFiles = cachedPaths.split(':').map { File(it) }
            if (cachedFiles.isNotEmpty() && cachedFiles.all { it.isFile }) {
                return cachedPaths
            }
        }

        val outputDir = File(context.cacheDir, "RootHelperDex").apply { mkdirs() }
        try {
            outputDir.listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {}

        val extracted = mutableListOf<File>()
        try {
            ZipFile(apk).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!isDexEntry(entry)) continue
                    if (!CLASS_MARKERS.any { marker -> entryContains(zip, entry, marker) }) continue

                    val target = File(outputDir, entry.name)
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output ->
                            input.copyTo(output, bufferSize = 64 * 1024)
                        }
                    }
                    try {
                        target.setReadable(true, false)
                    } catch (_: Exception) {}
                    extracted.add(target)
                }
            }
        } catch (_: Exception) {
            return null
        }

        if (extracted.isEmpty()) return null
        val classpath = extracted.joinToString(":") { it.absolutePath }
        prefs.edit()
            .putString(KEY_PATHS, classpath)
            .putLong(KEY_APK_SIZE, apk.length())
            .putLong(KEY_APK_TIME, apk.lastModified())
            .apply()
        return classpath
    }

    private fun isDexEntry(entry: ZipEntry): Boolean {
        return entry.name.matches(Regex("classes\\d*\\.dex"))
    }

    /**
     * Streams a dex entry looking for the helper class name. Dex files keep type names
     * in a string table, so a byte search is sufficient and avoids loading multi-megabyte
     * dex files into memory.
     */
    private fun entryContains(zip: ZipFile, entry: ZipEntry, marker: String): Boolean {
        val needle = marker.toByteArray(Charsets.UTF_8)
        if (needle.isEmpty()) return false
        val overlap = needle.size - 1
        var carry = ByteArray(0)

        try {
            zip.getInputStream(entry).buffered(64 * 1024).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) return false
                    val data = ByteArray(carry.size + read)
                    carry.copyInto(data, 0, 0, carry.size)
                    buffer.copyInto(data, carry.size, 0, read)
                    if (indexOf(data, needle) >= 0) return true
                    carry = if (data.size >= overlap) {
                        data.copyOfRange(data.size - overlap, data.size)
                    } else {
                        data
                    }
                }
            }
        } catch (_: Exception) {
            return false
        }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (index in 0..haystack.size - needle.size) {
            for (offset in needle.indices) {
                if (haystack[index + offset] != needle[offset]) continue@outer
            }
            return index
        }
        return -1
    }
}
