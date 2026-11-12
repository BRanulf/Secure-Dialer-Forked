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

package com.example.util;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.os.Process;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Standalone PCM recorder used by the root recording engine.
 *
 * The same code can run in two places:
 * 1. Inside a root shell process started with `app_process` (true system-level UID 0 capture).
 * 2. As a fallback thread inside the app process after the CAPTURE_AUDIO_OUTPUT app-op has
 *    been granted through root.
 *
 * It deliberately uses only java.io / android.media so the process started through
 * `app_process` does not need the app's full runtime environment.
 *
 * Output is raw 16-bit little-endian PCM. The Kotlin side converts it to WAV when the
 * session stops (a streaming WAV header with an unknown size is avoided so a killed
 * helper can never leave an unplayable file behind).
 *
 * Arguments:
 *  0: pcm output path
 *  1: status file path
 *  2: stop marker path
 *  3: preferred AudioRecord source (4 = VOICE_CALL, 3 = VOICE_DOWNLINK)
 *  4: requested sample rate (e.g. 16000)
 *  5: requested channel count (1 or 2)
 *  6: maximum recording duration in seconds (safety net)
 */
public final class RootAudioServer {

    private static final int SOURCE_VOICE_UPLINK = 2;
    private static final int SOURCE_VOICE_DOWNLINK = 3;
    private static final int SOURCE_VOICE_CALL = 4;

    private RootAudioServer() {
    }

    public static void main(String[] args) {
        int result = run(args);
        System.exit(result);
    }

    /**
     * Runs a recording session. Returns 0 on success, 2 on capture failure.
     * This method returns when the stop marker appears, the max duration expires,
     * the thread is interrupted, or the JVM receives SIGTERM.
     */
    public static int run(String[] args) {
        if (args == null || args.length < 6) {
            return 2;
        }

        final File pcmFile = new File(args[0]);
        final File statusFile = new File(args[1]);
        final File stopFile = new File(args[2]);
        final int preferredSource = parseInt(args[3], SOURCE_VOICE_CALL);
        final int requestedRate = parseInt(args[4], 16000);
        final int requestedChannels = Math.max(1, Math.min(2, parseInt(args[5], 1)));
        final long maxSeconds = args.length > 6 ? parseLong(args[6], 14_400L) : 14_400L;

        writeStatus(statusFile, "state=starting");

        FileOutputStream output = null;
        AudioRecord record = null;
        long totalBytes = 0L;

        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);

            if (pcmFile.exists() && !pcmFile.delete()) {
                writeStatus(statusFile, "state=error\nerror=output file is not replaceable");
                return 2;
            }

            Set<Integer> sourceOrder = new LinkedHashSet<>();
            if (preferredSource == SOURCE_VOICE_CALL
                    || preferredSource == SOURCE_VOICE_DOWNLINK
                    || preferredSource == SOURCE_VOICE_UPLINK) {
                sourceOrder.add(preferredSource);
            }
            // Never fall back to MIC/UPLINK for the root engine: recording the local
            // room through the microphone is exactly what this engine must avoid.
            sourceOrder.add(SOURCE_VOICE_CALL);
            sourceOrder.add(SOURCE_VOICE_DOWNLINK);

            String lastError = "no call audio source could be opened";
            for (int source : sourceOrder) {
                for (int[] config : buildConfigs(requestedRate, requestedChannels)) {
                    int sampleRate = config[0];
                    int channels = config[1];
                    int channelMask = channels == 2
                            ? AudioFormat.CHANNEL_IN_STEREO
                            : AudioFormat.CHANNEL_IN_MONO;

                    int minBuffer = AudioRecord.getMinBufferSize(
                            sampleRate,
                            channelMask,
                            AudioFormat.ENCODING_PCM_16BIT
                    );
                    if (minBuffer <= 0) {
                        lastError = "unsupported audio configuration " + sampleRate + "Hz/" + channels + "ch";
                        continue;
                    }

                    int bufferSize = Math.max(
                            minBuffer * 2,
                            Math.max(4096, sampleRate * channels * 2 / 10)
                    );

                    AudioRecord candidate = null;
                    try {
                        candidate = new AudioRecord(
                                source,
                                sampleRate,
                                channelMask,
                                AudioFormat.ENCODING_PCM_16BIT,
                                bufferSize
                        );
                        if (candidate.getState() != AudioRecord.STATE_INITIALIZED) {
                            lastError = "AudioRecord init failed for source " + source;
                            candidate.release();
                            candidate = null;
                            continue;
                        }

                        candidate.startRecording();
                        if (candidate.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                            lastError = "AudioRecord start failed for source " + source;
                            candidate.release();
                            candidate = null;
                            continue;
                        }

                        record = candidate;
                        break;
                    } catch (Throwable t) {
                        lastError = t.getClass().getSimpleName()
                                + (t.getMessage() == null ? "" : ": " + t.getMessage());
                        if (candidate != null) {
                            try {
                                candidate.release();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
                if (record != null) {
                    break;
                }
            }

            if (record == null) {
                writeStatus(statusFile, "state=error\nerror=" + sanitize(lastError));
                return 2;
            }

            int sampleRate = record.getSampleRate();
            int channels = record.getChannelCount() > 0 ? record.getChannelCount() : requestedChannels;
            output = new FileOutputStream(pcmFile);
            makeWorldReadable(pcmFile);

            writeStatus(
                    statusFile,
                    "state=recording"
                            + "\nsource=" + record.getAudioSource()
                            + "\nrate=" + sampleRate
                            + "\nchannels=" + channels
                            + "\nbytes=0"
            );

            byte[] buffer = new byte[Math.max(4096, sampleRate * channels * 2 / 20)];
            long startedAt = System.currentTimeMillis();
            long maxBytes = maxSeconds * sampleRate * channels * 2L;

            while (!Thread.currentThread().isInterrupted()) {
                if (stopFile.exists()) {
                    break;
                }
                long elapsed = (System.currentTimeMillis() - startedAt) / 1000L;
                if (elapsed >= maxSeconds || totalBytes >= maxBytes) {
                    break;
                }

                int read;
                try {
                    read = record.read(buffer, 0, buffer.length);
                } catch (Throwable t) {
                    lastError = "read failed: " + t.getClass().getSimpleName();
                    break;
                }

                if (read > 0) {
                    output.write(buffer, 0, read);
                    totalBytes += read;
                } else if (read == AudioRecord.ERROR_INVALID_OPERATION
                        || read == AudioRecord.ERROR_DEAD_OBJECT
                        || read == AudioRecord.ERROR_BAD_VALUE) {
                    lastError = "AudioRecord read error " + read;
                    break;
                }
            }

            // Flush the PCM before publishing the stopped marker so the app never
            // converts a file that is still being written.
            try {
                output.flush();
            } catch (Throwable ignored) {
            }
            writeStatus(
                    statusFile,
                    "state=stopped"
                            + "\nsource=" + record.getAudioSource()
                            + "\nrate=" + sampleRate
                            + "\nchannels=" + channels
                            + "\nbytes=" + totalBytes
            );
            return 0;
        } catch (Throwable t) {
            writeStatus(
                    statusFile,
                    "state=error\nerror=" + sanitize(
                            t.getClass().getSimpleName()
                                    + (t.getMessage() == null ? "" : ": " + t.getMessage())
                    )
            );
            return 2;
        } finally {
            try {
                if (record != null) {
                    try {
                        record.stop();
                    } catch (Throwable ignored) {
                    }
                    record.release();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (output != null) {
                    output.flush();
                    output.close();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (pcmFile.exists()) {
                    makeWorldReadable(pcmFile);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static List<int[]> buildConfigs(int requestedRate, int requestedChannels) {
        Set<String> seen = new LinkedHashSet<>();
        List<int[]> configs = new ArrayList<>();
        addConfig(configs, seen, requestedRate, requestedChannels);
        addConfig(configs, seen, requestedRate, 1);
        addConfig(configs, seen, 16000, 1);
        addConfig(configs, seen, 8000, 1);
        addConfig(configs, seen, 44100, 2);
        addConfig(configs, seen, 48000, 2);
        return configs;
    }

    private static void addConfig(List<int[]> configs, Set<String> seen, int rate, int channels) {
        if (rate <= 0 || (channels != 1 && channels != 2)) {
            return;
        }
        String key = rate + "x" + channels;
        if (seen.add(key)) {
            configs.add(new int[]{rate, channels});
        }
    }

    private static void writeStatus(File statusFile, String content) {
        File parent = statusFile.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        File temp = new File(statusFile.getPath() + ".tmp");
        try {
            FileOutputStream stream = new FileOutputStream(temp);
            try {
                stream.write(content.getBytes("UTF-8"));
                stream.flush();
            } finally {
                stream.close();
            }
            makeWorldReadable(temp);
            if (statusFile.exists() && !statusFile.delete()) {
                // The rename below still replaces it on POSIX file systems.
            }
            if (!temp.renameTo(statusFile)) {
                FileOutputStream direct = new FileOutputStream(statusFile);
                try {
                    direct.write(content.getBytes("UTF-8"));
                    direct.flush();
                } finally {
                    direct.close();
                }
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
            makeWorldReadable(statusFile);
        } catch (IOException ignored) {
        }
    }

    private static void makeWorldReadable(File file) {
        try {
            //noinspection ResultOfMethodCallIgnored
            file.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            file.setWritable(true, false);
        } catch (Throwable ignored) {
        }
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "unknown error";
        }
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (Throwable ignored) {
            return fallback;
        }
    }
}
