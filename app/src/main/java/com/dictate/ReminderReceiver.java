package com.dictate;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * Runs when a reminder alarm fires. Records it as "unread" and posts a
 * notification that plays the Jarvis sound.
 *
 * Sound: if the file res/raw/jarvis_reminder.(mp3|ogg|wav) exists, that is
 * used; otherwise the phone's default notification sound. A notification
 * channel's sound can't be changed after it is created, so the custom-sound
 * and default-sound cases use two different channel ids.
 */
public class ReminderReceiver extends BroadcastReceiver {

    private static final String TAG = "DictateReminder";
    private static final String CHANNEL_CUSTOM = "jarvis_reminder_custom";
    private static final String CHANNEL_DEFAULT = "jarvis_reminder_default";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ReminderScheduler.ACTION_FIRE.equals(intent.getAction())) return;

        int id = intent.getIntExtra(ReminderScheduler.EXTRA_ID, -1);
        String text = intent.getStringExtra(ReminderScheduler.EXTRA_TEXT);
        if (text == null) text = "Reminder";
        Log.d(TAG, "Reminder fired id=" + id + " text=" + text);

        ReminderScheduler.markFired(context, id, text);
        postNotification(context, id, text);
    }

    private void postNotification(Context context, int id, String text) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;

        int resId = context.getResources().getIdentifier(
                "jarvis_reminder", "raw", context.getPackageName());
        boolean custom = resId != 0;
        Uri sound = custom
                ? Uri.parse("android.resource://" + context.getPackageName() + "/" + resId)
                : RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        String channelId = custom ? CHANNEL_CUSTOM : CHANNEL_DEFAULT;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId, "Jarvis reminders", NotificationManager.IMPORTANCE_HIGH);
            channel.setSound(sound, new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            channel.enableVibration(true);
            nm.createNotificationChannel(channel);
        }

        PendingIntent open = PendingIntent.getActivity(context, 0,
                new Intent(context, TranscriptionActivity.class), PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Jarvis")
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(open)
                .setAutoCancel(true);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            b.setSound(sound).setVibrate(new long[]{0, 300, 200, 300});
        }

        try {
            nm.notify(1000 + Math.max(id, 0), b.build());
        } catch (SecurityException e) {
            Log.e(TAG, "Notification blocked (POST_NOTIFICATIONS?)", e);
        }
    }
}