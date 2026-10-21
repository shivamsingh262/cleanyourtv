package com.gathin.tvhome;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
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
    private static final int OPEN_LANDSCAPE_W_DP = 190, OPEN_PORTRAIT_H_DP = 140, OPEN_TOP_DP = 26;
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
    /** config.xml top_menu: the print has the top right to itself (see OPEN_*). */
    private final boolean roomy;

    HomeInfo(Activity activity) {
        this.activity = activity;
        Resources res = activity.getResources();
        line = activity.findViewById(R.id.homeInfo);
        memory = activity.findViewById(R.id.memory);
        memoryPhoto = activity.findViewById(R.id.memoryPhoto);
        memoryKicker = activity.findViewById(R.id.memoryKicker);
        memoryDate = activity.findViewById(R.id.memoryDate);
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
            // "Oct 2024" and "2 years ago" on two lines of the strip: one line is wider than a portrait print.
            ViewGroup.LayoutParams sp = memoryDate.getLayoutParams();
            sp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            memoryDate.setLayoutParams(sp);
            memoryDate.setMaxLines(2);
            memoryDate.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
            memoryDate.setLineSpacing(0, 0.9f);
            int pad = Math.round(5 * res.getDisplayMetrics().density);
            memoryDate.setPadding(0, pad, 0, pad + pad);
        }
    }

    void start() {
        if (thread != null) return;
        thread = new HandlerThread("homeInfo");
        thread.start();
        Handler h = new Handler(thread.getLooper());
        bg = h;
        render();   // after the thread: render() schedules its next run only while started
        if (weatherOn) {
            long age = System.currentTimeMillis() - prefs.getLong("at", 0);
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (bg != h) return;
                    fetchWeather();
                    main.post(HomeInfo.this::render);
                    h.postDelayed(this, WEATHER_MS);
                }
            }, Math.max(0, WEATHER_MS - age));
        }
        if (memoryOn && !LocalDate.now().equals(memoryDay)) {
            LocalDate today = LocalDate.now();
            memoryDay = today;
            h.post(() -> chooseMemory(today));
        }
    }

    void stop() {
        main.removeCallbacks(renderAgain);
        if (thread == null) return;
        bg.removeCallbacksAndMessages(null);
        thread.quit();
        thread = null;
        bg = null;
    }

    /** The shelf shows or hides: the print goes with it. */
    void showWithShelf(boolean shown, boolean animate) {
        shelfShown = shown;
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
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        LocalTime nowTime = LocalTime.now(zone);
        int nowMin = nowTime.getHour() * 60 + nowTime.getMinute();
        int[] sun = Sun.riseAndSet(today, latitude, longitude, zone);
        boolean day = sun == null || (nowMin >= sun[0] && nowMin < sun[1]);
        SpannableStringBuilder text = new SpannableStringBuilder();
        String json = prefs.getString("json", null);
        if (weatherOn && json != null && System.currentTimeMillis() - prefs.getLong("at", 0) < STALE_MS) {
            try {
                JSONObject o = new JSONObject(json);
                JSONObject now = o.getJSONObject("current");
                JSONObject daily = o.getJSONObject("daily");
                int code = now.getInt("weather_code");
                icon(text, conditionIcon(code, day));
                text.append(activity.getString(R.string.weather_now,
                        Math.round(now.getDouble("temperature_2m")), condition(code)));
                text.append(SEP);
                icon(text, R.drawable.wx_high);
                text.append(activity.getString(R.string.degrees,
                        Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)))).append("  ");
                icon(text, R.drawable.wx_low);
                text.append(activity.getString(R.string.degrees,
                        Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0))));
            } catch (Exception ignored) {
                // A reading we cannot read is left out; the sun time still shows.
            }
        }
        if (sun != null) {
            if (text.length() > 0) text.append(SEP);
            icon(text, R.drawable.wx_twilight);
            int untilMin;
            if (nowMin < sun[0]) {
                text.append(activity.getString(R.string.sunrise, clock(sun[0])));
                untilMin = sun[0] - nowMin;
            } else if (nowMin < sun[1]) {
                text.append(activity.getString(R.string.sunset, clock(sun[1])));
                untilMin = sun[1] - nowMin;
            } else {
                int[] tomorrow = Sun.riseAndSet(today.plusDays(1), latitude, longitude, zone);
                int rise = tomorrow != null ? tomorrow[0] : sun[0];
                text.append(activity.getString(R.string.sunrise, clock(rise)));
                untilMin = 1440 - nowMin + rise;
            }
            // Just after the event, on the minute; a day change is caught the same way, at the next sunrise.
            long ms = untilMin * 60_000L - nowTime.getSecond() * 1000L + 1000L;
            if (thread != null) main.postDelayed(renderAgain, Math.max(1000L, ms));
        }
        line.setText(text);
        line.setVisibility(text.length() > 0 ? View.VISIBLE : View.GONE);
    }

    private final Runnable renderAgain = this::render;

    /** An icon the size of the line's capitals, in the line's colour, then a thin space. */
    private void icon(SpannableStringBuilder text, int drawable) {
        Drawable d = activity.getDrawable(drawable);
        if (d == null) return;
        d = d.mutate();
        int size = Math.round(line.getTextSize() * 1.2f);
        d.setBounds(0, 0, size, size);
        d.setTint(line.getCurrentTextColor());
        int start = text.length();
        text.append('￼');
        int align = Build.VERSION.SDK_INT >= 29 ? ImageSpan.ALIGN_CENTER : ImageSpan.ALIGN_BASELINE;
        text.setSpan(new ImageSpan(d, align), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.append(' ');
    }

    /** The picture for a WMO code, with the moon in place of the sun at night. */
    private static int conditionIcon(int code, boolean day) {
        if (code == 0) return day ? R.drawable.wx_clear_day : R.drawable.wx_clear_night;
        if (code == 1 || code == 2) return day ? R.drawable.wx_partly_cloudy_day : R.drawable.wx_partly_cloudy_night;
        if (code == 45 || code == 48) return R.drawable.wx_fog;
        if (code >= 51 && code <= 57) return R.drawable.wx_drizzle;
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return R.drawable.wx_rain;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.drawable.wx_snow;
        if (code >= 95) return R.drawable.wx_thunder;
        return R.drawable.wx_cloudy;
    }

    private String clock(int minutes) {
        LocalTime t = LocalTime.of((minutes / 60) % 24, minutes % 60);
        boolean h24 = DateFormat.is24HourFormat(activity);
        return t.format(DateTimeFormatter.ofPattern(h24 ? "H:mm" : "h:mm a", Locale.getDefault()));
    }

    /** WMO weather interpretation codes, as Open-Meteo reports them, in a word or two. */
    private String condition(int code) {
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
        return activity.getString(id);
    }

    /** Background thread: one request for now and today, kept in prefs. A failure keeps the last reading. */
    private void fetchWeather() {
        HttpURLConnection c = null;
        try {
            URL url = new URL(String.format(Locale.ROOT,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f"
                            + "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min"
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
            prefs.edit().putString("json", json).putLong("at", System.currentTimeMillis()).apply();
        } catch (Exception ignored) {
            // Offline or a bad answer: the last reading stays until it is three hours old.
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---- on this day -----------------------------------------------------------------------------

    /** Background thread: picks today's print and hands it to the main thread. */
    private void chooseMemory(LocalDate today) {
        File[] files = new File(activity.getExternalFilesDir(null), PhotoDream.DIR).listFiles();
        List<File> day = new ArrayList<>(), week = new ArrayList<>(), month = new ArrayList<>();
        List<LocalDate> dates = new ArrayList<>();
        if (files != null) {
            for (File f : files) {
                LocalDate taken = taken(f);
                if (taken == null || taken.getYear() >= today.getYear()) continue;
                LocalDate sameYear;
                try {
                    sameYear = taken.withYear(today.getYear());
                } catch (Exception e) {
                    continue;
                }
                long apart = Math.abs(ChronoUnit.DAYS.between(today, sameYear));
                if (!dayKnown(f)) {
                    if (taken.getMonthValue() == today.getMonthValue()) month.add(f);   // a month-only name
                } else if (taken.getMonthValue() == today.getMonthValue()
                        && taken.getDayOfMonth() == today.getDayOfMonth()) {
                    day.add(f);
                } else if (apart <= 3) {
                    week.add(f);
                } else if (taken.getMonthValue() == today.getMonthValue()) {
                    month.add(f);
                }
            }
        }
        List<File> pool = !day.isEmpty() ? day : !week.isEmpty() ? week : month;
        int kicker = !day.isEmpty() ? R.string.memory_on_this_day
                : !week.isEmpty() ? R.string.memory_this_week : R.string.memory_this_month;
        if (pool.isEmpty()) {
            main.post(() -> setMemory(null, null, null, 0, 0));
            return;
        }
        pool.sort((a, b) -> a.getName().compareTo(b.getName()));
        File pick = pool.get(new Random(today.toEpochDay()).nextInt(pool.size()));   // the same one all day
        LocalDate taken = taken(pick);
        int years = today.getYear() - taken.getYear();
        String ago = activity.getResources().getQuantityString(R.plurals.memory_years_ago, years, years);
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
        int maxH = Math.round(OPEN_PORTRAIT_H_DP * density);
        if (roomy && h > maxH) {
            w = Math.round(w * (float) maxH / h);
            h = maxH;
        }
        Bitmap bitmap = PhotoDream.decode(pick, w, h);
        if (bitmap == null) return;
        // Roomy: no line above the print, so "how long ago" goes under the date in the strip.
        String strip = roomy ? date + "\n" + ago : date;
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
        if (bitmap == null) {
            memory.setVisibility(View.GONE);
            return;
        }
        memoryPhoto.setImageBitmap(bitmap);
        ViewGroup.LayoutParams lp = memoryPhoto.getLayoutParams();
        lp.width = w;
        lp.height = h;
        memoryPhoto.setLayoutParams(lp);
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
