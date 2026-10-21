package com.gathin.tvhome;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The space left of the On this day photo (HomeInfo): the owner's note (TelegramNotes) on a glass card, and under it
 * the next hours' forecast with the air quality at the end of the row, on India's NAQI scale from the last 24 hours
 * of PM2.5 and PM10 as CPCB reckons it. Everything comes from what HomeInfo keeps in prefs; nothing is fetched here.
 * Shown and hidden with the shelf, and laid out between the line under the clock, the photo and the dock.
 */
final class HomePanel {

    private static final int HOURS = 6;
    private static final int LEFT_DP = 60, LINE_GAP_DP = 20, PHOTO_GAP_DP = 36, DOCK_GAP_DP = 20;
    /** CPCB's PM2.5 and PM10 breakpoints (24-hour average, ug/m3) against the index band each one opens. */
    private static final double[] PM25 = {0, 30, 60, 90, 120, 250, 380};
    private static final double[] PM10 = {0, 50, 100, 250, 350, 430, 510};
    private static final int[] INDEX = {0, 50, 100, 200, 300, 400, 500};
    private static final int[] AQI_COLORS = {0xFF4CAF50, 0xFF9CCC65, 0xFFFFD54F, 0xFFFF9800, 0xFFF44336, 0xFFB71C1C};
    private static final int[] AQI_NAMES = {R.string.aqi_good, R.string.aqi_satisfactory, R.string.aqi_moderate,
            R.string.aqi_poor, R.string.aqi_very_poor, R.string.aqi_severe};

    private final Activity activity;
    private final SharedPreferences weather, notes;
    private final boolean notesOn, hoursOn, airOn;
    private final View panel, noteCard, aqiBadge, aqiDot, hoursBlock;
    private final TextView noteText, noteMeta, aqiValue, aqiLabel;
    private final LinearLayout hoursRow;
    private final float density;
    private boolean shown;

    HomePanel(Activity activity, SharedPreferences weather, boolean notesOn, boolean hoursOn, boolean airOn) {
        this.activity = activity;
        this.weather = weather;
        this.notes = activity.getSharedPreferences(TelegramNotes.PREFS, Activity.MODE_PRIVATE);
        panel = activity.findViewById(R.id.infoPanel);
        this.notesOn = notesOn && panel != null;
        this.hoursOn = hoursOn && panel != null;
        this.airOn = airOn && panel != null;
        noteCard = activity.findViewById(R.id.noteCard);
        noteText = activity.findViewById(R.id.noteText);
        noteMeta = activity.findViewById(R.id.noteMeta);
        hoursBlock = activity.findViewById(R.id.hoursBlock);
        hoursRow = activity.findViewById(R.id.hoursRow);
        aqiBadge = activity.findViewById(R.id.aqiBadge);
        aqiDot = activity.findViewById(R.id.aqiDot);
        aqiValue = activity.findViewById(R.id.aqiValue);
        aqiLabel = activity.findViewById(R.id.aqiLabel);
        density = activity.getResources().getDisplayMetrics().density;
        if (on()) panel.setVisibility(View.VISIBLE);
    }

    boolean on() {
        return notesOn || hoursOn || airOn;
    }

    /** Fills the panel from prefs; cheap, called whenever the note, the readings or the hour change. */
    void refresh() {
        if (!on()) return;
        noteCard.setVisibility(notesOn ? View.VISIBLE : View.GONE);
        if (notesOn) {
            String text = notes.getString(TelegramNotes.KEY_TEXT, "");
            long at = notes.getLong(TelegramNotes.KEY_AT, 0);
            if (text.isEmpty()) {
                noteText.setText(R.string.note_empty);
                noteText.setAlpha(0.55f);
                noteMeta.setVisibility(View.GONE);
            } else {
                noteText.setText(text);
                noteText.setAlpha(1f);
                noteMeta.setVisibility(View.VISIBLE);
                noteMeta.setText(activity.getString(R.string.note_updated, DateUtils.getRelativeTimeSpanString(
                        at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)));
            }
        }
        boolean hours = hoursOn && fillHours();
        boolean air = airOn && fillAir();
        hoursRow.setVisibility(hours ? View.VISIBLE : View.GONE);
        aqiBadge.setVisibility(air ? View.VISIBLE : View.GONE);
        hoursBlock.setVisibility(hours || air ? View.VISIBLE : View.GONE);
    }

    /** The next HOURS hours from the forecast HomeInfo kept: time, icon, temperature. */
    private boolean fillHours() {
        hoursRow.removeAllViews();
        try {
            JSONObject hourly = new JSONObject(weather.getString("json", "{}")).getJSONObject("hourly");
            JSONArray times = hourly.getJSONArray("time"), temps = hourly.getJSONArray("temperature_2m");
            JSONArray codes = hourly.getJSONArray("weather_code");
            LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
            boolean h24 = DateFormat.is24HourFormat(activity);
            DateTimeFormatter label = DateTimeFormatter.ofPattern(h24 ? "HH:mm" : "h a", Locale.getDefault());
            int added = 0;
            for (int i = 0; i < times.length() && added < HOURS; i++) {
                LocalDateTime t = LocalDateTime.parse(times.getString(i));
                if (!t.isAfter(now)) continue;
                int[] sun = HomeInfo.sunFor(activity, t.toLocalDate());
                int minute = t.getHour() * 60 + t.getMinute();
                boolean day = sun == null || (minute >= sun[0] && minute < sun[1]);
                hoursRow.addView(cell(t.format(label), HomeInfo.conditionIcon(codes.getInt(i), day),
                        Math.round(temps.getDouble(i))));
                added++;
            }
            return added > 0;
        } catch (Exception e) {
            return false;   // no hourly forecast kept yet
        }
    }

    private View cell(String time, int icon, long temp) {
        LinearLayout c = new LinearLayout(activity);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        c.setLayoutParams(lp);
        TextView t = text(12, 0x99FFFFFF, R.font.outfit_medium);
        t.setText(time);
        t.setAllCaps(true);
        t.setLetterSpacing(0.06f);
        c.addView(t);
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView i = new ImageView(activity);
        i.setImageResource(icon);
        i.setImageTintList(ColorStateList.valueOf(0xE6FFFFFF));
        int size = Math.round(20 * density);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(size, size);
        ip.setMarginEnd(Math.round(5 * density));
        row.addView(i, ip);
        TextView v = text(17, 0xF2FFFFFF, R.font.outfit_regular);
        v.setText(activity.getString(R.string.degrees, temp));
        row.addView(v);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = Math.round(4 * density);
        c.addView(row, rp);
        return c;
    }

    private TextView text(int sp, int color, int font) {
        TextView t = new TextView(activity);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(activity.getResources().getFont(font));
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        return t;
    }

    /** NAQI from the air-quality readings HomeInfo kept: the larger of the PM2.5 and PM10 sub-indices. */
    private boolean fillAir() {
        try {
            JSONObject hourly = new JSONObject(weather.getString("aq_json", "{}")).getJSONObject("hourly");
            JSONArray times = hourly.getJSONArray("time"), pm25 = hourly.getJSONArray("pm2_5");
            JSONArray pm10 = hourly.getJSONArray("pm10");
            LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
            double sum25 = 0, sum10 = 0;
            int n25 = 0, n10 = 0;
            // The 24 hours up to now, as CPCB averages them.
            for (int i = 0; i < times.length(); i++) {
                LocalDateTime t = LocalDateTime.parse(times.getString(i));
                if (t.isAfter(now) || t.isBefore(now.minusHours(24))) continue;
                if (!pm25.isNull(i)) { sum25 += pm25.getDouble(i); n25++; }
                if (!pm10.isNull(i)) { sum10 += pm10.getDouble(i); n10++; }
            }
            if (n25 < 16 && n10 < 16) return false;   // CPCB wants at least 16 hours of data
            int aqi = Math.max(n25 >= 16 ? subIndex(sum25 / n25, PM25) : 0, n10 >= 16 ? subIndex(sum10 / n10, PM10) : 0);
            int band = aqi <= 50 ? 0 : aqi <= 100 ? 1 : aqi <= 200 ? 2 : aqi <= 300 ? 3 : aqi <= 400 ? 4 : 5;
            aqiValue.setText(activity.getString(R.string.aqi_value, aqi));
            aqiLabel.setText(AQI_NAMES[band]);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(AQI_COLORS[band]);
            aqiDot.setBackground(dot);
            return true;
        } catch (Exception e) {
            return false;   // no air-quality reading kept yet
        }
    }

    private static int subIndex(double c, double[] bp) {
        for (int i = 1; i < bp.length; i++) {
            if (c <= bp[i]) {
                return (int) Math.round(INDEX[i - 1] + (c - bp[i - 1]) * (INDEX[i] - INDEX[i - 1]) / (bp[i] - bp[i - 1]));
            }
        }
        return 500;
    }

    /**
     * Places the panel under the line below the clock, from LEFT_DP to the photo's left edge (or where the photo
     * would be at its widest), and drops the note to two lines if it would otherwise run into the dock.
     */
    void layout(View line, int dockTop, int photoLeft) {
        if (!on() || line == null || dockTop <= 0) return;
        int[] at = new int[2];
        line.getLocationInWindow(at);
        int top = at[1] + line.getHeight() + Math.round(LINE_GAP_DP * density);
        int left = Math.round(LEFT_DP * density);
        int right = photoLeft - Math.round(PHOTO_GAP_DP * density);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panel.getLayoutParams();
        lp.topMargin = top;
        lp.leftMargin = left;
        lp.width = Math.max(Math.round(240 * density), right - left);
        panel.setLayoutParams(lp);
        int room = dockTop - Math.round(DOCK_GAP_DP * density) - top;
        noteText.setMaxLines(room < Math.round(170 * density) ? 2 : 3);
    }

    /** With the shelf: shown while the dock has focus, hidden in the grid. */
    void show(boolean show, boolean animate) {
        if (!on() || show == shown) return;
        shown = show;
        panel.animate().cancel();
        float alpha = show ? 1f : 0f;
        if (animate) panel.animate().alpha(alpha).setDuration(show ? 260 : 160).start();
        else panel.setAlpha(alpha);
    }
}
