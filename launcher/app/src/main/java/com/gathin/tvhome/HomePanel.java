package com.gathin.tvhome;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.text.format.DateFormat;
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

import java.time.LocalDate;
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
    private static final int LEFT_DP = 48, LINE_GAP_DP = 20, PHOTO_GAP_DP = 36, DOCK_GAP_DP = 20;
    /** CPCB's PM2.5 and PM10 breakpoints (24-hour average, ug/m3) against the index band each one opens. */
    private static final double[] PM25 = {0, 30, 60, 90, 120, 250, 380};
    private static final double[] PM10 = {0, 50, 100, 250, 350, 430, 510};
    private static final int[] INDEX = {0, 50, 100, 200, 300, 400, 500};
    private static final int[] AQI_COLORS = {0xFF4CAF50, 0xFF9CCC65, 0xFFFFD54F, 0xFFFF9800, 0xFFF44336, 0xFFB71C1C};
    private static final int[] AQI_NAMES = {R.string.aqi_good, R.string.aqi_satisfactory, R.string.aqi_moderate,
            R.string.aqi_poor, R.string.aqi_very_poor, R.string.aqi_severe};

    private final Activity activity;
    private final SharedPreferences weather, notes;
    private final boolean notesOn, hoursOn, airOn, agendaOn, playingOn;
    private final View panel, noteCard, aqiBadge, aqiDot, hoursBlock, nowPlaying;
    private final LinearLayout agenda;
    private final ImageView npArt, npState;
    private final TextView npTitle, npSub;
    private NowPlaying.Track track;
    private java.util.List<CalendarFeed.Event> events = java.util.Collections.emptyList();
    private static final int AGENDA_MAX = 3, AGENDA_DAYS = 7;
    private final TextView noteText, noteMeta, aqiValue, aqiLabel;
    private final LinearLayout hoursRow;
    private final float density;
    private boolean shown;

    HomePanel(Activity activity, SharedPreferences weather, boolean notesOn, boolean hoursOn, boolean airOn,
              boolean agendaOn, boolean playingOn) {
        this.activity = activity;
        this.weather = weather;
        this.notes = activity.getSharedPreferences(TelegramNotes.PREFS, Activity.MODE_PRIVATE);
        panel = activity.findViewById(R.id.infoPanel);
        this.notesOn = notesOn && panel != null;
        this.hoursOn = hoursOn && panel != null;
        this.airOn = airOn && panel != null;
        this.agendaOn = agendaOn && panel != null;
        this.playingOn = playingOn && panel != null;
        agenda = activity.findViewById(R.id.agenda);
        nowPlaying = activity.findViewById(R.id.nowPlaying);
        npArt = activity.findViewById(R.id.npArt);
        npState = activity.findViewById(R.id.npState);
        npTitle = activity.findViewById(R.id.npTitle);
        npSub = activity.findViewById(R.id.npSub);
        if (npArt != null) npArt.setClipToOutline(true);
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
        return notesOn || hoursOn || airOn || agendaOn || playingOn;
    }

    /** NowPlaying: the track changed, or stopped (null). */
    void setTrack(NowPlaying.Track t) {
        track = t;
        if (playingOn) refresh();
    }

    private java.util.Map<String, int[]> destinations = java.util.Collections.emptyMap();

    static String eventKey(CalendarFeed.Event e) {
        return e.title + "|" + e.start.toInstant().toEpochMilli();
    }

    /** HomeInfo: the weather where the agenda's trips go, by eventKey: {code, max, min}. */
    void setDestinations(java.util.Map<String, int[]> d) {
        destinations = d;
        if (agendaOn) refresh();
    }

    /** HomeInfo: the calendars were read anew. */
    void setEvents(java.util.List<CalendarFeed.Event> e) {
        events = e;
        if (agendaOn) refresh();
    }

    /** Fills the panel from prefs; cheap, called whenever the note, the readings or the hour change. */
    void refresh() {
        if (!on()) return;
        // No note: no card at all, and the forecast moves up into its place.
        String text = notesOn ? notes.getString(TelegramNotes.KEY_TEXT, "") : "";
        noteCard.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        if (!text.isEmpty()) {
            long at = notes.getLong(TelegramNotes.KEY_AT, 0);
            noteText.setText(text);
            noteMeta.setText(age(at));
        }
        boolean hours = hoursOn && fillHours();
        boolean air = airOn && fillAir();
        hoursRow.setVisibility(hours ? View.VISIBLE : View.GONE);
        aqiBadge.setVisibility(air ? View.VISIBLE : View.GONE);
        hoursBlock.setVisibility(hours || air ? View.VISIBLE : View.GONE);
        if (agendaOn) fillAgenda();
        if (playingOn) fillPlaying();
        panel.post(this::fit);
        // The forecast keeps its gap under the note only when there is a note above it (fit() adds the room left).
        ((LinearLayout.LayoutParams) hoursBlock.getLayoutParams()).topMargin =
                text.isEmpty() ? 0 : Math.round(16 * density);
        ((LinearLayout.LayoutParams) agenda.getLayoutParams()).topMargin = Math.round(16 * density);
    }

    /** How old the note is, as a chat app says it: "now", "5m", "2h" today, then "Yesterday", "Mon", "12 Oct". */
    private String age(long at) {
        long minutes = (System.currentTimeMillis() - at) / 60_000L;
        ZoneId zone = ZoneId.systemDefault();
        LocalDate day = java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalDate();
        LocalDate today = LocalDate.now(zone);
        if (day.equals(today)) {
            if (minutes < 1) return activity.getString(R.string.note_now);
            if (minutes < 60) return activity.getString(R.string.note_minutes, minutes);
            return activity.getString(R.string.note_hours, minutes / 60);
        }
        if (day.equals(today.minusDays(1))) return activity.getString(R.string.note_yesterday);
        if (day.isAfter(today.minusDays(7))) return day.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault()));
        return day.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()));
    }

    /** The next events, soonest first: "in 20 min", "16:00", "Tomorrow 10:30", "Sat", with the title after. */
    private void fillAgenda() {
        agenda.removeAllViews();
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now();
        LocalDate today = now.toLocalDate();
        boolean h24 = DateFormat.is24HourFormat(activity);
        DateTimeFormatter clock = DateTimeFormatter.ofPattern(h24 ? "HH:mm" : "h:mm a", Locale.getDefault());
        DateTimeFormatter weekday = DateTimeFormatter.ofPattern("EEE", Locale.getDefault());
        int shown = 0;
        agendaExtra = 0;
        for (CalendarFeed.Event e : events) {
            // Begun more than half an hour ago: it makes way.
            if (!e.allDay && e.start.plusMinutes(30).isBefore(now)) continue;
            if (shown == AGENDA_MAX) {
                agendaExtra++;   // counted for "+N more"
                continue;
            }
            LocalDate day = e.start.toLocalDate();
            String when;
            long mins = java.time.Duration.between(now, e.start).toMinutes();
            if (e.allDay) {
                when = day.equals(today) ? activity.getString(R.string.agenda_all_day)
                        : day.equals(today.plusDays(1)) ? activity.getString(R.string.agenda_tomorrow) : day.format(weekday);
            } else if (mins <= 0) {
                when = activity.getString(R.string.agenda_now);
            } else if (mins < 60) {
                when = activity.getString(R.string.agenda_in_minutes, mins);
            } else if (day.equals(today)) {
                when = e.start.format(clock);
            } else if (day.equals(today.plusDays(1))) {
                when = activity.getString(R.string.agenda_tomorrow) + " " + e.start.format(clock);
            } else {
                when = day.format(weekday) + " " + e.start.format(clock);
            }
            agenda.addView(agendaRow(when, e.title, shown == 0 && mins < 60 && !e.allDay,
                    destinations.get(eventKey(e))));
            shown++;
        }
        agenda.setVisibility(shown > 0 ? View.VISIBLE : View.GONE);
    }

    /** Upcoming events with no row: past AGENDA_MAX, or given way to fit above the dock. */
    private int agendaExtra;

    /** "+N more" at the end of the last agenda row, when some events have no row. */
    private void markMore() {
        int rows = agenda.getChildCount();
        if (rows == 0) return;
        LinearLayout last = (LinearLayout) agenda.getChildAt(rows - 1);
        View old = last.findViewWithTag("more");
        if (old != null) last.removeView(old);
        if (agendaExtra <= 0) return;
        TextView more = text(14, 0x99FFFFFF, R.font.outfit_medium);
        more.setTag("more");
        more.setText(activity.getString(R.string.agenda_more, agendaExtra));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(Math.round(12 * density));
        last.addView(more, lp);
    }

    private View agendaRow(String when, String title, boolean soon, int[] weather) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(activity);
        icon.setImageResource(R.drawable.ic_agenda);
        icon.setImageTintList(ColorStateList.valueOf(soon ? 0xFFFFD54F : 0x80FFFFFF));
        int size = Math.round(16 * density);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(size, size);
        ip.setMarginEnd(Math.round(10 * density));
        row.addView(icon, ip);
        TextView w = text(15, soon ? 0xFFFFD54F : 0xB3FFFFFF, R.font.outfit_medium);
        w.setText(when);
        w.setMinWidth(Math.round(118 * density));
        row.addView(w);
        TextView t = text(16, 0xF2FFFFFF, R.font.outfit_regular);
        t.setText(title);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        // The title as long as it is (up to the room left for the weather), the trip's weather right after it.
        t.setMaxWidth(Math.max(Math.round(160 * density), panel.getLayoutParams().width - Math.round(380 * density)));
        row.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (weather != null) {
            // The day's weather where the event goes: its icon, high and low.
            ImageView wi = new ImageView(activity);
            wi.setImageResource(HomeInfo.conditionIcon(weather[0], true));
            wi.setImageTintList(ColorStateList.valueOf(0xCCFFFFFF));
            int ws = Math.round(18 * density);
            LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(ws, ws);
            wp.setMarginStart(Math.round(14 * density));
            wp.setMarginEnd(Math.round(6 * density));
            row.addView(wi, wp);
            TextView wt = text(15, 0xB3FFFFFF, R.font.outfit_regular);
            wt.setText(activity.getString(R.string.degrees, weather[1]) + " / "
                    + activity.getString(R.string.degrees, weather[2]));
            row.addView(wt);
        }
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        if (agenda.getChildCount() > 0) rp.topMargin = Math.round(8 * density);
        row.setLayoutParams(rp);
        return row;
    }

    /** The playing track's card: its art (or the app's icon), title, artist and app, play or pause mark. */
    private void fillPlaying() {
        NowPlaying.Track t = track;
        nowPlaying.setVisibility(t == null ? View.GONE : View.VISIBLE);
        if (t == null) return;
        if (t.art != null) npArt.setImageBitmap(t.art);
        else npArt.setImageResource(R.drawable.ic_np_playing);
        GradientDrawable round = new GradientDrawable();
        round.setCornerRadius(10 * density);
        round.setColor(0x33FFFFFF);
        npArt.setBackground(round);
        npTitle.setText(t.title);
        String sub = t.artist.isEmpty() ? t.app : t.app.isEmpty() ? t.artist : t.artist + "  \u00B7  " + t.app;
        npSub.setText(sub);
        npSub.setVisibility(sub.isEmpty() ? View.GONE : View.VISIBLE);
        npState.setImageResource(t.playing ? R.drawable.ic_np_playing : R.drawable.ic_np_paused);
        npState.setImageTintList(ColorStateList.valueOf(0xB3FFFFFF));
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
        c.setGravity(Gravity.START);   // time over icon and temperature, all on one left edge
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
    void layout(View line, int dockTop, int photoLeft, int photoBottom) {
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
        // The note card fits its note, up to the panel's width (less its padding and the age at the end).
        noteText.setMaxWidth(lp.width - Math.round((20 + 20 + 16 + 56) * density));
        int room = dockTop - Math.round(DOCK_GAP_DP * density) - top;
        noteText.setMaxLines(room < Math.round(170 * density) ? 2 : 3);
        bottomLimit = dockTop - Math.round(DOCK_GAP_DP * density);
        minTop = top;
        alignBottom = photoBottom;
        panel.post(this::fit);
    }

    /** The panel's highest place (under the line below the clock), and the photo's bottom it ends level with. */
    private int minTop, alignBottom;

    private int bottomLimit;

    /**
     * Whatever would run into the dock gives way, least important first: agenda rows from the last, then the note
     * drops to one line. Now playing, the forecast and the first agenda row stay.
     */
    private void fit() {
        if (bottomLimit <= 0) return;
        // Measured as laid out: the panel's real top on screen, and every row at the panel's real width.
        int[] at = new int[2];
        panel.getLocationInWindow(at);
        int width = panel.getWidth() > 0 ? panel.getWidth() : panel.getLayoutParams().width;
        for (int guard = 0; guard < 8; guard++) {
            panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            if (minTop + panel.getMeasuredHeight() <= bottomLimit) break;
            if (agenda.getVisibility() == View.VISIBLE && agenda.getChildCount() > 1) {
                agenda.removeViewAt(agenda.getChildCount() - 1);
                agendaExtra++;
            } else if (noteText.getMaxLines() > 1 && noteCard.getVisibility() == View.VISIBLE) {
                noteText.setMaxLines(1);
            } else if (agenda.getVisibility() == View.VISIBLE && agenda.getChildCount() == 1) {
                agenda.removeViewAt(0);   // not even one row fits: the agenda waits for more room
                agenda.setVisibility(View.GONE);
                agendaExtra++;
            } else {
                break;
            }
        }
        markMore();
        // Level with the photo: the note stays under the line below the clock, and the room left over goes above the
        // forecast, so the panel's last line ends where the photo does. With no note the forecast stays at the top
        // and the room goes above the agenda.
        View spaced = noteCard.getVisibility() == View.VISIBLE ? hoursBlock
                : agenda.getVisibility() == View.VISIBLE ? agenda : null;
        if (spaced != null && spaced.getVisibility() == View.VISIBLE) {
            LinearLayout.LayoutParams sp = (LinearLayout.LayoutParams) spaced.getLayoutParams();
            int base = Math.round(16 * density);
            sp.topMargin = base;
            spaced.setLayoutParams(sp);
            if (alignBottom > 0) {
                panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                int extra = Math.min(alignBottom, bottomLimit) - (minTop + panel.getMeasuredHeight());
                if (extra > 0) {
                    sp.topMargin = base + extra;
                    spaced.setLayoutParams(sp);
                }
            }
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panel.getLayoutParams();
        if (lp.topMargin != minTop) {
            lp.topMargin = minTop;
            panel.setLayoutParams(lp);
        }
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
