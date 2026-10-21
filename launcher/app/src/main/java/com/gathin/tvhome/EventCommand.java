package com.gathin.tvhome;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the when and the what of a Telegram "/event" (TelegramNotes): "18:00 Dentist", "tomorrow 10:30 Standup",
 * "fri 6pm Dinner", "25 oct 9am Flight", "sat Diwali shopping" (no time: all day). A time with no day is today, or
 * tomorrow once it has passed. Pure parsing, no Android types, so it runs in JVM tests.
 */
final class EventCommand {

    /** What "/event" asked for; null fields never. */
    static final class Parsed {
        final ZonedDateTime start;
        final boolean allDay;
        final String title;

        Parsed(ZonedDateTime start, boolean allDay, String title) {
            this.start = start;
            this.allDay = allDay;
            this.title = title;
        }
    }

    private static final Pattern TIME = Pattern.compile("^(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DAY_MONTH = Pattern.compile("^(\\d{1,2})(?:st|nd|rd|th)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMERIC = Pattern.compile("^(\\d{1,2})[/.-](\\d{1,2})(?:[/.-](\\d{2,4}))?$");
    private static final Pattern ISO = Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$");

    private EventCommand() {
    }

    /** The text after "/event"; null when there is no title, or a date or time that cannot be. */
    static Parsed parse(String text, ZonedDateTime now) {
        String[] words = text.trim().split("\\s+");
        int i = 0;
        LocalDate date = null;
        LocalTime time = null;
        // The day: a word for it, a weekday, "25 oct" / "oct 25", 25/10, or 2026-10-25.
        if (i < words.length) {
            String w = words[i].toLowerCase(Locale.ROOT);
            Matcher m;
            if (w.equals("today") || w.equals("tonight")) {
                date = now.toLocalDate();
                i++;
            } else if (w.equals("tomorrow") || w.equals("tmrw") || w.equals("tmr")) {
                date = now.toLocalDate().plusDays(1);
                i++;
            } else if (weekday(w) != null) {
                // The coming one; today's weekday means next week's.
                date = now.toLocalDate().with(TemporalAdjusters.next(weekday(w)));
                i++;
            } else if ((m = ISO.matcher(w)).matches()) {
                date = date(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
                if (date == null) return null;
                i++;
            } else if ((m = NUMERIC.matcher(w)).matches()) {
                int year = m.group(3) == null ? -1 : Integer.parseInt(m.group(3));
                if (year >= 0 && year < 100) year += 2000;
                date = dayMonth(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), year, now);
                if (date == null) return null;
                i++;
            } else if ((m = DAY_MONTH.matcher(w)).matches() && i + 1 < words.length && month(words[i + 1]) != null) {
                date = dayMonth(Integer.parseInt(m.group(1)), month(words[i + 1]).getValue(), -1, now);
                if (date == null) return null;
                i += 2;
            } else if (month(w) != null && i + 1 < words.length && DAY_MONTH.matcher(words[i + 1]).matches()) {
                Matcher d = DAY_MONTH.matcher(words[i + 1]);
                d.matches();
                date = dayMonth(Integer.parseInt(d.group(1)), month(w).getValue(), -1, now);
                if (date == null) return null;
                i += 2;
            }
        }
        // The time: 18:00, 18.00, 6pm, 6:30 pm, or "at 18:00".
        if (i < words.length && words[i].equalsIgnoreCase("at")) i++;
        if (i < words.length) {
            String w = words[i];
            if (i + 1 < words.length && words[i + 1].matches("(?i)am|pm")) w = w + words[i + 1];
            Matcher m = TIME.matcher(w);
            if (m.matches() && (m.group(2) != null || m.group(3) != null)) {
                int h = Integer.parseInt(m.group(1));
                int min = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
                String ap = m.group(3);
                if (ap != null) {
                    if (h < 1 || h > 12) return null;
                    h = h % 12 + (ap.equalsIgnoreCase("pm") ? 12 : 0);
                }
                if (h > 23 || min > 59) return null;
                time = LocalTime.of(h, min);
                i += w.equals(words[i]) ? 1 : 2;
            }
        }
        StringBuilder title = new StringBuilder();
        for (; i < words.length; i++) title.append(title.length() == 0 ? "" : " ").append(words[i]);
        if (title.length() == 0 || (date == null && time == null)) return null;
        if (date == null) {
            date = now.toLocalDate();
            if (!date.atTime(time).atZone(now.getZone()).isAfter(now)) date = date.plusDays(1);
        }
        boolean allDay = time == null;
        ZonedDateTime start = allDay ? date.atStartOfDay(now.getZone()) : date.atTime(time).atZone(now.getZone());
        return new Parsed(start, allDay, title.toString());
    }

    /** A day and month with no year: this year's, or next year's once it has gone by. */
    private static LocalDate dayMonth(int day, int month, int year, ZonedDateTime now) {
        if (year > 0) return date(year, month, day);
        LocalDate d = date(now.getYear(), month, day);
        if (d != null && d.isBefore(now.toLocalDate())) d = date(now.getYear() + 1, month, day);
        return d;
    }

    private static LocalDate date(int year, int month, int day) {
        try {
            return LocalDate.of(year, month, day);
        } catch (Exception e) {
            return null;
        }
    }

    /** "fri", "friday", "thur", "thurs": at least three letters of a weekday's name. */
    private static DayOfWeek weekday(String w) {
        w = w.toLowerCase(Locale.ROOT);
        if (w.length() < 3) return null;
        for (DayOfWeek d : DayOfWeek.values()) {
            if (d.name().toLowerCase(Locale.ROOT).startsWith(w)) return d;
        }
        return null;
    }

    private static Month month(String w) {
        w = w.toLowerCase(Locale.ROOT).replace(",", "");
        if (w.length() < 3) return null;
        for (Month m : Month.values()) {
            if (m.name().toLowerCase(Locale.ROOT).startsWith(w) || (w.length() == 4 && w.equals("sept") && m == Month.SEPTEMBER)) {
                return m;
            }
        }
        return null;
    }
}
