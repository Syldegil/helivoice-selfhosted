package io.github.syldegil.helivoice;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

final class VoiceRecorder {
    interface Listener {
        void onLevel(int level);
        void onFinished(byte[] pcm, int pcmLength, boolean heardSpeech);
        void onCanceled();
        void onError(String message);
    }

    private static final int SAMPLE_RATE = 16000;
    private static final int MAX_SECONDS = 20 * 60;
    private static final int NO_SPEECH_TIMEOUT_MS = 8000;
    private static final int TRAILING_SILENCE_MS = 1500;
    // RMS values reported by Android vary a lot between devices. A fixed threshold of
    // 500 rejected quiet but perfectly usable speech on microphones with a low gain.
    private static final double INITIAL_NOISE_FLOOR = 24.0;
    private static final int MIN_START_THRESHOLD = 90;
    private static final int MIN_CONTINUE_THRESHOLD = 55;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean canceled = new AtomicBoolean(false);
    private AudioRecord audioRecord;

    @SuppressLint("MissingPermission")
    void start(Listener listener) {
        if (!running.compareAndSet(false, true)) return;
        canceled.set(false);
        int minimum = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int bufferSize = Math.max(4096, minimum * 2);
        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize);
        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            running.set(false);
            audioRecord.release();
            audioRecord = null;
            listener.onError("Impossible d’ouvrir le microphone");
            return;
        }
        new Thread(() -> recordLoop(listener), "HeliVoiceRecorder").start();
    }

    void stop() {
        running.set(false);
    }

    void cancel() {
        canceled.set(true);
        running.set(false);
    }

    boolean isRunning() {
        return running.get();
    }

    private void recordLoop(Listener listener) {
        PcmBuffer pcm = new PcmBuffer(SAMPLE_RATE * 2 * 10);
        byte[] buffer = new byte[640];
        long start = System.currentTimeMillis();
        long lastSpeech = start;
        double noiseFloor = INITIAL_NOISE_FLOOR;
        int speechFrames = 0;
        boolean heardSpeech = false;
        try {
            audioRecord.startRecording();
            while (running.get()) {
                int count = audioRecord.read(buffer, 0, buffer.length);
                if (count <= 0) continue;
                pcm.write(buffer, 0, count);
                int rms = rms(buffer, count);
                listener.onLevel(levelFromRms(rms));
                long now = System.currentTimeMillis();
                int startThreshold = startThreshold(noiseFloor);
                if (!heardSpeech && rms < startThreshold) {
                    // Follow a changing room noise slowly without letting speech raise
                    // the reference level enough to hide the following words.
                    double weight = rms <= noiseFloor ? 0.08 : 0.015;
                    noiseFloor = noiseFloor * (1.0 - weight) + rms * weight;
                }
                int activeThreshold = heardSpeech
                        ? continueThreshold(noiseFloor)
                        : startThreshold;
                if (rms >= activeThreshold) {
                    speechFrames++;
                    if (speechFrames >= 3) heardSpeech = true;
                    lastSpeech = now;
                } else if (!heardSpeech) {
                    speechFrames = 0;
                }

                if (heardSpeech && now - lastSpeech >= TRAILING_SILENCE_MS) {
                    running.set(false);
                } else if (!heardSpeech && now - start >= NO_SPEECH_TIMEOUT_MS) {
                    running.set(false);
                } else if (now - start >= MAX_SECONDS * 1000L) {
                    running.set(false);
                }
            }
        } catch (Exception exception) {
            listener.onError("Erreur pendant l’enregistrement");
            return;
        } finally {
            try {
                if (audioRecord != null && audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
            } catch (IllegalStateException ignored) {
                // Recorder is already stopped.
            }
            if (audioRecord != null) audioRecord.release();
            audioRecord = null;
            running.set(false);
        }
        if (canceled.get()) listener.onCanceled();
        else {
            normalizeQuietPcm(pcm.data(), pcm.size());
            listener.onFinished(pcm.data(), pcm.size(), heardSpeech);
        }
    }

    static int startThreshold(double noiseFloor) {
        return (int) Math.max(MIN_START_THRESHOLD, noiseFloor * 2.35 + 20.0);
    }

    static int continueThreshold(double noiseFloor) {
        return (int) Math.max(MIN_CONTINUE_THRESHOLD, noiseFloor * 1.55 + 12.0);
    }

    static int levelFromRms(int rms) {
        if (rms <= 0) return 0;
        // A dB scale makes low-gain microphones visible. -60 dBFS maps to 0 and
        // -6 dBFS to 100; values above that are simply clipped by the meter.
        double dbfs = 20.0 * Math.log10(rms / 32768.0);
        return (int) Math.max(0, Math.min(100, Math.round((dbfs + 60.0) * (100.0 / 54.0))));
    }

    static void normalizeQuietPcm(byte[] pcm, int length) {
        int peak = 0;
        for (int i = 0; i + 1 < length; i += 2) {
            int sample = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            peak = Math.max(peak, Math.abs(sample));
        }
        // Whisper generally copes well with normal levels. Only raise genuinely quiet
        // recordings, cap the boost, and retain headroom to avoid clipping.
        if (peak < 128 || peak >= 12000) return;
        double gain = Math.min(8.0, 12000.0 / peak);
        if (gain < 1.15) return;
        for (int i = 0; i + 1 < length; i += 2) {
            int sample = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            int amplified = (int) Math.round(sample * gain);
            amplified = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, amplified));
            pcm[i] = (byte) amplified;
            pcm[i + 1] = (byte) (amplified >> 8);
        }
    }

    /** Exposes the filled portion without duplicating up to 38.4 MB of PCM. */
    private static final class PcmBuffer extends ByteArrayOutputStream {
        PcmBuffer(int initialSize) {
            super(initialSize);
        }

        byte[] data() {
            return buf;
        }
    }

    private static int rms(byte[] data, int count) {
        long sum = 0;
        int samples = count / 2;
        for (int i = 0; i + 1 < count; i += 2) {
            int sample = (short) ((data[i] & 0xff) | (data[i + 1] << 8));
            sum += (long) sample * sample;
        }
        return samples == 0 ? 0 : (int) Math.sqrt((double) sum / samples);
    }
}
