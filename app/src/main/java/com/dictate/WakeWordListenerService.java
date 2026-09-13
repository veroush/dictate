package com.dictate;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

public class WakeWordListenerService extends Service {

    private static final String CHANNEL_ID = "sidenote_listener";
    private static final int NOTIFICATION_ID = 1;
    public static final String ACTION_STOP = "com.dictate.action.STOP_LISTENING";

    // Same 60s idea as before -- once command capture is wired in (stage 2)
    // this will reset on real activity, not just wake-word detection.
    private static final long UNLOCK_TIMEOUT_MS = 60000;

    private WakeWordBridge wakeWordBridge;
    private boolean unlocked = false;

    private final Handler lockHandler = new Handler(Looper.getMainLooper());
    private final Runnable lockRunnable = this::lockSession;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        wakeWordBridge = new WakeWordBridge(this,
                () -> new Handler(Looper.getMainLooper()).post(this::onWakeWordDetected));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification());
        wakeWordBridge.start();
        return START_STICKY; // Android tries to restart this service if it's killed
    }

    private void onWakeWordDetected() {
        unlocked = true;
        updateNotification();
        resetLockTimer();
        // Command capture is stage 2 -- for now this just proves the wake
        // word works with the screen off / the app closed.
    }

    private void resetLockTimer() {
        lockHandler.removeCallbacks(lockRunnable);
        lockHandler.postDelayed(lockRunnable, UNLOCK_TIMEOUT_MS);
    }

    private void lockSession() {
        unlocked = false;
        updateNotification();
    }

    private void updateNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private Notification buildNotification() {
        Intent stopIntent = new Intent(this, WakeWordListenerService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Sidenote")
                .setContentText(unlocked ? "Unlocked" : "Locked")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Sidenote Listening", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        lockHandler.removeCallbacks(lockRunnable);
        if (wakeWordBridge != null) {
            wakeWordBridge.stop();
            wakeWordBridge.release();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // not a bound service for this stage
    }
}