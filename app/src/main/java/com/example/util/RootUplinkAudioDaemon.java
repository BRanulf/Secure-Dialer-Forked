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

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;

/**
 * Persistent root audio bridge used by the in-call soundboard and the hold-to-bleep
 * button.
 *
 * Starting an `app_process` helper for every short sound would add seconds of
 * latency. This helper keeps one Telephony Tx {@link AudioTrack} open for the call
 * and watches a tiny command file written by the app:
 *
 * <pre>
 *   seq=12
 *   op=play | tone | stop | shutdown
 *   path=/path/to/raw.pcm          (op=play)
 *   frequency=1000                 (op=tone)
 * </pre>
 *
 * Raw PCM files are little-endian 16-bit mono at {@link #SAMPLE_RATE}. The helper
 * converts them to stereo because the AOSP incall-music mix port expects stereo on
 * the devices tested. Tone playback generates a sine wave locally.
 *
 * Arguments:
 *  0: status file path
 *  1: command file path
 *  2: maximum daemon lifetime in seconds (safety net for orphaned processes)
 */
public final class RootUplinkAudioDaemon {

    private static final int SAMPLE_RATE = 16_000;
    private static final int MONO_FRAME_BYTES = 2;
    private static final int CHUNK_FRAMES = 320; // 20 ms
    private static final double TWO_PI = Math.PI * 2.0;
    private static final double BEEP_AMPLITUDE = 0.30 * 32767.0;
    private static final int DEFAULT_BEEP_HZ = 1_000;
    private static final short[] SILENCE = new short[CHUNK_FRAMES * 2];

    private static File statusFile;
    private static File commandFile;
    private static String routedDescription = "none";
    private static long lastSequence = Long.MIN_VALUE;

    private RootUplinkAudioDaemon() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    public static int run(String[] args) {
        if (args == null || args.length < 2) {
            return 2;
        }

        statusFile = new File(args[0]);
        commandFile = new File(args[1]);
        long maxSeconds = args.length >= 3 ? parseLong(args[2], 1_800L) : 1_800L;

        writeStatus("state=starting");
        AudioTrack track = null;
        try {
            AudioDeviceInfo telephonyTx = findTelephonyTx();
            if (telephonyTx == null) {
                writeStatus("state=error\nerror=no Telephony Tx output device");
                return 2;
            }

            track = createTrack();
            if (track == null) {
                writeStatus("state=error\nerror=AudioTrack initialization failed");
                return 2;
            }
            if (!track.setPreferredDevice(telephonyTx)) {
                writeStatus("state=error\nerror=setPreferredDevice(Telephony Tx) rejected");
                return 2;
            }

            track.setVolume(1.0f);
            track.play();

            // Open the output stream with silent pre-roll before verifying the route.
            short[] preRoll = new short[512 * 2];
            track.write(preRoll, 0, preRoll.length);

            AudioDeviceInfo routed = waitForTelephonyRoute(track, 2_500L);
            if (routed == null) {
                writeStatus("state=error\nerror=audio was not routed to Telephony Tx");
                return 2;
            }
            routedDescription = describe(routed);

            // Ignore a stale command left over from a previous daemon instance.
            Command existing = readCommand();
            if (existing != null) {
                lastSequence = existing.sequence;
            }

            writeStatus(
                    "state=ready"
                            + "\nrouted=" + routedDescription
                            + "\nrate=" + SAMPLE_RATE
            );

            long deadline = System.currentTimeMillis() + maxSeconds * 1000L;
            while (System.currentTimeMillis() < deadline) {
                Command command = readCommand();
                if (command != null && command.sequence > lastSequence) {
                    lastSequence = command.sequence;
                    if ("shutdown".equals(command.op)) {
                        writeStatus("state=stopped");
                        return 0;
                    }
                    if ("tone".equals(command.op)) {
                        playTone(track, command);
                    } else if ("play".equals(command.op)) {
                        playPcmFile(track, command);
                    } else {
                        // "stop" (and any unknown operation) returns to idle.
                        writeStatus("state=ready\nrouted=" + routedDescription);
                    }
                } else {
                    writeSilence(track);
                }
            }

            writeStatus("state=stopped\nerror=daemon lifetime reached");
            return 0;
        } catch (Throwable t) {
            writeStatus(
                    "state=error\nerror=" + sanitize(
                            t.getClass().getSimpleName()
                                    + (t.getMessage() == null ? "" : ": " + t.getMessage())
                    )
            );
            return 2;
        } finally {
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

    private static AudioTrack createTrack() {
        int channelMask = AudioFormat.CHANNEL_OUT_STEREO;
        int minBuffer = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT
        );
        if (minBuffer <= 0) {
            return null;
        }
        // Keep at least ~160 ms queued so short writes cannot underrun.
        int bufferSize = Math.max(minBuffer * 2, SAMPLE_RATE * 4 / 5);

        AudioTrack track = new AudioTrack.Builder()
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
            try {
                track.release();
            } catch (Throwable ignored) {
            }
            return null;
        }
        return track;
    }

    private static void writeSilence(AudioTrack track) {
        track.write(SILENCE, 0, SILENCE.length);
    }

    private static void playTone(AudioTrack track, Command command) {
        int frequency = command.frequency;
        if (frequency < 200 || frequency > 4_000) {
            frequency = DEFAULT_BEEP_HZ;
        }
        writeStatus("state=playing\nkind=tone\nfrequency=" + frequency);

        double step = TWO_PI * frequency / SAMPLE_RATE;
        double phase = 0.0;
        short[] stereo = new short[CHUNK_FRAMES * 2];

        while (!hasNewerCommand(command.sequence)) {
            for (int frame = 0; frame < CHUNK_FRAMES; frame++) {
                short sample = (short) (Math.sin(phase) * BEEP_AMPLITUDE);
                stereo[frame * 2] = sample;
                stereo[frame * 2 + 1] = sample;
                phase += step;
                if (phase >= TWO_PI) {
                    phase -= TWO_PI;
                }
            }
            int written = track.write(stereo, 0, stereo.length);
            if (written < 0) {
                writeStatus("state=error\nerror=AudioTrack tone write failed: " + written);
                return;
            }
        }

        writeStatus("state=ready\nrouted=" + routedDescription);
    }

    private static void playPcmFile(AudioTrack track, Command command) {
        if (command.path == null || command.path.isEmpty()) {
            writeStatus("state=error\nerror=sound PCM path is missing");
            return;
        }
        File pcmFile = new File(command.path);
        if (!pcmFile.isFile() || pcmFile.length() < MONO_FRAME_BYTES) {
            writeStatus("state=error\nerror=sound PCM is missing");
            return;
        }

        writeStatus("state=playing\nkind=sound");
        BufferedInputStream input = null;
        try {
            input = new BufferedInputStream(
                    new FileInputStream(pcmFile),
                    64 * 1024
            );
            byte[] monoBytes = new byte[CHUNK_FRAMES * MONO_FRAME_BYTES];
            short[] stereo = new short[CHUNK_FRAMES * 2];

            while (!hasNewerCommand(command.sequence)) {
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
                    writeStatus("state=error\nerror=AudioTrack sound write failed: " + written);
                    return;
                }
            }

            writeStatus("state=ready\nrouted=" + routedDescription);
        } catch (IOException io) {
            writeStatus(
                    "state=error\nerror=" + sanitize(
                            io.getClass().getSimpleName()
                                    + (io.getMessage() == null ? "" : ": " + io.getMessage())
                    )
            );
        } finally {
            try {
                if (input != null) input.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean hasNewerCommand(long sequence) {
        Command command = readCommand();
        return command != null && command.sequence > sequence;
    }

    private static Command readCommand() {
        if (commandFile == null || !commandFile.isFile()) {
            return null;
        }
        String text;
        try {
            text = readText(commandFile);
        } catch (IOException ignored) {
            return null;
        }
        if (text == null || text.isEmpty()) {
            return null;
        }

        Command command = new Command();
        boolean sawSequence = false;
        for (String line : text.split("\n")) {
            int index = line.indexOf('=');
            if (index <= 0) {
                continue;
            }
            String key = line.substring(0, index).trim();
            String value = line.substring(index + 1).trim();
            if ("seq".equals(key)) {
                command.sequence = parseLong(value, Long.MIN_VALUE);
                sawSequence = true;
            } else if ("op".equals(key)) {
                command.op = value;
            } else if ("path".equals(key)) {
                command.path = value;
            } else if ("frequency".equals(key)) {
                command.frequency = (int) parseLong(value, DEFAULT_BEEP_HZ);
            }
        }
        return sawSequence ? command : null;
    }

    private static String readText(File file) throws IOException {
        FileInputStream stream = new FileInputStream(file);
        try {
            byte[] data = new byte[(int) Math.min(file.length(), 64 * 1024)];
            int read = 0;
            while (read < data.length) {
                int count = stream.read(data, read, data.length - read);
                if (count <= 0) break;
                read += count;
            }
            return new String(data, 0, read, "UTF-8");
        } finally {
            try {
                stream.close();
            } catch (Throwable ignored) {
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
        // AudioManager.getDevicesStatic(int) is hidden but callable from UID 0.
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

    private static void writeStatus(String content) {
        if (statusFile == null) {
            return;
        }
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

    private static final class Command {
        long sequence = Long.MIN_VALUE;
        String op = "";
        String path = "";
        int frequency = DEFAULT_BEEP_HZ;
    }
}
