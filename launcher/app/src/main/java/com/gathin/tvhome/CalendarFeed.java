package com.gathin.tvhome;

import java.io.BufferedReader;
import java.io.StringReader;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The next events from a calendar's secret iCal address (config.xml calendar_ics_urls), for HomePanel's agenda.
 * Pure parsing, no Android types, so it runs in JVM tests. It understands what Google Calendar exports: DTSTART and
 * DTEND with TZID, UTC or all-day dates; RRULE with FREQ DAILY, WEEKLY, MONTHLY or YEARLY, INTERVAL, COUNT, UNTIL,
 * BYDAY (weekly) and BYMONTHDAY; EXDATE; and RECURRENCE-ID overrides, including moved and cancelled occurrences.
 */
final class CalendarFeed {

    /** One occurrence, in the TV's own time zone. */
    static final class Event {
        final String title;
        final ZonedDateTime start;
        final boolean allDay;

        Event(String title, ZonedDateTime start, boolean allDay) {
            this.title = title;
            this.start = start;
            this.allDay = allDay;
        }
    }

    private CalendarFeed() {
    }

    /** Occurrences that have not ended before now and start within days, soonest first, at most max of them. */
    static List<Event> upcoming(List<String> icsTexts, ZonedDateTime now, int days, int max) {
        List<Event> out = new ArrayList<>();
        ZonedDateTime until = now.plusDays(days);
        for (String ics : icsTexts) collect(ics, now, until, out);
        out.sort((a, b) -> a.start.compareTo(b.start));
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    private static final class Raw {
        String uid = "", summary = "", rrule, status = "";
        String start, startTz, end, endTz, recurrenceId, recurrenceTz;
        boolean allDay;
        final List<String[]> exdates = new ArrayList<>();
    }

    private static void collect(String ics, ZonedDateTime now, ZonedDateTime until, List<Event> out) {
        List<Raw> events = parse(ics);
        // Overrides of single occurrences, by series and original start.
        Map<String, Set<Long>> overridden = new HashMap<>();
        for (Raw r : events) {
            if (r.recurrenceId == null) continue;
            ZonedDateTime orig = when(r.recurrenceId, r.recurrenceTz, now.getZone());
            if (orig != null) overridden.computeIfAbsent(r.uid, k -> new HashSet<>()).add(orig.toEpochSecond());
        }
        for (Raw r : events) {
            if (r.start == null || "CANCELLED".equals(r.status)) continue;
            ZonedDateTime start = when(r.start, r.startTz, now.getZone());
            if (start == null) continue;
            ZonedDateTime end = r.end != null ? when(r.end, r.endTz, now.getZone()) : null;
            long length = end != null ? ChronoUnit.SECONDS.between(start, end) : (r.allDay ? 86400 : 0);
            String title = r.summary.isEmpty() ? "(no title)" : r.summary;
            if (r.rrule == null || r.recurrenceId != null) {
                add(out, title, start, length, r.allDay, now, until);
                continue;
            }
            Set<Long> skip = new HashSet<>(overridden.getOrDefault(r.uid, new HashSet<>()));
            for (String[] ex : r.exdates) {
                ZonedDateTime e = when(ex[0], ex[1], now.getZone());
                if (e != null) skip.add(e.toEpochSecond());
            }
            expand(r.rrule, start, now.getZone(), until, occurrence -> {
                if (!skip.contains(occurrence.toEpochSecond())) add(out, title, occurrence, length, r.allDay, now, until);
            });
        }
    }

    private static void add(List<Event> out, String title, ZonedDateTime start, long length, boolean allDay,
                            ZonedDateTime now, ZonedDateTime until) {
        ZonedDateTime end = start.plusSeconds(Math.max(length, 60));
        if (!end.isAfter(now) || start.isAfter(until)) return;
        out.add(new Event(title, start, allDay));
    }

    private interface Sink {
        void accept(ZonedDateTime occurrence);
    }

    /** Walks an RRULE from the series' start; stops at UNTIL, COUNT or the window's end, whichever comes first. */
    private static void expand(String rrule, ZonedDateTime start, ZoneId zone, ZonedDateTime until, Sink sink) {
        Map<String, String> rule = new HashMap<>();
        for (String part : rrule.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) rule.put(part.substring(0, eq).toUpperCase(), part.substring(eq + 1));
        }
        String freq = rule.getOrDefault("FREQ", "");
        int interval = Math.max(1, parseInt(rule.get("INTERVAL"), 1));
        int count = parseInt(rule.get("COUNT"), Integer.MAX_VALUE);
        ZonedDateTime last = rule.containsKey("UNTIL") ? when(rule.get("UNTIL"), null, zone) : null;
        if (last == null || last.isAfter(until)) last = until;
        List<DayOfWeek> byDay = new ArrayList<>();
        if (rule.containsKey("BYDAY") && freq.equals("WEEKLY")) {
            for (String d : rule.get("BYDAY").split(",")) {
                DayOfWeek w = day(d.replaceAll("^[+-]?\\d+", ""));
                if (w != null) byDay.add(w);
            }
        }
        int byMonthDay = parseInt(rule.get("BYMONTHDAY"), 0);
        // The local time of day and zone stay the same across daylight-saving changes, as calendars expect.
        LocalTime time = start.toLocalTime();
        LocalDate first = start.toLocalDate();
        int emitted = 0;
        for (int step = 0; step < 5000 && emitted < count; step++) {
            List<LocalDate> dates = new ArrayList<>();
            switch (freq) {
                case "DAILY":
                    dates.add(first.plusDays((long) step * interval));
                    break;
                case "WEEKLY": {
                    LocalDate week = first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                            .plusWeeks((long) step * interval);
                    if (byDay.isEmpty()) {
                        dates.add(first.plusWeeks((long) step * interval));
                    } else {
                        for (DayOfWeek w : DayOfWeek.values()) if (byDay.contains(w)) dates.add(week.with(w));
                    }
                    break;
                }
                case "MONTHLY": {
                    LocalDate month = first.withDayOfMonth(1).plusMonths((long) step * interval);
                    int dom = byMonthDay > 0 ? byMonthDay : first.getDayOfMonth();
                    if (dom <= month.lengthOfMonth()) dates.add(month.withDayOfMonth(dom));
                    break;
                }
                case "YEARLY": {
                    LocalDate year = first.withDayOfMonth(1).plusYears((long) step * interval);
                    if (first.getDayOfMonth() <= year.lengthOfMonth()) dates.add(year.withDayOfMonth(first.getDayOfMonth()));
                    break;
                }
                default:
                    sink.accept(start);
                    return;
            }
            for (LocalDate d : dates) {
                if (d.isBefore(first)) continue;
                ZonedDateTime occurrence = ZonedDateTime.of(d, time, start.getZone());
                if (occurrence.isAfter(last) || emitted >= count) return;
                emitted++;
                sink.accept(occurrence.withZoneSameInstant(zone));
            }
        }
    }

    /** Unfolds lines and splits the calendar into its VEVENTs. */
    private static List<Raw> parse(String ics) {
        List<Raw> out = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new StringReader(ics))) {
            String line;
            while ((line = r.readLine()) != null) {
                if ((line.startsWith(" ") || line.startsWith("\t")) && !lines.isEmpty()) {
                    lines.set(lines.size() - 1, lines.get(lines.size() - 1) + line.substring(1));
                } else {
                    lines.add(line);
                }
            }
        } catch (java.io.IOException ignored) {
            // A StringReader does not throw.
        }
        Raw cur = null;
        for (String line : lines) {
            if (line.equals("BEGIN:VEVENT")) {
                cur = new Raw();
                continue;
            }
            if (line.equals("END:VEVENT")) {
                if (cur != null) out.add(cur);
                cur = null;
                continue;
            }
            if (cur == null) continue;
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String head = line.substring(0, colon), value = line.substring(colon + 1);
            String name = head, tz = null;
            boolean date = false;
            int semi = head.indexOf(';');
            if (semi >= 0) {
                name = head.substring(0, semi);
                for (String p : head.substring(semi + 1).split(";")) {
                    if (p.startsWith("TZID=")) tz = p.substring(5).replace("\"", "");
                    if (p.equals("VALUE=DATE")) date = true;
                }
            }
            switch (name) {
                case "UID": cur.uid = value; break;
                case "SUMMARY": cur.summary = unescape(value); break;
                case "STATUS": cur.status = value.trim(); break;
                case "RRULE": cur.rrule = value; break;
                case "DTSTART":
                    cur.start = value;
                    cur.startTz = tz;
                    cur.allDay = date || value.length() == 8;
                    break;
                case "DTEND": cur.end = value; cur.endTz = tz; break;
                case "RECURRENCE-ID": cur.recurrenceId = value; cur.recurrenceTz = tz; break;
                case "EXDATE":
                    for (String v : value.split(",")) cur.exdates.add(new String[]{v, tz});
                    break;
                default:
                    break;
            }
        }
        return out;
    }

    /** "20261022T160000Z", "20261022T160000" with or without a TZID, or "20261022" (all day, local midnight). */
    static ZonedDateTime when(String value, String tz, ZoneId local) {
        try {
            value = value.trim();
            if (value.length() == 8) {
                return LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(local);
            }
            boolean utc = value.endsWith("Z");
            LocalDateTime t = LocalDateTime.parse(utc ? value.substring(0, value.length() - 1) : value,
                    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"));
            ZoneId zone = utc ? ZoneId.of("UTC") : tz != null ? ZoneId.of(tz) : local;
            return t.atZone(zone).withZoneSameInstant(local);
        } catch (Exception e) {
            return null;
        }
    }

    private static DayOfWeek day(String two) {
        switch (two) {
            case "MO": return DayOfWeek.MONDAY;
            case "TU": return DayOfWeek.TUESDAY;
            case "WE": return DayOfWeek.WEDNESDAY;
            case "TH": return DayOfWeek.THURSDAY;
            case "FR": return DayOfWeek.FRIDAY;
            case "SA": return DayOfWeek.SATURDAY;
            case "SU": return DayOfWeek.SUNDAY;
            default: return null;
        }
    }

    private static int parseInt(String s, int fallback) {
        try {
            return s == null ? fallback : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String unescape(String s) {
        return s.replace("\\n", " ").replace("\\N", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\");
    }
}
