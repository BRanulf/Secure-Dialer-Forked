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
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Decodes the answering-machine greeting to the format consumed by
 * [RootIncallMusicPlayer]: raw little-endian 16-bit mono PCM at 16 kHz.
 *
 * The root helper cannot use MediaPlayer (it would need an application Context),
 * so decoding happens in the app process, where MediaExtractor/MediaCodec are
 * available. PCM WAV files are decoded directly; everything else uses the platform
 * decoders. Results are cached until the source file changes.
 */
object AnsweringMachinePcm {

    const val SAMPLE_RATE = 16_000

    private const val MAX_DECODE_SECONDS = 180
    private const val CACHE_VERSION = "v2"

    data class PcmFile(val file: File)

    fun ensure(context: Context): PcmFile? {
        val source = AnsweringMachineGreeting.playableFile(context) ?: return null
        if (!source.isFile || source.length() <= 0L) return null

        val cacheDir = File(context.cacheDir, "AnsweringMachinePcm").apply { mkdirs() }
        cleanupOldCache(cacheDir)

        val key = "$CACHE_VERSION:${source.absolutePath}:${source.length()}:${source.lastModified()}"
        val output = File(cacheDir, "greeting_${key.hashCode().toUInt().toString(16)}.pcm")
        if (output.isFile && output.length() >= 2L) {
            return PcmFile(output)
        }

        val decoded = try {
            if (isRiffWave(source)) {
                decodeWave(source, output) || decodeWithMediaCodec(source, output)
            } else {
                decodeWithMediaCodec(source, output)
            }
        } catch (_: Exception) {
            false
        }

        if (decoded && output.isFile && output.length() >= 2L) {
            return PcmFile(output)
        }
        try { output.delete() } catch (_: Exception) {}
        return null
    }

    private fun cleanupOldCache(cacheDir: File) {
        try {
            val cutoff = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L
            cacheDir.listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < cutoff) file.delete()
            }
        } catch (_: Exception) {}
    }

    private fun isRiffWave(file: File): Boolean {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 12) return false
                val header = ByteArray(12)
                raf.readFully(header)
                String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                        String(header, 8, 4, Charsets.US_ASCII) == "WAVE"
            }
        } catch (_: Exception) {
            false
        }
    }

    // =====================================================================
    // WAV (PCM) direct decode
    // =====================================================================

    private fun decodeWave(source: File, output: File): Boolean {
        RandomAccessFile(source, "r").use { raf ->
            if (raf.length() < 12) return false
            val riff = ByteArray(12)
            raf.readFully(riff)
            if (String(riff, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                String(riff, 8, 4, Charsets.US_ASCII) != "WAVE"
            ) {
                return false
            }

            var channels = 0
            var sampleRate = 0
            var bitsPerSample = 0
            var audioFormat = 0
            var dataOffset = -1L
            var dataSize = 0L

            while (raf.filePointer + 8 <= raf.length()) {
                val id = readFourCc(raf) ?: break
                val size = readLe32(raf).coerceAtLeast(0L)
                val chunkStart = raf.filePointer
                when (id) {
                    "fmt " -> {
                        if (size < 16 || chunkStart + size > raf.length()) return false
                        audioFormat = readLe16(raf)
                        channels = readLe16(raf)
                        sampleRate = readLe32(raf).toInt()
                        raf.skipBytes(6) // byte rate + block align
                        bitsPerSample = readLe16(raf)
                    }
                    "data" -> {
                        dataOffset = chunkStart
                        dataSize = minOf(size, raf.length() - chunkStart)
                    }
                }
                if (dataOffset >= 0 && channels > 0 && sampleRate > 0) {
                    break
                }
                val skipTo = chunkStart + size + (size and 1L)
                if (skipTo > raf.length()) break
                raf.seek(skipTo)
            }

            if (audioFormat != 1 || channels <= 0 || channels > 8 ||
                sampleRate <= 0 || dataOffset < 0 || dataSize < 2
            ) {
                return false
            }
            if (bitsPerSample != 16 && bitsPerSample != 8) {
                // 24/32-bit/float WAV: let MediaCodec handle it.
                return false
            }

            val bytesPerSample = bitsPerSample / 8
            val frameBytes = channels * bytesPerSample
            if (frameBytes <= 0) return false
            val maxFrames = MAX_DECODE_SECONDS.toLong() * sampleRate
            var framesRemaining = minOf(dataSize / frameBytes, maxFrames)
            if (framesRemaining <= 0) return false

            raf.seek(dataOffset)
            val builder = FloatSamples(
                initialCapacity = minOf(
                    (framesRemaining * 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    (sampleRate.toLong() * MAX_DECODE_SECONDS / 4).toInt().coerceAtLeast(16_384)
                )
            )
            val buffer = ByteArray(64 * 1024)
            var carry = ByteArray(0)

            while (framesRemaining > 0) {
                val maxRead = minOf(buffer.size.toLong(), framesRemaining * frameBytes)
                    .toInt()
                    .coerceAtLeast(frameBytes)
                val read = raf.read(buffer, 0, maxRead)
                if (read <= 0) break

                val data = if (carry.isEmpty()) {
                    buffer.copyOf(read)
                } else {
                    ByteArray(carry.size + read).also {
                        carry.copyInto(it, 0)
                        buffer.copyInto(it, carry.size, 0, read)
                    }
                }
                val completeFrames = minOf(
                    data.size / frameBytes,
                    framesRemaining.toInt()
                )
                var index = 0
                for (frame in 0 until completeFrames) {
                    var sum = 0f
                    for (channel in 0 until channels) {
                        val sample = if (bitsPerSample == 16) {
                            val lo = data[index].toInt() and 0xFF
                            val hi = data[index + 1].toInt()
                            ((hi shl 8) or lo).toShort() / 32768f
                        } else {
                            ((data[index].toInt() and 0xFF) - 128) / 128f
                        }
                        sum += sample
                        index += bytesPerSample
                    }
                    builder.add(sum / channels)
                }
                framesRemaining -= completeFrames

                val consumed = completeFrames * frameBytes
                carry = if (consumed < data.size) {
                    data.copyOfRange(consumed, data.size)
                } else {
                    ByteArray(0)
                }
            }

            if (builder.size < 2) return false
            writeResampled(builder.data, builder.size, sampleRate, output)
            return true
        }
    }

    // =====================================================================
    // MediaExtractor + MediaCodec decode (MP3, M4A, OGG, FLAC, ...)
    // =====================================================================

    private fun decodeWithMediaCodec(source: File, output: File): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(source.absolutePath)
            var trackIndex = -1
            var inputFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = index
                    inputFormat = format
                    break
                }
            }
            if (trackIndex < 0 || inputFormat == null) return false
            extractor.selectTrack(trackIndex)

            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return false
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            var outputFormat: MediaFormat? = null
            val builder = FloatSamples(initialCapacity = 64 * 1024)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val deadline = System.currentTimeMillis() + 45_000L

            while (!outputDone && System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                        if (inputBuffer == null) {
                            inputDone = true
                        } else {
                            inputBuffer.clear()
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(
                                    inputIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(
                                    inputIndex, 0, sampleSize,
                                    extractor.sampleTime.coerceAtLeast(0L), 0
                                )
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputIndex = decoder.dequeueOutputBuffer(info, 10_000L)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outputFormat = decoder.outputFormat
                    }
                    outputIndex >= 0 -> {
                        if (info.size > 0) {
                            val format = outputFormat ?: decoder.outputFormat
                            val rate = getInt(format, MediaFormat.KEY_SAMPLE_RATE, 16_000)
                            val channels = getInt(format, MediaFormat.KEY_CHANNEL_COUNT, 1)
                                .coerceIn(1, 8)
                            val pcmEncoding = getInt(
                                format,
                                MediaFormat.KEY_PCM_ENCODING,
                                AudioFormat.ENCODING_PCM_16BIT
                            )
                            val outputBuffer = decoder.getOutputBuffer(outputIndex)
                            if (outputBuffer != null) {
                                outputBuffer.position(info.offset)
                                outputBuffer.limit(info.offset + info.size)
                                appendSamples(
                                    buffer = outputBuffer,
                                    encoding = pcmEncoding,
                                    channels = channels,
                                    rate = rate,
                                    builder = builder
                                )
                            }
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            if (builder.size < 2) return false
            val finalRate = outputFormat?.let {
                getInt(it, MediaFormat.KEY_SAMPLE_RATE, 16_000)
            } ?: getInt(inputFormat, MediaFormat.KEY_SAMPLE_RATE, 16_000)
            writeResampled(builder.data, builder.size, finalRate, output)
            return true
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun appendSamples(
        buffer: java.nio.ByteBuffer,
        encoding: Int,
        channels: Int,
        rate: Int,
        builder: FloatSamples
    ) {
        val maxSamples = MAX_DECODE_SECONDS.toLong() * rate * channels
        val available = buffer.remaining()
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floats = buffer.order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
                val frames = minOf(floats.remaining() / channels, maxSamples.toInt())
                for (frame in 0 until frames) {
                    var sum = 0f
                    for (channel in 0 until channels) sum += floats.get()
                    builder.add((sum / channels).coerceIn(-1f, 1f))
                }
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                val frames = minOf(available / channels, maxSamples.toInt())
                for (frame in 0 until frames) {
                    var sum = 0f
                    for (channel in 0 until channels) {
                        sum += ((buffer.get().toInt() and 0xFF) - 128) / 128f
                    }
                    builder.add(sum / channels)
                }
            }
            else -> {
                val shorts = buffer.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                val frames = minOf(shorts.remaining() / channels, maxSamples.toInt())
                for (frame in 0 until frames) {
                    var sum = 0f
                    for (channel in 0 until channels) {
                        sum += shorts.get() / 32768f
                    }
                    builder.add(sum / channels)
                }
            }
        }
    }

    private fun getInt(format: MediaFormat, key: String, fallback: Int): Int {
        return try {
            if (format.containsKey(key)) format.getInteger(key) else fallback
        } catch (_: Exception) {
            fallback
        }
    }

    // =====================================================================
    // Resampling / PCM writing
    // =====================================================================

    private fun writeResampled(
        samples: FloatArray,
        count: Int,
        sourceRate: Int,
        output: File
    ) {
        val out = BufferedOutputStream(FileOutputStream(output), 64 * 1024)
        try {
            val buffer = ByteArray(16 * 1024)
            var bufferIndex = 0

            fun flushBuffer() {
                if (bufferIndex > 0) {
                    out.write(buffer, 0, bufferIndex)
                    bufferIndex = 0
                }
            }

            fun putSample(value: Float) {
                val clamped = value.coerceIn(-1f, 1f)
                val sample = (clamped * 32767f).toInt()
                if (bufferIndex + 2 > buffer.size) flushBuffer()
                buffer[bufferIndex++] = (sample and 0xFF).toByte()
                buffer[bufferIndex++] = ((sample ushr 8) and 0xFF).toByte()
            }

            if (sourceRate == SAMPLE_RATE) {
                for (index in 0 until count) putSample(samples[index])
            } else {
                val step = sourceRate.toDouble() / SAMPLE_RATE.toDouble()
                var position = 0.0
                while (position < count - 1) {
                    val index = position.toInt()
                    val fraction = (position - index).toFloat()
                    val value = samples[index] * (1f - fraction) +
                            samples[index + 1] * fraction
                    putSample(value)
                    position += step
                }
            }
            flushBuffer()
        } finally {
            try { out.close() } catch (_: Exception) {}
        }
    }

    private class FloatSamples(initialCapacity: Int) {
        var data = FloatArray(initialCapacity.coerceAtLeast(1024))
            private set
        var size = 0
            private set

        fun add(value: Float) {
            if (size >= data.size) {
                val newSize = (data.size * 2).coerceAtMost(MAX_DECODE_SECONDS * 192_000)
                if (newSize <= data.size) return
                data = data.copyOf(newSize)
            }
            data[size++] = value
        }
    }

    private fun readFourCc(raf: RandomAccessFile): String? {
        val bytes = ByteArray(4)
        return try {
            raf.readFully(bytes)
            String(bytes, Charsets.US_ASCII)
        } catch (_: Exception) {
            null
        }
    }

    private fun readLe16(raf: RandomAccessFile): Int {
        val lo = raf.read()
        val hi = raf.read()
        if (lo < 0 || hi < 0) return 0
        return (lo and 0xFF) or ((hi and 0xFF) shl 8)
    }

    private fun readLe32(raf: RandomAccessFile): Long {
        val b0 = raf.read()
        val b1 = raf.read()
        val b2 = raf.read()
        val b3 = raf.read()
        if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) return 0L
        return ((b0 and 0xFF).toLong()) or
                ((b1 and 0xFF).toLong() shl 8) or
                ((b2 and 0xFF).toLong() shl 16) or
                ((b3 and 0xFF).toLong() shl 24)
    }
}
