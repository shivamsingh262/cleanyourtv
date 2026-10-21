package com.gathin.tvhome;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Home's note, sent from anywhere as a Telegram message to the owner's own bot (config.xml telegram_bot_token,
 * telegram_owner_id). Asked from HomeInfo's background thread while Home is on screen: getUpdates, then the newest
 * text from the owner becomes the note ("/clear" empties it) and the bot answers that it is on the TV. Messages from
 * anyone else are read past and ignored. Telegram keeps unread updates for a day, so a note sent while the TV was
 * off shows when it comes back on.
 */
final class TelegramNotes {

    static final String PREFS = "home_notes";
    static final String KEY_TEXT = "text", KEY_AT = "at";
    private static final String KEY_OFFSET = "tg_offset";
    static final int MAX_NOTE = 280;
    private static final int TIMEOUT_MS = 10_000;

    private final String token;
    private final long owner;
    private final SharedPreferences store;

    TelegramNotes(String token, long owner, SharedPreferences store) {
        this.token = token;
        this.owner = owner;
        this.store = store;
    }

    static final String KEY_EVENTS = "events";
    /** Events set from Telegram that have not ended yet, soonest first; "/events" numbers them in this order. */
    static java.util.List<CalendarFeed.Event> events(SharedPreferences store, java.time.ZonedDateTime now) {
        java.util.List<CalendarFeed.Event> out = new java.util.ArrayList<>();
        try {
            JSONArray a = new JSONArray(store.getString(KEY_EVENTS, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                java.time.ZonedDateTime start = java.time.Instant.ofEpochMilli(o.getLong("t")).atZone(now.getZone());
                boolean allDay = o.optBoolean("allDay");
                // Over: an hour after a timed event starts, or the day after an all-day one.
                java.time.ZonedDateTime end = allDay ? start.plusDays(1) : start.plusHours(1);
                if (end.isAfter(now)) out.add(new CalendarFeed.Event(o.getString("title"), start, allDay));
            }
        } catch (Exception ignored) {
            // Nothing kept yet, or unreadable: no events.
        }
        out.sort((x, y) -> x.start.compareTo(y.start));
        return out;
    }

    private static void saveEvents(SharedPreferences.Editor e, java.util.List<CalendarFeed.Event> list) {
        JSONArray a = new JSONArray();
        try {
            for (CalendarFeed.Event ev : list) {
                a.put(new JSONObject().put("t", ev.start.toInstant().toEpochMilli()).put("allDay", ev.allDay)
                        .put("title", ev.title));
            }
        } catch (org.json.JSONException ignored) {
            // put() with these types does not throw.
        }
        e.putString(KEY_EVENTS, a.toString());
    }

    private static String describe(CalendarFeed.Event ev) {
        String day = ev.start.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.ENGLISH));
        return day + (ev.allDay ? " (all day)" : " " + ev.start.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")))
                + " " + ev.title;
    }

    private static final String HELP = "Send any text to set the note on the TV.\n"
            + "/clear removes it.\n\n"
            + "/event 18:00 Dentist\n/event tomorrow 10:30 Standup\n/event fri 6pm Dinner\n"
            + "/event 25 oct 9am Flight\n/event sat Diwali shopping (all day)\n"
            + "/events lists them, /cancel 2 removes the second.";

    /** Background thread: reads what came in since last time; true when the note or the events changed. */
    boolean poll() {
        try {
            long offset = store.getLong(KEY_OFFSET, 0);
            JSONObject reply = new JSONObject(call("getUpdates?timeout=0&allowed_updates=%5B%22message%22%5D"
                    + (offset > 0 ? "&offset=" + offset : "")));
            if (!reply.optBoolean("ok")) return false;
            JSONArray updates = reply.getJSONArray("result");
            String note = null;
            long chat = 0;
            java.time.ZonedDateTime now = java.time.ZonedDateTime.now();
            java.util.List<CalendarFeed.Event> evs = events(store, now);
            boolean evChanged = false;
            java.util.List<String[]> replies = new java.util.ArrayList<>();   // {chat, text}
            for (int i = 0; i < updates.length(); i++) {
                JSONObject u = updates.getJSONObject(i);
                offset = Math.max(offset, u.getLong("update_id") + 1);
                JSONObject m = u.optJSONObject("message");
                if (m == null || m.optJSONObject("from") == null) continue;
                if (m.getJSONObject("from").optLong("id") != owner) continue;
                String text = m.optString("text", "").trim();
                if (text.isEmpty()) continue;
                long from = m.getJSONObject("chat").optLong("id");
                String lower = text.toLowerCase(java.util.Locale.ROOT);
                if (text.equals("/clear")) {
                    note = "";
                    chat = from;
                } else if (lower.startsWith("/event ") || lower.equals("/event")) {
                    EventCommand.Parsed p = EventCommand.parse(text.substring(6), now);
                    if (p == null) {
                        replies.add(new String[]{"" + from, "I couldn't read a day or time and a title there. Try:\n"
                                + "/event 18:00 Dentist\n/event fri 6pm Dinner\n/event 25 oct Flight"});
                    } else {
                        String title = p.title.length() > 80 ? p.title.substring(0, 80) : p.title;
                        CalendarFeed.Event ev = new CalendarFeed.Event(title, p.start, p.allDay);
                        evs.add(ev);
                        evs.sort((x, y) -> x.start.compareTo(y.start));
                        evChanged = true;
                        replies.add(new String[]{"" + from, "Added: " + describe(ev) + " \u2713"});
                    }
                } else if (lower.equals("/events")) {
                    StringBuilder b = new StringBuilder();
                    for (int k = 0; k < evs.size(); k++) b.append(k + 1).append(". ").append(describe(evs.get(k))).append('\n');
                    replies.add(new String[]{"" + from, evs.isEmpty() ? "No events set from here." : b.toString().trim()});
                } else if (lower.startsWith("/cancel")) {
                    int k = -1;
                    try {
                        k = Integer.parseInt(text.substring(7).trim()) - 1;
                    } catch (NumberFormatException ignored) {
                        // reported below
                    }
                    if (k >= 0 && k < evs.size()) {
                        CalendarFeed.Event gone = evs.remove(k);
                        evChanged = true;
                        replies.add(new String[]{"" + from, "Removed: " + describe(gone)});
                    } else {
                        replies.add(new String[]{"" + from, "Send /events, then /cancel with its number."});
                    }
                } else if (text.startsWith("/")) {
                    replies.add(new String[]{"" + from, HELP});   // /start, /help and anything else
                } else {
                    note = text.length() > MAX_NOTE ? text.substring(0, MAX_NOTE) : text;
                    chat = from;
                }
            }
            SharedPreferences.Editor e = store.edit().putLong(KEY_OFFSET, offset);
            if (note != null) e.putString(KEY_TEXT, note).putLong(KEY_AT, System.currentTimeMillis());
            if (evChanged) saveEvents(e, evs);
            e.apply();
            if (note != null && chat != 0) {
                String ack = note.isEmpty() ? "Cleared the note on the TV." : "On the TV \u2713";
                call("sendMessage?chat_id=" + chat + "&text=" + URLEncoder.encode(ack, "UTF-8"));
            }
            for (String[] r : replies) {
                call("sendMessage?chat_id=" + r[0] + "&text=" + URLEncoder.encode(r[1], "UTF-8"));
            }
            return note != null || evChanged;
        } catch (Exception ignored) {
            return false;   // offline or a bad answer: try again next time
        }
    }

    private String call(String method) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.telegram.org/bot" + token + "/" + method)
                .openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream()) {
                if (in == null) return "{}";
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 256 * 1024) out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
