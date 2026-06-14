package com.example.whistledetector;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class WhistleService extends Service implements AudioProcessor.WhistleListener {
    private static final String TAG = "WhistleService";
    public static final String ACTION_START = "ACTION_START";
    public static final String ACTION_STOP = "ACTION_STOP";
    public static final String ACTION_STOP_ALARM = "ACTION_STOP_ALARM";
    public static final String EXTRA_TARGET_WHISTLES = "EXTRA_TARGET_WHISTLES";

    // Broadcast action to update UI
    public static final String ACTION_UPDATE_UI = "com.example.whistledetector.UPDATE_UI";
    public static final String EXTRA_CURRENT_COUNT = "EXTRA_CURRENT_COUNT";
    public static final String EXTRA_ALARM_TRIGGERED = "EXTRA_ALARM_TRIGGERED";

    private static final String CHANNEL_ID = "WhistleServiceChannel";
    private static final int NOTIFICATION_ID = 1;

    private AudioProcessor audioProcessor;
    private MediaPlayer mediaPlayer;
    private int targetWhistles = 0;
    private int currentWhistleCount = 0;
    private boolean isListening = false;
    private boolean isAlarmPlaying = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_START.equals(action)) {
                targetWhistles = intent.getIntExtra(EXTRA_TARGET_WHISTLES, 1);
                currentWhistleCount = 0;
                startListening();
            } else if (ACTION_STOP.equals(action)) {
                stopListening();
                stopAlarm();
                stopSelf();
            } else if (ACTION_STOP_ALARM.equals(action)) {
                stopAlarm();
                stopSelf();
            }
        }
        return START_NOT_STICKY;
    }

    private void startListening() {
        if (isListening) return;

        Notification notification = createNotification("Listening for whistles... (0/" + targetWhistles + ")");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        try {
            audioProcessor = new AudioProcessor(this);
            audioProcessor.start();
            isListening = true;
            broadcastUpdate(false);
        } catch (SecurityException e) {
            Log.e(TAG, "Audio permission denied", e);
            stopSelf();
        }
    }

    private void stopListening() {
        if (!isListening) return;

        if (audioProcessor != null) {
            audioProcessor.stop();
            audioProcessor = null;
        }
        isListening = false;
        broadcastUpdate(isAlarmPlaying);
    }

    @Override
    public void onWhistleDetected() {
        if (!isListening) return;

        currentWhistleCount++;
        Log.d(TAG, "Whistle detected. Count: " + currentWhistleCount + "/" + targetWhistles);

        updateNotification("Whistle detected: " + currentWhistleCount + "/" + targetWhistles);

        if (currentWhistleCount >= targetWhistles) {
            triggerAlarm();
        } else {
            broadcastUpdate(false);
        }
    }

    private void triggerAlarm() {
        Log.d(TAG, "Target whistles reached! Triggering alarm.");
        stopListening();
        updateNotification("Target whistles reached!");

        playAlarm();
        broadcastUpdate(true);
    }

    private void playAlarm() {
        if (isAlarmPlaying) return;

        try {
            Uri alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            }
            mediaPlayer = MediaPlayer.create(this, alarmUri);
            if (mediaPlayer != null) {
                mediaPlayer.setLooping(true);
                mediaPlayer.start();
                isAlarmPlaying = true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to play alarm", e);
        }
    }

    private void stopAlarm() {
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }
        isAlarmPlaying = false;
        broadcastUpdate(false);
    }

    private void broadcastUpdate(boolean alarmTriggered) {
        Intent updateIntent = new Intent(ACTION_UPDATE_UI);
        updateIntent.putExtra(EXTRA_CURRENT_COUNT, currentWhistleCount);
        updateIntent.putExtra(EXTRA_ALARM_TRIGGERED, alarmTriggered);
        updateIntent.setPackage(getPackageName());
        sendBroadcast(updateIntent);
    }

    private Notification createNotification(String text) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent;
        int flags = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = PendingIntent.FLAG_IMMUTABLE;
        }
        pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, flags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Whistle Detector")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now) // Default icon for now
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, createNotification(text));
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Whistle Detector Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // We use started service, not bound
    }

    @Override
    public void onDestroy() {
        stopListening();
        stopAlarm();
        super.onDestroy();
    }
}
