package com.example.whistledetector;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

public class AudioProcessor {
    private static final String TAG = "AudioProcessor";
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private AudioRecord audioRecord;
    private int bufferSize;
    private boolean isRecording = false;
    private Thread recordingThread;

    private final WhistleListener listener;

    // Heuristic thresholds tweaked for emulators/laptop speakers
    private static final int AMPLITUDE_THRESHOLD = 500; // Lowered significantly
    private static final int MIN_ZERO_CROSSING_RATE = 800; // Widened lower bound
    private static final int MAX_ZERO_CROSSING_RATE = 10000; // Widened upper bound

    // Consecutively positive frames required to confirm a whistle
    private static final int REQUIRED_CONSECUTIVE_FRAMES = 5; // Reduced required frames
    private int consecutiveWhistleFrames = 0;

    private long lastWhistleTime = 0;
    private static final long WHISTLE_COOLDOWN_MS = 1500; // 1.5 seconds between whistles

    public interface WhistleListener {
        void onWhistleDetected();
    }

    public AudioProcessor(WhistleListener listener) {
        this.listener = listener;
        bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
    }

    public void start() throws SecurityException {
        if (isRecording) return;

        // Use UNPROCESSED or VOICE_RECOGNITION to try and bypass OS-level noise suppression
        // that often filters out continuous whistling noises.
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize);

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord initialization failed");
            return;
        }

        audioRecord.startRecording();
        isRecording = true;
        consecutiveWhistleFrames = 0;

        recordingThread = new Thread(this::processAudio);
        recordingThread.start();
    }

    public void stop() {
        isRecording = false;
        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }
        if (recordingThread != null) {
            if (Thread.currentThread() != recordingThread) {
                try {
                    recordingThread.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            recordingThread = null;
        }
    }

    private void processAudio() {
        short[] buffer = new short[bufferSize / 2];

        while (isRecording) {
            int readSize = audioRecord.read(buffer, 0, buffer.length);
            if (readSize > 0) {
                analyzeBuffer(buffer, readSize);
            }
        }
    }

    private void analyzeBuffer(short[] buffer, int readSize) {
        long sum = 0;
        int zeroCrossings = 0;

        for (int i = 0; i < readSize; i++) {
            sum += Math.abs(buffer[i]);

            if (i > 0) {
                // Check if sign changed
                if ((buffer[i] > 0 && buffer[i - 1] < 0) || (buffer[i] < 0 && buffer[i - 1] > 0)) {
                    zeroCrossings++;
                }
            }
        }

        double averageAmplitude = (double) sum / readSize;

        // Calculate Zero Crossing Rate (crossings per second)
        // readSize samples represent (readSize / SAMPLE_RATE) seconds
        double durationInSeconds = (double) readSize / SAMPLE_RATE;
        double zcr = zeroCrossings / durationInSeconds;

        // Diagnostic log: Print properties of any audible sound to Logcat to help troubleshoot emulator audio routing
        if (averageAmplitude > 100) {
            Log.d(TAG, "Audio frame detected: Amplitude=" + averageAmplitude + ", ZCR=" + zcr);
        }

        if (averageAmplitude > AMPLITUDE_THRESHOLD &&
            zcr > MIN_ZERO_CROSSING_RATE &&
            zcr < MAX_ZERO_CROSSING_RATE) {

            consecutiveWhistleFrames++;
            if (consecutiveWhistleFrames >= REQUIRED_CONSECUTIVE_FRAMES) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastWhistleTime > WHISTLE_COOLDOWN_MS) {
                    Log.d(TAG, "Whistle detected! Amplitude: " + averageAmplitude + ", ZCR: " + zcr);
                    if (listener != null) {
                        listener.onWhistleDetected();
                    }
                    lastWhistleTime = currentTime;
                }
                consecutiveWhistleFrames = 0; // Reset after detection
            }
        } else {
            // Decay consecutive frames if criteria not met to handle slight interruptions
            if (consecutiveWhistleFrames > 0) {
                consecutiveWhistleFrames--;
            }
        }
    }
}
