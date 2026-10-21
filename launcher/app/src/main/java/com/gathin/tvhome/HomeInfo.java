package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.text.style.ImageSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DateFormatSymbols;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Two extras on Home, both run only while Home is resumed:
 * <ul>
 * <li>A line under the clock with the weather (Open-Meteo, for config.xml's latitude and longitude, every 30
 * minutes) and sunrise and sunset (computed on the TV by {@link Sun}). The last reading is kept, so the line
 * shows at once and survives a failed fetch; one older than three hours is not shown as current.</li>
 * <li>"On this day": a screensaver photo taken on today's date in an earlier year (else this week, else this
 * month) as a small print on the top shelf, chosen once a day and shown and hidden with the shelf.</li>
 * </ul>
 * Either view may be missing (a layout without them): that part is then off.
 */
final class HomeInfo {

    private static final long WEATHER_MS = 30 * 60_000L;
    private static final long STALE_MS = 3 * 60 * 60_000L;
    private static final int TIMEOUT_MS = 10_000;
    private static final String PREFS = "home_info";
    private static final String SEP = "  ·  ";
    /** The print's photo, dp: a landscape photo's width, a portrait one's height. */
    private static final int MEMORY_LANDSCAPE_W_DP = 196, MEMORY_PORTRAIT_H_DP = 150;
    /** With top_menu the top right is free: a larger print near the top, its heading written in the strip. */
    private static final int OPEN_LANDSCAPE_W_DP = 240, OPEN_PORTRAIT_H_DP = 160, OPEN_TOP_DP = 6;
    /**
     * Roomy, once the dock's place is known (setDockTop): the photo is as tall as the room down to the dock allows,
     * at most MAX_W_FRACTION of the screen wide. Anything the frame adds below the photo is CARD_EXTRA_DP (none: the caption sits on it), and the print keeps
     * DOCK_GAP_DP clear of the dock and MENU_GAP_DP clear of the open top menu, shrinking towards its top right
     * corner to do so.
     */
    private static final float MAX_W_FRACTION = 0.34f;
    private static final int CARD_EXTRA_DP = 0, DOCK_GAP_DP = 14, MENU_GAP_DP = 18;
    /** The date tools/photos.sh puts in each name: photo-0007-20231202.jpg, or only the month, -202312. */
    private static final Pattern NAME_DATE = Pattern.compile("-(\\d{4})(\\d{2})(\\d{2})?\\.[A-Za-z]+$");

    private final Activity activity;
    private final TextView line;
    private final View memory;
    private final ImageView memoryPhoto;
    private final TextView memoryKicker, memoryDate;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    /** lineOn: config.xml weather with a place set. weatherOn: that, and the build may use the network. */
    private final boolean lineOn, weatherOn, memoryOn, hasPlace;
    private final double latitude, longitude;
    private HandlerThread thread;
    private Handler bg;
    /** The day the print was chosen for; another day chooses again. */
    private LocalDate memoryDay;
    /** Whether the top shelf is showing, so a print that loads later knows whether to appear. */
    private boolean shelfShown;
    private boolean memoryFound;
    /** Today's pick is done, found or not: without a photo the panel takes the width. */
    private boolean memoryChecked;
    /** Window y where the dock band starts, from MainActivity; 0 until laid out. */
    private int dockTop;
    private Bitmap memoryBitmap;
    /** config.xml top_menu: the print has the top right to itself (see OPEN_*). */
    private final boolean roomy;
    /** The note, hourly forecast and air quality left of the photo, and where the note comes from. */
    private final HomePanel panel;
    private final TelegramNotes telegram;
    private final boolean airOn;
    /** calendar_ics_urls; the calendars as last read, kept in memory only (they hold the owner's events). */
    private final java.util.List<String> calendarUrls;
    private final java.util.List<String> calendars = new java.util.ArrayList<>();
    private long calendarAt;
    private static final long CALENDAR_MS = 30 * 60_000L, CALENDAR_SOON_MS = 10 * 60_000L;
    private final NowPlaying nowPlaying;
    private final int reminderMinutes;
    private final boolean nightDim;
    private static final long NOTE_POLL_MS = 45_000L, PANEL_TICK_MS = 60_000L;
    private final Runnable panelTick = new Runnable() {
        @Override
        public void run() {
            panel.refresh();   // the hour moves on, "updated 5 minutes ago" ages
            main.postDelayed(this, PANEL_TICK_MS);
        }
    };

    HomeInfo(Activity activity) {
        this.activity = activity;
        Resources res = activity.getResources();
        line = activity.findViewById(R.id.homeInfo);
        memory = activity.findViewById(R.id.memory);
        memoryPhoto = activity.findViewById(R.id.memoryPhoto);
        memoryKicker = activity.findViewById(R.id.memoryKicker);
        memoryDate = activity.findViewById(R.id.memoryDate);
        View card = activity.findViewById(R.id.memoryCard);
        if (card != null) card.setClipToOutline(true);   // the photo takes the card's rounded corners
        prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        double lat = Double.NaN, lon = Double.NaN;
        try {
            lat = Double.parseDouble(res.getString(R.string.latitude).trim());
            lon = Double.parseDouble(res.getString(R.string.longitude).trim());
        } catch (NumberFormatException ignored) {
            // No place set: no sunrise, sunset or weather.
        }
        latitude = lat;
        longitude = lon;
        hasPlace = !Double.isNaN(lat) && !Double.isNaN(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180;
        lineOn = hasPlace && res.getBoolean(R.bool.weather) && line != null;
        // The launcher ships without network access; a build that adds INTERNET gets the weather, any other
        // build keeps the line to sunrise and sunset, computed here.
        weatherOn = lineOn && activity.checkSelfPermission(android.Manifest.permission.INTERNET)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        memoryOn = res.getBoolean(R.bool.on_this_day) && memory != null;
        roomy = res.getBoolean(R.bool.top_menu);
        if (memoryOn && roomy) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) memory.getLayoutParams();
            lp.topMargin = Math.round(OPEN_TOP_DP * res.getDisplayMetrics().density);
            memory.setLayoutParams(lp);
            memoryKicker.setVisibility(View.GONE);
            // Only "2 years ago" on the photo: it says when.
        }
        boolean net = activity.checkSelfPermission(android.Manifest.permission.INTERNET)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        boolean notesOn = res.getBoolean(R.bool.home_notes) && net;
        String token = res.getString(R.string.telegram_bot_token).trim();
        long owner = 0;
        try {
            owner = Long.parseLong(res.getString(R.string.telegram_owner_id).trim());
        } catch (NumberFormatException ignored) {
            // No owner: no sender is trusted, so no note is read.
        }
        telegram = notesOn && !token.isEmpty() && owner > 0
                ? new TelegramNotes(token, owner, activity.getSharedPreferences(TelegramNotes.PREFS, Activity.MODE_PRIVATE),
                        new java.io.File(activity.getExternalFilesDir(null), PhotoDream.DIR))
                : null;
        airOn = weatherOn && res.getBoolean(R.bool.air_quality);
        java.util.List<String> urls = new java.util.ArrayList<>();
        for (String u : res.getString(R.string.calendar_ics_urls).trim().split("\\s+")) {
            if (u.startsWith("https://")) urls.add(u);
        }
        calendarUrls = net ? urls : java.util.Collections.emptyList();
        boolean playingOn = res.getBoolean(R.bool.now_playing);
        panel = new HomePanel(activity, prefs, notesOn, weatherOn && res.getBoolean(R.bool.hourly_forecast), airOn,
                !calendarUrls.isEmpty() || telegram != null, playingOn);
        nowPlaying = playingOn ? new NowPlaying(activity, panel::setTrack) : null;
        reminderMinutes = Math.max(0, Math.min(120, res.getInteger(R.integer.reminder_minutes)));
        nightDim = res.getBoolean(R.bool.night_dim);
        // The panel ends where the photo starts: follow the photo's actual bounds whenever it is laid out anew.
        if (memory != null && panel.on()) {
            memory.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (l != ol || r != or) v.post(this::layoutPanel);
            });
        }
    }

    void start() {
        if (thread != null) return;
        thread = new HandlerThread("homeInfo");
        thread.start();
        Handler h = new Handler(thread.getLooper());
        bg = h;
        render();   // after the thread: render() schedules its next run only while started
        panel.refresh();
        if (panel.on()) main.postDelayed(panelTick, PANEL_TICK_MS);
        if (nowPlaying != null) nowPlaying.start();
        if (telegram != null) showAgenda(h);   // the Telegram events at once, before any calendar read
        if (!calendarUrls.isEmpty()) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    if (bg != h) return;
                    long wait = CALENDAR_MS;
                    if (System.currentTimeMillis() - calendarAt >= CALENDAR_SOON_MS) {
                        java.util.List<CalendarFeed.Event> next = readCalendars();
                        main.post(() -> showAgenda(h));
                        // Something within the hour: look again sooner, so a last-minute change shows.
                        if (!next.isEmpty() && java.time.Duration.between(java.time.ZonedDateTime.now(),
                                next.get(0).start).toMinutes() < 60) wait = CALENDAR_SOON_MS;
                    }
                    h.postDelayed(this, wait);
                }
            });
        }
        if (telegram != null) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    if (bg != h) return;
                    if (telegram.poll()) main.post(() -> showAgenda(h));
                    h.postDelayed(this, NOTE_POLL_MS);
                }
            });
        }
        if (weatherOn) {
            long age = System.currentTimeMillis() - prefs.getLong("at", 0);
            // A reading kept for another place, or from before the hourly forecast and air quality: fetch now.
            String place = String.format(Locale.ROOT, "%.2f,%.2f", latitude, longitude);
            if (!place.equals(prefs.getString("place", "")) || (airOn && !prefs.contains("aq_json"))) age = WEATHER_MS;
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (bg != h) return;
                    fetchWeather();
                    if (airOn) fetchAir();
                    main.post(HomeInfo.this::render);
                    main.post(panel::refresh);
                    h.postDelayed(this, WEATHER_MS);
                }
            }, Math.max(0, WEATHER_MS - age));
        }
        if (memoryOn) {
            // A new photo on a new day, after 30 minutes away from Home, or every two hours while it stays up.
            long now = System.currentTimeMillis(), at = prefs.getLong("memAt", 0), away = prefs.getLong("awayAt", now);
            boolean fresh = !LocalDate.now().equals(memoryDay) || now - away >= MEMORY_AWAY_MS || now - at >= MEMORY_EVERY_MS;
            if (fresh || !memoryFound) nextMemory(h);
            h.postDelayed(memoryTick, Math.max(1000, MEMORY_EVERY_MS - (fresh ? 0 : now - at)));
        }
        nightTick.run();
    }

    private static final long MEMORY_EVERY_MS = 2 * 60 * 60_000L, MEMORY_AWAY_MS = 30 * 60_000L;

    private final Runnable memoryTick = new Runnable() {
        @Override
        public void run() {
            Handler h = bg;
            if (h == null) return;
            nextMemory(h);
            h.postDelayed(this, MEMORY_EVERY_MS);
        }
    };

    private void nextMemory(Handler h) {
        LocalDate today = LocalDate.now();
        memoryDay = today;
        h.post(() -> chooseMemory(today));
    }

    void stop() {
        prefs.edit().putLong("awayAt", System.currentTimeMillis()).apply();
        main.removeCallbacks(nightTick);
        main.removeCallbacks(renderAgain);
        main.removeCallbacks(panelTick);
        if (nowPlaying != null) nowPlaying.stop();
        if (thread == null) return;
        bg.removeCallbacksAndMessages(null);
        thread.quit();
        thread = null;
        bg = null;
    }

    // ---- night dimming (config.xml night_dim) ---------------------------------------------------

    /** 23:00 to 06:00: the photo, the panel and the apps at NIGHT_ALPHA; the clock and the line under it stay. */
    private static final int NIGHT_FROM = 23, NIGHT_TO = 6;
    private static final float NIGHT_ALPHA = 0.6f;
    private static final long WAKE_MS = 5 * 60_000L;
    private long wokeAt;
    private boolean dimmed;

    private final Runnable nightTick = new Runnable() {
        @Override
        public void run() {
            applyNight(true);
            main.removeCallbacks(this);
            if (nightDim) main.postDelayed(this, 30_000L);
        }
    };

    /** MainActivity: a key was pressed on Home; full brightness for WAKE_MS. */
    void wake() {
        if (!nightDim) return;
        wokeAt = System.currentTimeMillis();
        applyNight(true);
    }

    private void applyNight(boolean animate) {
        if (!nightDim) return;
        int hour = LocalTime.now().getHour();
        boolean night = (hour >= NIGHT_FROM || hour < NIGHT_TO) && System.currentTimeMillis() - wokeAt > WAKE_MS;
        if (night == dimmed) return;
        dimmed = night;
        float a = night ? NIGHT_ALPHA : 1f;
        // The photo, the panel's parts and the apps; the panel and the hint keep their own alpha for show and hide.
        for (int id : new int[]{R.id.memoryCard, R.id.grid, R.id.moreHint, R.id.noteCard, R.id.hoursBlock, R.id.agenda}) {
            setNightAlpha(id, a);
        }
    }

    private void setNightAlpha(int id, float a) {
        View v = activity.findViewById(id);
        if (v == null) return;
        if (id == R.id.moreHint) {
            // The hint's own alpha shows and hides it; scale it only when shown.
            if (v.getAlpha() > 0f) v.animate().alpha(a).setDuration(600).start();
            return;
        }
        v.animate().alpha(a).setDuration(600).start();
    }

    /** MainActivity: where the dock band starts, in window px. The print grows into the room above it. */
    void setDockTop(int y) {
        if (y == dockTop || y <= 0) return;
        dockTop = y;
        layoutMemory();
        layoutPanel();
    }

    /** The panel fills the room left of the photo (or of where the photo is at its widest, before one shows). */
    private void layoutPanel() {
        if (!panel.on() || dockTop <= 0) return;
        android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        int photoLeft;
        if (memoryFound && memory.getWidth() > 0) photoLeft = memory.getLeft();
        else if (!memoryOn || memoryChecked) photoLeft = dm.widthPixels - Math.round(20 * dm.density);   // no photo today
        else photoLeft = Math.round(dm.widthPixels * (1 - MAX_W_FRACTION) - 56 * dm.density);
        panel.layout(line, dockTop, photoLeft);
    }

    /** The caption's fade spans the photo (a lone match_parent child would keep the last photo's width). */
    private void fitCaption(int width) {
        if (!roomy || width <= 0) return;
        ViewGroup.LayoutParams dp = memoryDate.getLayoutParams();
        dp.width = width;
        memoryDate.setLayoutParams(dp);
    }

    /** Roomy: the photo as large as the room between the top of the screen and the dock allows. */
    private void layoutMemory() {
        if (!roomy || memoryBitmap == null || memoryBitmap.isRecycled() || dockTop <= 0) return;
        android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        float d = dm.density;
        float aspect = (float) memoryBitmap.getWidth() / memoryBitmap.getHeight();
        float availH = dockTop - (OPEN_TOP_DP + CARD_EXTRA_DP + DOCK_GAP_DP) * d;
        float h = Math.min(availH, dm.widthPixels * MAX_W_FRACTION / aspect);
        if (h <= 0) return;
        ViewGroup.LayoutParams lp = memoryPhoto.getLayoutParams();
        lp.width = Math.round(h * aspect);
        lp.height = Math.round(h);
        memoryPhoto.setLayoutParams(lp);
        fitCaption(lp.width);
    }

    /**
     * top_menu opening (bar: where its buttons reach, window px) or closing (null): the print shrinks towards its
     * top right corner just enough to stay clear of the buttons, and grows back when they fold away.
     */
    void makeRoomFor(Rect bar, boolean animate) {
        if (memory == null || !memoryFound) return;
        float scale = 1f;
        if (bar != null && memory.getWidth() > 0) {
            float gap = MENU_GAP_DP * activity.getResources().getDisplayMetrics().density;
            int[] at = new int[2];
            memory.getLocationInWindow(at);
            // Unscaled bounds: getLocationInWindow follows the current scale, the layout does not.
            float right = at[0] + memory.getWidth() * memory.getScaleX();
            float left = right - memory.getWidth();
            if (bar.right + gap > left && bar.top < at[1] + memory.getHeight()) {
                scale = Math.max(0.3f, (right - bar.right - gap) / memory.getWidth());
            }
        }
        memory.setPivotX(memory.getWidth());
        memory.setPivotY(0f);
        memory.animate().cancel();
        if (animate) {
            memory.animate().scaleX(scale).scaleY(scale).alpha(shelfShown ? 1f : 0f).setDuration(240)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        } else {
            memory.setScaleX(scale);
            memory.setScaleY(scale);
        }
    }

    /** The shelf shows or hides: the print goes with it. */
    void showWithShelf(boolean shown, boolean animate) {
        shelfShown = shown;
        panel.show(shown, animate);
        if (memory == null || !memoryFound) return;
        float alpha = shown ? 1f : 0f;
        memory.animate().cancel();
        if (animate) memory.animate().alpha(alpha).setDuration(shown ? 260 : 160).start();
        else memory.setAlpha(alpha);
    }

    // ---- the line under the clock ----------------------------------------------------------------

    /**
     * "[icon] 26° Clear   [up] 31° [down] 19°   [horizon] Sunset 17:47": the weather, then only the next sun
     * event (sunset by day, the coming sunrise after it). Redrawn at that event, so the icon turns to night and
     * the time moves on without waiting for the next fetch.
     */
    private void render() {
        if (!lineOn) return;
        main.removeCallbacks(renderAgain);
        long[] next = new long[1];
        CharSequence text = compose(activity, line, weatherOn, latitude, longitude, next);
        if (next[0] > 0 && thread != null) main.postDelayed(renderAgain, next[0]);
        line.setText(text);
        line.setVisibility(text.length() > 0 ? View.VISIBLE : View.GONE);
    }

    private final Runnable renderAgain = this::render;

    /**
     * The same line for another screen (the Photos screensaver), from the last reading Home kept: nothing is
     * fetched. Empty without a place, or with config.xml weather off.
     */
    static CharSequence lineFor(Context c, TextView style) {
        Resources res = c.getResources();
        double lat, lon;
        try {
            lat = Double.parseDouble(res.getString(R.string.latitude).trim());
            lon = Double.parseDouble(res.getString(R.string.longitude).trim());
        } catch (NumberFormatException e) {
            return "";
        }
        if (!res.getBoolean(R.bool.weather)) return "";
        boolean net = c.checkSelfPermission(android.Manifest.permission.INTERNET)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        return compose(c, style, net, lat, lon, new long[1]);
    }

    /** Builds the line; next[0] gets the milliseconds until the next sunrise or sunset, when it should change. */
    private static CharSequence compose(Context c, TextView style, boolean weather, double latitude, double longitude,
                                        long[] next) {
        SharedPreferences prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        LocalTime nowTime = LocalTime.now(zone);
        int nowMin = nowTime.getHour() * 60 + nowTime.getMinute();
        int[] sun = Sun.riseAndSet(today, latitude, longitude, zone);
        boolean day = sun == null || (nowMin >= sun[0] && nowMin < sun[1]);
        SpannableStringBuilder text = new SpannableStringBuilder();
        String json = prefs.getString("json", null);
        if (weather && json != null && System.currentTimeMillis() - prefs.getLong("at", 0) < STALE_MS) {
            try {
                JSONObject o = new JSONObject(json);
                JSONObject now = o.getJSONObject("current");
                JSONObject daily = o.getJSONObject("daily");
                int code = now.getInt("weather_code");
                icon(c, style, text, conditionIcon(code, day));
                text.append(c.getString(R.string.weather_now,
                        Math.round(now.getDouble("temperature_2m")), condition(c, code)));
                text.append(SEP);
                icon(c, style, text, R.drawable.wx_high);
                text.append(c.getString(R.string.degrees,
                        Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)))).append("  ");
                icon(c, style, text, R.drawable.wx_low);
                text.append(c.getString(R.string.degrees,
                        Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0))));
            } catch (Exception ignored) {
                // A reading we cannot read is left out; the sun time still shows.
            }
        }
        if (sun != null) {
            if (text.length() > 0) text.append(SEP);
            icon(c, style, text, R.drawable.wx_twilight);
            int untilMin;
            if (nowMin < sun[0]) {
                text.append(c.getString(R.string.sunrise, clock(c, sun[0])));
                untilMin = sun[0] - nowMin;
            } else if (nowMin < sun[1]) {
                text.append(c.getString(R.string.sunset, clock(c, sun[1])));
                untilMin = sun[1] - nowMin;
            } else {
                int[] tomorrow = Sun.riseAndSet(today.plusDays(1), latitude, longitude, zone);
                int rise = tomorrow != null ? tomorrow[0] : sun[0];
                text.append(c.getString(R.string.sunrise, clock(c, rise)));
                untilMin = 1440 - nowMin + rise;
            }
            // Just after the event, on the minute; a day change is caught the same way, at the next sunrise.
            next[0] = Math.max(1000L, untilMin * 60_000L - nowTime.getSecond() * 1000L + 1000L);
        }
        return text;
    }

    /** An icon the size of the line's capitals, in the line's colour, then a thin space. */
    private static void icon(Context c, TextView style, SpannableStringBuilder text, int drawable) {
        Drawable d = c.getDrawable(drawable);
        if (d == null) return;
        d = d.mutate();
        int size = Math.round(style.getTextSize() * 1.2f);
        d.setBounds(0, 0, size, size);
        d.setTint(style.getCurrentTextColor());
        int start = text.length();
        text.append('\uFFFC');
        int align = Build.VERSION.SDK_INT >= 29 ? ImageSpan.ALIGN_CENTER : ImageSpan.ALIGN_BASELINE;
        text.setSpan(new ImageSpan(d, align), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.append('\u2009');
    }

    /** The picture for a WMO code, with the moon in place of the sun at night. */
    static int conditionIcon(int code, boolean day) {
        if (code == 0) return day ? R.drawable.wx_clear_day : R.drawable.wx_clear_night;
        if (code == 1 || code == 2) return day ? R.drawable.wx_partly_cloudy_day : R.drawable.wx_partly_cloudy_night;
        if (code == 45 || code == 48) return R.drawable.wx_fog;
        if (code >= 51 && code <= 57) return R.drawable.wx_drizzle;
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return R.drawable.wx_rain;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.drawable.wx_snow;
        if (code >= 95) return R.drawable.wx_thunder;
        return R.drawable.wx_cloudy;
    }

    /** Sunrise and sunset at config.xml's place on date, minutes of the day, or null without a place. */
    static int[] sunFor(Context c, LocalDate date) {
        try {
            double lat = Double.parseDouble(c.getString(R.string.latitude).trim());
            double lon = Double.parseDouble(c.getString(R.string.longitude).trim());
            return Sun.riseAndSet(date, lat, lon, ZoneId.systemDefault());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String clock(Context c, int minutes) {
        LocalTime t = LocalTime.of((minutes / 60) % 24, minutes % 60);
        boolean h24 = DateFormat.is24HourFormat(c);
        return t.format(DateTimeFormatter.ofPattern(h24 ? "H:mm" : "h:mm a", Locale.getDefault()));
    }

    /** WMO weather interpretation codes, as Open-Meteo reports them, in a word or two. */
    private static String condition(Context c, int code) {
        int id;
        if (code == 0) id = R.string.weather_clear;
        else if (code == 1) id = R.string.weather_mostly_clear;
        else if (code == 2) id = R.string.weather_partly_cloudy;
        else if (code == 3) id = R.string.weather_cloudy;
        else if (code == 45 || code == 48) id = R.string.weather_fog;
        else if (code >= 51 && code <= 57) id = R.string.weather_drizzle;
        else if (code >= 61 && code <= 67) id = R.string.weather_rain;
        else if (code >= 71 && code <= 77) id = R.string.weather_snow;
        else if (code >= 80 && code <= 82) id = R.string.weather_showers;
        else if (code == 85 || code == 86) id = R.string.weather_snow;
        else if (code >= 95) id = R.string.weather_thunder;
        else id = R.string.weather_cloudy;
        return c.getString(id);
    }

    /**
     * Background thread: the last day and today's hourly PM2.5 and PM10 for the place (Open-Meteo's air-quality
     * model), kept in prefs for HomePanel's 24-hour NAQI. A failure keeps the last reading.
     */
    private void fetchAir() {
        HttpURLConnection c = null;
        try {
            URL url = new URL(String.format(Locale.ROOT,
                    "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=%.2f&longitude=%.2f"
                            + "&hourly=pm2_5,pm10&past_days=1&forecast_days=1&timezone=auto", latitude, longitude));
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            if (c.getResponseCode() != 200) return;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 128 * 1024) out.write(buf, 0, n);
            }
            String json = out.toString("UTF-8");
            new JSONObject(json).getJSONObject("hourly").getJSONArray("pm2_5");   // only a reading we can use
            prefs.edit().putString("aq_json", json).apply();
        } catch (Exception ignored) {
            // Offline or a bad answer: the last reading stays.
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Background thread: every calendar anew (one that fails keeps its last copy), then the next events. */
    private java.util.List<CalendarFeed.Event> readCalendars() {
        while (calendars.size() < calendarUrls.size()) calendars.add("");
        for (int i = 0; i < calendarUrls.size(); i++) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(calendarUrls.get(i)).openConnection();
                c.setConnectTimeout(TIMEOUT_MS);
                c.setReadTimeout(TIMEOUT_MS);
                if (c.getResponseCode() != 200) continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0 && out.size() < 4 * 1024 * 1024) out.write(buf, 0, n);
                }
                String ics = out.toString("UTF-8");
                if (ics.startsWith("BEGIN:VCALENDAR")) calendars.set(i, ics);
            } catch (Exception ignored) {
                // Offline or a bad answer: the last copy stays.
            } finally {
                if (c != null) c.disconnect();
            }
        }
        calendarAt = System.currentTimeMillis();
        calendarEvents = CalendarFeed.upcoming(calendars, java.time.ZonedDateTime.now(), 7, 10);
        return calendarEvents;
    }

    /** The Google Calendar events last read and the ones set from Telegram, soonest first. */
    private volatile java.util.List<CalendarFeed.Event> calendarEvents = java.util.Collections.emptyList();

    /** Main thread: the agenda to the panel, then on bg the weather where its trips go. */
    private void showAgenda(Handler h) {
        java.util.List<CalendarFeed.Event> list = agenda();
        panel.setEvents(list);
        if (weatherOn && bg == h) h.post(() -> {
            java.util.Map<String, int[]> found = destinations(list);
            if (!found.isEmpty()) main.post(() -> panel.setDestinations(found));
        });
    }

    /** "Travel to Dehradun", "Trip to Goa", "Lunch @Mussoorie": the place an event names, or null. */
    static String place(String title) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("@\\s*([\\p{L}][\\p{L} .'-]{1,40})").matcher(title);
        if (m.find()) return m.group(1).trim();
        m = java.util.regex.Pattern.compile("\\bto\\s+(\\p{Lu}[\\p{L}.'-]+(?:\\s+\\p{Lu}[\\p{L}.'-]+){0,2})").matcher(title);
        return m.find() ? m.group(1).trim() : null;
    }

    /**
     * Background thread: the day's weather where the next events go, keyed by HomePanel.eventKey: {code, max, min}.
     * Places are looked up once (Open-Meteo geocoding) and forecasts kept for three hours.
     */
    private java.util.Map<String, int[]> destinations(java.util.List<CalendarFeed.Event> events) {
        java.util.Map<String, int[]> out = new java.util.HashMap<>();
        int looked = 0;
        for (CalendarFeed.Event e : events) {
            if (looked == 3) break;
            String place = place(e.title);
            if (place == null) continue;
            looked++;
            try {
                String geo = prefs.getString("geo:" + place.toLowerCase(Locale.ROOT), null);
                if (geo == null) {
                    JSONObject r = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&name="
                            + java.net.URLEncoder.encode(place, "UTF-8")));
                    JSONArray results = r.optJSONArray("results");
                    if (results == null || results.length() == 0) continue;
                    JSONObject g = results.getJSONObject(0);
                    geo = String.format(Locale.ROOT, "%.3f,%.3f", g.getDouble("latitude"), g.getDouble("longitude"));
                    prefs.edit().putString("geo:" + place.toLowerCase(Locale.ROOT), geo).apply();
                }
                String day = e.start.toLocalDate().toString();
                String key = "dw:" + geo + ":" + day;
                String cached = prefs.getString(key, null);
                long at = prefs.getLong(key + ":at", 0);
                if (cached == null || System.currentTimeMillis() - at > 3 * 60 * 60_000L) {
                    String[] ll = geo.split(",");
                    JSONObject d = new JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=" + ll[0]
                            + "&longitude=" + ll[1] + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
                            + "&timezone=auto&start_date=" + day + "&end_date=" + day)).getJSONObject("daily");
                    cached = d.getJSONArray("weather_code").getInt(0) + "," + Math.round(d.getJSONArray("temperature_2m_max")
                            .getDouble(0)) + "," + Math.round(d.getJSONArray("temperature_2m_min").getDouble(0));
                    prefs.edit().putString(key, cached).putLong(key + ":at", System.currentTimeMillis()).apply();
                }
                String[] v = cached.split(",");
                out.put(HomePanel.eventKey(e), new int[]{Integer.parseInt(v[0]), Integer.parseInt(v[1]), Integer.parseInt(v[2])});
            } catch (Exception ignored) {
                // Unknown place, offline, or a day past the forecast: no weather on that line.
            }
        }
        return out;
    }

    /** Background thread: a small GET as text, or an exception. */
    private static String get(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 64 * 1024) out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private java.util.List<CalendarFeed.Event> agenda() {
        java.util.List<CalendarFeed.Event> all = merged();
        if (reminderMinutes > 0) Reminders.schedule(activity, all, reminderMinutes);
        return all;
    }

    private java.util.List<CalendarFeed.Event> merged() {
        java.util.List<CalendarFeed.Event> all = new java.util.ArrayList<>(calendarEvents);
        if (telegram != null) {
            java.time.ZonedDateTime now = java.time.ZonedDateTime.now();
            for (CalendarFeed.Event e : TelegramNotes.events(
                    activity.getSharedPreferences(TelegramNotes.PREFS, Activity.MODE_PRIVATE), now)) {
                if (!e.start.isAfter(now.plusDays(7))) all.add(e);
            }
        }
        all.sort((a, b) -> a.start.compareTo(b.start));
        return all;
    }

    /** Background thread: one request for now and today, kept in prefs. A failure keeps the last reading. */
    private void fetchWeather() {
        HttpURLConnection c = null;
        try {
            URL url = new URL(String.format(Locale.ROOT,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f"
                            + "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min"
                            + "&hourly=temperature_2m,weather_code&forecast_hours=8"
                            + "&timezone=auto&forecast_days=1", latitude, longitude));
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            if (c.getResponseCode() != 200) return;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 64 * 1024) out.write(buf, 0, n);
            }
            String json = out.toString("UTF-8");
            new JSONObject(json).getJSONObject("current").getDouble("temperature_2m");   // only a reading we can use
            prefs.edit().putString("json", json).putLong("at", System.currentTimeMillis())
                    .putString("place", String.format(Locale.ROOT, "%.2f,%.2f", latitude, longitude)).apply();
        } catch (Exception ignored) {
            // Offline or a bad answer: the last reading stays until it is three hours old.
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---- on this day -----------------------------------------------------------------------------

    /** Background thread: picks today's print and hands it to the main thread. */
    /**
     * Background thread: the next photo. Best matches first: taken on this day in an earlier year, then this week,
     * then this month; then every other photo at least a month old, in an order fixed for the day. Each is shown once
     * a day (the photos shown today are kept in prefs); once all have been, the round starts again.
     */
    private void chooseMemory(LocalDate today) {
        File[] files = new File(activity.getExternalFilesDir(null), PhotoDream.DIR).listFiles();
        List<File> day = new ArrayList<>(), week = new ArrayList<>(), month = new ArrayList<>(), rest = new ArrayList<>();
        if (files != null) {
            for (File f : files) {
                LocalDate taken = taken(f);
                if (taken == null || taken.isAfter(today.minusMonths(1))) continue;   // undated, or too recent to be a memory
                boolean earlierYear = taken.getYear() < today.getYear();
                long apart = earlierYear ? Math.abs(ChronoUnit.DAYS.between(today,
                        taken.withDayOfMonth(Math.min(taken.getDayOfMonth(), 28)).withYear(today.getYear())
                                .withDayOfMonth(Math.min(taken.getDayOfMonth(), today.withMonth(taken.getMonthValue())
                                        .lengthOfMonth())))) : Long.MAX_VALUE;
                if (earlierYear && dayKnown(f) && taken.getMonthValue() == today.getMonthValue()
                        && taken.getDayOfMonth() == today.getDayOfMonth()) day.add(f);
                else if (earlierYear && dayKnown(f) && apart <= 3) week.add(f);
                else if (earlierYear && taken.getMonthValue() == today.getMonthValue()) month.add(f);
                else rest.add(f);
            }
        }
        java.util.Comparator<File> byName = (a, b) -> a.getName().compareTo(b.getName());
        day.sort(byName);
        week.sort(byName);
        month.sort(byName);
        rest.sort(byName);
        java.util.Collections.shuffle(rest, new Random(today.toEpochDay()));
        List<File> order = new ArrayList<>(day);
        order.addAll(week);
        order.addAll(month);
        order.addAll(rest);
        if (order.isEmpty()) {
            main.post(() -> setMemory(null, null, null, 0, 0));
            return;
        }
        String shownKey = "memShown:" + today;
        String shown = prefs.getString(shownKey, "|");
        File pick = null;
        for (File f : order) {
            if (!shown.contains("|" + f.getName() + "|")) {
                pick = f;
                break;
            }
        }
        if (pick == null) {   // every photo shown today: start the round again
            shown = "|";
            pick = order.get(0);
        }
        prefs.edit().putString(shownKey, shown + pick.getName() + "|").putLong("memAt", System.currentTimeMillis()).apply();
        int kicker = day.contains(pick) ? R.string.memory_on_this_day : week.contains(pick) ? R.string.memory_this_week
                : R.string.memory_this_month;
        LocalDate taken = taken(pick);
        java.time.Period gap = java.time.Period.between(taken, today);
        String ago = gap.getYears() >= 1
                ? activity.getResources().getQuantityString(R.plurals.memory_years_ago, gap.getYears(), gap.getYears())
                : activity.getResources().getQuantityString(R.plurals.memory_months_ago, gap.getMonths(), gap.getMonths());
        String head = activity.getString(kicker, ago);
        String date = new DateFormatSymbols().getShortMonths()[taken.getMonthValue() - 1] + " " + taken.getYear();
        float density = activity.getResources().getDisplayMetrics().density;
        int[] size = PhotoDream.uprightSize(pick);
        if (size == null) return;
        int w, h;
        if (size[1] > size[0]) {
            h = Math.round((roomy ? OPEN_PORTRAIT_H_DP : MEMORY_PORTRAIT_H_DP) * density);
            w = Math.round(h * (float) size[0] / size[1]);
        } else {
            w = Math.round((roomy ? OPEN_LANDSCAPE_W_DP : MEMORY_LANDSCAPE_W_DP) * density);
            h = Math.round(w * (float) size[1] / size[0]);
        }
        // Roomy: the print sits between the top of the screen and the dock, so no photo is taller than a portrait one.
        // Decoded large enough for the most room it can get; layoutMemory() sizes it once the dock's place is known.
        int maxH = Math.round(OPEN_PORTRAIT_H_DP * density);
        if (roomy && h > maxH) {
            w = Math.round(w * (float) maxH / h);
            h = maxH;
        }
        int decodeW = w, decodeH = h;
        if (roomy) {
            android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            float k = Math.min(dm.widthPixels * MAX_W_FRACTION / w, dm.heightPixels * 0.7f / h);
            decodeW = Math.round(w * k);
            decodeH = Math.round(h * k);
        }
        Bitmap bitmap = PhotoDream.decode(pick, decodeW, decodeH);
        if (bitmap == null) return;
        // Roomy: no line above the print, so the strip says how long ago instead of the month and year.
        String strip = roomy ? ago : date;
        int photoW = w, photoH = h;
        main.post(() -> setMemory(bitmap, head, strip, photoW, photoH));
    }

    private void setMemory(Bitmap bitmap, String head, String date, int w, int h) {
        Drawable old = memoryPhoto.getDrawable();
        memoryPhoto.setImageDrawable(null);
        if (old instanceof BitmapDrawable && ((BitmapDrawable) old).getBitmap() != null) {
            ((BitmapDrawable) old).getBitmap().recycle();
        }
        memoryFound = bitmap != null;
        memoryChecked = true;
        if (bitmap == null) {
            memory.setVisibility(View.GONE);
            layoutPanel();
            return;
        }
        // A photo already up: cross-fade to the new one rather than cutting.
        if (old != null && memory.getAlpha() > 0f) {
            memoryPhoto.setAlpha(0.15f);
            memoryPhoto.animate().alpha(1f).setDuration(700).start();
        }
        memoryPhoto.setImageBitmap(bitmap);
        memoryBitmap = bitmap;
        ViewGroup.LayoutParams lp = memoryPhoto.getLayoutParams();
        lp.width = w;
        lp.height = h;
        memoryPhoto.setLayoutParams(lp);
        fitCaption(lp.width);
        layoutMemory();
        memoryKicker.setText(head);
        memoryDate.setText(date);
        memory.setVisibility(View.VISIBLE);
        memory.setAlpha(shelfShown ? 1f : 0f);
    }

    /** Whether the name carries the day too, not only the month (names from an older tools/photos.sh). */
    private static boolean dayKnown(File file) {
        Matcher m = NAME_DATE.matcher(file.getName());
        return m.find() && m.group(3) != null;
    }

    /**
     * When the photo was taken, from the name tools/photos.sh gave it (the 1st when only the month is known);
     * null when the name carries no date.
     */
    static LocalDate taken(File file) {
        Matcher m = NAME_DATE.matcher(file.getName());
        if (!m.find()) return null;
        try {
            int day = m.group(3) != null ? Integer.parseInt(m.group(3)) : 1;
            return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), day);
        } catch (Exception e) {
            return null;
        }
    }
}
