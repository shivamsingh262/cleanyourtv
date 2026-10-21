package com.gathin.tvhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * A banner over whatever is on screen shortly before a timed agenda event (config.xml reminder_minutes). HomeInfo
 * hands over the agenda whenever it changes; the next reminder is one inexact alarm (a minute's window, so no exact
 * alarm permission), and its receiver shows the banner and sets the alarm for the one after. The banner is an
 * overlay window, which needs "display over other apps" granted once:
 * {@code adb shell appops set com.gathin.tvhome SYSTEM_ALERT_WINDOW allow}. Without it, nothing is shown.
 */
final class Reminders {

    private static final String PREFS = "reminders", KEY_LIST = "list", KEY_DONE = "done";
    private static final long SHOW_MS = 9000;

    private Reminders() {
    }

    /** HomeInfo: the agenda as it is now; replaces the reminders kept from before. */
    static void schedule(Context c, List<CalendarFeed.Event> events, int leadMinutes) {
        JSONArray list = new JSONArray();
        try {
            for (CalendarFeed.Event e : events) {
                if (e.allDay) continue;
                list.put(new JSONObject().put("t", e.start.toInstant().toEpochMilli()).put("title", e.title));
            }
        } catch (org.json.JSONException ignored) {
            // put() with these types does not throw.
        }
        prefs(c).edit().putString(KEY_LIST, list.toString()).putInt("lead", leadMinutes).apply();
        armNext(c);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The alarm for the soonest reminder not shown yet. */
    private static void armNext(Context c) {
        SharedPreferences p = prefs(c);
        long lead = p.getInt("lead", 10) * 60_000L, now = System.currentTimeMillis();
        String done = p.getString(KEY_DONE, "");
        long nextAt = Long.MAX_VALUE;
        try {
            JSONArray list = new JSONArray(p.getString(KEY_LIST, "[]"));
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                long t = o.getLong("t"), at = t - lead;
                // Still worth showing: due from now on, or due a moment ago and the event not begun.
                if (t <= now || done.contains("|" + key(o) + "|")) continue;
                nextAt = Math.min(nextAt, Math.max(at, now + 1000));
            }
        } catch (Exception ignored) {
            // Nothing kept: no alarm.
        }
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent pi = PendingIntent.getBroadcast(c, 0, new Intent(c, Receiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (am == null) return;
        am.cancel(pi);
        if (nextAt != Long.MAX_VALUE) am.setWindow(AlarmManager.RTC_WAKEUP, nextAt, 60_000L, pi);
    }

    private static String key(JSONObject o) {
        return o.optLong("t") + ":" + o.optString("title").hashCode();
    }

    /** The alarm: banners for every reminder now due, then the next alarm. */
    public static final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent intent) {
            SharedPreferences p = prefs(c);
            long lead = p.getInt("lead", 10) * 60_000L, now = System.currentTimeMillis();
            StringBuilder done = new StringBuilder(p.getString(KEY_DONE, ""));
            try {
                JSONArray list = new JSONArray(p.getString(KEY_LIST, "[]"));
                for (int i = 0; i < list.length(); i++) {
                    JSONObject o = list.getJSONObject(i);
                    long t = o.getLong("t");
                    if (t - lead > now + 60_000L || t <= now || done.indexOf("|" + key(o) + "|") >= 0) continue;
                    show(c.getApplicationContext(), o.getString("title"), t);
                    done.append('|').append(key(o)).append('|');
                }
            } catch (Exception ignored) {
                // Unreadable list: nothing to show.
            }
            // Only the last couple of hundred keys matter; they are of events that have passed.
            String d = done.length() > 4000 ? done.substring(done.length() - 4000) : done.toString();
            p.edit().putString(KEY_DONE, d).apply();
            armNext(c);
        }
    }

    /** The banner, top right over any app, gone by itself; it takes no focus and no keys. */
    static void show(Context c, String title, long at) {
        if (!Settings.canDrawOverlays(c)) return;
        WindowManager wm = c.getSystemService(WindowManager.class);
        if (wm == null) return;
        View v = LayoutInflater.from(c).inflate(R.layout.reminder_banner, null);
        long mins = Math.max(1, Math.round((at - System.currentTimeMillis()) / 60_000.0));
        ZonedDateTime when = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault());
        String clock = when.format(DateTimeFormatter.ofPattern(DateFormat.is24HourFormat(c) ? "HH:mm" : "h:mm a",
                Locale.getDefault()));
        ((TextView) v.findViewById(R.id.reminderWhen)).setText(c.getString(R.string.reminder_when, mins, clock));
        ((TextView) v.findViewById(R.id.reminderTitle)).setText(title);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        float d = c.getResources().getDisplayMetrics().density;
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = Math.round(40 * d);
        lp.y = Math.round(32 * d);
        lp.windowAnimations = android.R.style.Animation_Toast;
        try {
            wm.addView(v, lp);
        } catch (RuntimeException e) {
            return;   // the overlay was refused after all
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                wm.removeView(v);
            } catch (RuntimeException ignored) {
                // Already gone.
            }
        }, SHOW_MS);
    }
}
