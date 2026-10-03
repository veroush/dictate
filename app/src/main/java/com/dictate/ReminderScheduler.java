package com.dictate;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Schedules reminders on the phone itself (AlarmManager), so they fire even
 * if the laptop server is off. The server only says "in N seconds, with this
 * text"; the phone adds N seconds to its OWN clock.
 *
 * Two lists live in SharedPreferences:
 *   pending = scheduled, not fired yet (used to re-schedule after a reboot later)
 *   unread  = fired, but Jarvis hasn't read them out loud yet
 */
public class ReminderScheduler {

    private static final String TAG = "DictateReminder";
    private static final String PREFS = "jarvis_reminders";
    private static final String KEY_PENDING = "pending";
    private static final String KEY_UNREAD = "unread";
    private static final String KEY_NEXT_ID = "next_id";

    public static final String ACTION_FIRE = "com.dictate.action.REMINDER_FIRE";
    public static final String EXTRA_ID = "reminder_id";
    public static final String EXTRA_TEXT = "reminder_text";

    public static class Reminder {
        public final int id;
        public final String text;
        public final long fireAt;

        Reminder(int id, String text, long fireAt) {
            this.id = id;
            this.text = text;
            this.fireAt = fireAt;
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Schedules a reminder delaySeconds from now. Returns true if an exact alarm was set. */
    public static synchronized boolean schedule(Context ctx, String text, long delaySeconds) {
        Context app = ctx.getApplicationContext();
        SharedPreferences p = prefs(app);
        int id = p.getInt(KEY_NEXT_ID, 1);
        long fireAt = System.currentTimeMillis() + delaySeconds * 1000L;
        Reminder r = new Reminder(id, text, fireAt);

        List<Reminder> pending = read(p, KEY_PENDING);
        pending.add(r);
        p.edit().putInt(KEY_NEXT_ID, id + 1).putString(KEY_PENDING, toJson(pending)).apply();

        boolean exact = setAlarm(app, r);
        Log.d(TAG, "Scheduled reminder id=" + id + " in " + delaySeconds + "s exact=" + exact
                + " text=" + text);
        return exact;
    }

    static boolean setAlarm(Context app, Reminder r) {
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            Log.e(TAG, "No AlarmManager");
            return false;
        }
        Intent intent = new Intent(app, ReminderReceiver.class)
                .setAction(ACTION_FIRE)
                .putExtra(EXTRA_ID, r.id)
                .putExtra(EXTRA_TEXT, r.text);
        PendingIntent pi = PendingIntent.getBroadcast(app, r.id, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        boolean canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms();
        try {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.fireAt, pi);
                return true;
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Exact alarm not allowed, falling back to inexact", e);
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.fireAt, pi);
        return false;
    }

    /** Called when an alarm fires: moves it from pending to unread. */
    public static synchronized void markFired(Context ctx, int id, String fallbackText) {
        SharedPreferences p = prefs(ctx);
        List<Reminder> pending = read(p, KEY_PENDING);
        List<Reminder> unread = read(p, KEY_UNREAD);

        Reminder fired = null;
        for (int i = 0; i < pending.size(); i++) {
            if (pending.get(i).id == id) {
                fired = pending.remove(i);
                break;
            }
        }
        if (fired == null && fallbackText != null) {
            fired = new Reminder(id, fallbackText, System.currentTimeMillis());
        }
        if (fired != null) unread.add(fired);

        p.edit().putString(KEY_PENDING, toJson(pending)).putString(KEY_UNREAD, toJson(unread)).apply();
    }

    public static synchronized List<Reminder> getUnread(Context ctx) {
        return read(prefs(ctx), KEY_UNREAD);
    }

    public static synchronized boolean hasUnread(Context ctx) {
        return !getUnread(ctx).isEmpty();
    }

    public static synchronized void clearUnread(Context ctx) {
        prefs(ctx).edit().putString(KEY_UNREAD, "[]").apply();
    }

    // ---- tiny JSON helpers ----

    private static List<Reminder> read(SharedPreferences p, String key) {
        List<Reminder> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(p.getString(key, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Reminder(o.getInt("id"), o.getString("text"), o.getLong("fireAt")));
            }
        } catch (JSONException e) {
            Log.e(TAG, "Couldn't read " + key, e);
        }
        return out;
    }

    private static String toJson(List<Reminder> list) {
        JSONArray arr = new JSONArray();
        try {
            for (Reminder r : list) {
                arr.put(new JSONObject()
                        .put("id", r.id)
                        .put("text", r.text)
                        .put("fireAt", r.fireAt));
            }
        } catch (JSONException e) {
            Log.e(TAG, "Couldn't write reminders", e);
        }
        return arr.toString();
    }
}