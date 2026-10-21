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

    /** Background thread: reads what came in since last time; true when the note changed. */
    boolean poll() {
        try {
            long offset = store.getLong(KEY_OFFSET, 0);
            JSONObject reply = new JSONObject(call("getUpdates?timeout=0&allowed_updates=%5B%22message%22%5D"
                    + (offset > 0 ? "&offset=" + offset : "")));
            if (!reply.optBoolean("ok")) return false;
            JSONArray updates = reply.getJSONArray("result");
            String note = null;
            long chat = 0;
            for (int i = 0; i < updates.length(); i++) {
                JSONObject u = updates.getJSONObject(i);
                offset = Math.max(offset, u.getLong("update_id") + 1);
                JSONObject m = u.optJSONObject("message");
                if (m == null || m.optJSONObject("from") == null) continue;
                if (m.getJSONObject("from").optLong("id") != owner) continue;
                String text = m.optString("text", "").trim();
                if (text.isEmpty()) continue;
                if (text.equals("/clear")) {
                    note = "";
                } else if (text.startsWith("/")) {
                    continue;   // /start and other commands are not notes
                } else {
                    note = text.length() > MAX_NOTE ? text.substring(0, MAX_NOTE) : text;
                }
                chat = m.getJSONObject("chat").optLong("id");
            }
            SharedPreferences.Editor e = store.edit().putLong(KEY_OFFSET, offset);
            if (note != null) e.putString(KEY_TEXT, note).putLong(KEY_AT, System.currentTimeMillis());
            e.apply();
            if (note != null && chat != 0) {
                String ack = note.isEmpty() ? "Cleared the note on the TV." : "On the TV ✓";
                call("sendMessage?chat_id=" + chat + "&text=" + URLEncoder.encode(ack, "UTF-8"));
            }
            return note != null;
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
