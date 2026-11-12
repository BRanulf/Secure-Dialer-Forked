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

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;

/**
 * Streams raw 16-bit mono PCM into the telephony uplink.
 *
 * Modern AOSP audio policies expose an "incall_music_uplink" mix port and a
 * "Telephony Tx" output device (AUDIO_DEVICE_OUT_TELEPHONY_TX). When a linear PCM
 * track selects that device during a call, AudioPolicyManager adds
 * AUDIO_OUTPUT_FLAG_INCALL_MUSIC and the HAL mixes the audio into the voice TX path.
 *
 * A normal app cannot select that device during a carrier call without
 * MODIFY_PHONE_STATE, so this helper is launched through `su`/`app_process` as UID 0.
 * It uses AudioTrack (not MediaPlayer, which needs a Context) so it can run without
 * an Android application context.
 *
 * Arguments:
 *  0: raw PCM file path (16-bit little-endian, mono, 16 kHz)
 *  1: status file path
 *  2: stop marker path
 *  3: maximum playback duration in seconds
 */
public final class RootIncallMusicPlayer {

    private static final int SAMPLE_RATE = 16_000;
    private static final int MONO_FRAME_BYTES = 2;

    private RootIncallMusicPlayer() {
    }

    public static void main(String[] args) {
        int result = run(args);
        System.exit(result);
    }

    public static int run(String[] args) {
        if (args == null || args.length < 4) {
            return 2;
        }

        final File pcmFile = new File(args[0]);
        final File statusFile = new File(args[1]);
        final File stopFile = new File(args[2]);
        final long maxSeconds = parseLong(args[3], 120L);

        writeStatus(statusFile, "state=starting");

        if (!pcmFile.isFile() || pcmFile.length() < MONO_FRAME_BYTES) {
            writeStatus(statusFile, "state=error\nerror=greeting PCM is missing");
            return 2;
        }
        if (stopFile.exists()) {
            writeStatus(statusFile, "state=error\nerror=stopped before playback");
            return 2;
        }

        AudioTrack track = null;
        FileInputStream input = null;
        try {
            AudioDeviceInfo telephonyTx = findTelephonyTx();
            if (telephonyTx == null) {
                writeStatus(statusFile, "state=error\nerror=no Telephony Tx output device");
                return 2;
            }

            int channelMask = AudioFormat.CHANNEL_OUT_STEREO;
            int minBuffer = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    channelMask,
                    AudioFormat.ENCODING_PCM_16BIT
            );
            if (minBuffer <= 0) {
                writeStatus(statusFile, "state=error\nerror=unsupported 16 kHz stereo output");
                return 2;
            }
            int bufferSize = Math.max(minBuffer * 2, SAMPLE_RATE * 4 / 5);

            track = new AudioTrack.Builder()
                    .setAudioAttributes(
                            new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                    )
                    .setAudioFormat(
                            new AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(SAMPLE_RATE)
                                    .setChannelMask(channelMask)
                                    .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferSize)
                    .build();

            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                writeStatus(statusFile, "state=error\nerror=AudioTrack initialization failed");
                return 2;
            }

            if (!track.setPreferredDevice(telephonyTx)) {
                writeStatus(
                        statusFile,
                        "state=error\nerror=setPreferredDevice(Telephony Tx) rejected"
                );
                return 2;
            }

            track.setVolume(1.0f);
            track.play();

            // Push a small silent pre-roll so the audio policy opens the output
            // stream with the selected device before we verify the route.
            short[] preRoll = new short[512 * 2];
            track.write(preRoll, 0, preRoll.length);

            AudioDeviceInfo routed = waitForTelephonyRoute(track, 2_500L);
            if (routed == null) {
                writeStatus(
                        statusFile,
                        "state=error\nerror=greeting was not routed to Telephony Tx"
                );
                return 2;
            }

            writeStatus(
                    statusFile,
                    "state=playing"
                            + "\nrouted=" + describe(routed)
                            + "\nrate=" + SAMPLE_RATE
            );

            input = new FileInputStream(pcmFile);
            byte[] monoBytes = new byte[Math.max(4096, SAMPLE_RATE)]; // ~0.5 s
            short[] stereo = new short[monoBytes.length];
            long startedAt = System.currentTimeMillis();
            long deadline = startedAt + maxSeconds * 1000L;

            while (System.currentTimeMillis() < deadline) {
                if (stopFile.exists()) {
                    break;
                }
                int read = input.read(monoBytes);
                if (read < MONO_FRAME_BYTES) {
                    break;
                }
                int frames = read / MONO_FRAME_BYTES;
                for (int frame = 0; frame < frames; frame++) {
                    int offset = frame * 2;
                    short sample = (short) (
                            (monoBytes[offset] & 0xFF) |
                                    (monoBytes[offset + 1] << 8)
                    );
                    stereo[frame * 2] = sample;
                    stereo[frame * 2 + 1] = sample;
                }
                int written = track.write(stereo, 0, frames * 2);
                if (written < 0) {
                    writeStatus(
                            statusFile,
                            "state=error\nerror=AudioTrack write failed: " + written
                    );
                    return 2;
                }
            }

            // Let the last buffered frames reach the uplink before releasing.
            try {
                Thread.sleep(250L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }

            writeStatus(statusFile, "state=done\nrouted=" + describe(routed));
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
                if (input != null) input.close();
            } catch (Throwable ignored) {
            }
            if (track != null) {
                try {
                    if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                        track.stop();
                    }
                } catch (Throwable ignored) {
                }
                try {
                    track.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static AudioDeviceInfo findTelephonyTx() {
        AudioDeviceInfo[] devices = getOutputDevices();
        if (devices == null) {
            return null;
        }
        for (AudioDeviceInfo device : devices) {
            if (isTelephonyTx(device)) {
                return device;
            }
        }
        return null;
    }

    private static AudioDeviceInfo[] getOutputDevices() {
        // AudioManager.getDevicesStatic(int) is hidden but callable from a root
        // app_process; it avoids needing a Context in the helper.
        try {
            Method method = AudioManager.class.getDeclaredMethod("getDevicesStatic", int.class);
            method.setAccessible(true);
            Object result = method.invoke(null, AudioManager.GET_DEVICES_OUTPUTS);
            return (AudioDeviceInfo[]) result;
        } catch (Throwable ignored) {
        }
        try {
            Method method = AudioManager.class.getDeclaredMethod(
                    "getDeviceInfoFromType",
                    int.class
            );
            method.setAccessible(true);
            Object result = method.invoke(null, AudioDeviceInfo.TYPE_TELEPHONY);
            if (result instanceof AudioDeviceInfo) {
                return new AudioDeviceInfo[]{(AudioDeviceInfo) result};
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static AudioDeviceInfo getRoutedDevice(AudioTrack track) {
        try {
            return track.getRoutedDevice();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static AudioDeviceInfo waitForTelephonyRoute(AudioTrack track, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            AudioDeviceInfo last = getRoutedDevice(track);
            if (last != null && isTelephonyTx(last)) {
                return last;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }

    private static boolean isTelephonyTx(AudioDeviceInfo device) {
        if (device == null) {
            return false;
        }
        if (device.getType() == AudioDeviceInfo.TYPE_TELEPHONY) {
            return true;
        }
        try {
            String name = String.valueOf(device.getProductName()).toLowerCase();
            return name.contains("telephony") || name.contains("voice tx");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String describe(AudioDeviceInfo device) {
        if (device == null) {
            return "none";
        }
        try {
            return "id=" + device.getId()
                    + ",type=" + device.getType()
                    + ",name=" + device.getProductName();
        } catch (Throwable ignored) {
            return "unknown";
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
            //noinspection ResultOfMethodCallIgnored
            statusFile.delete();
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

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (Throwable ignored) {
            return fallback;
        }
    }
}
