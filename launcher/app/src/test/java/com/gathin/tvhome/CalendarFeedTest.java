package com.gathin.tvhome;

import org.junit.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The agenda's iCal reading: time zones, all-day events, repeats, exceptions and cancellations. */
public class CalendarFeedTest {

    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 10, 22, 9, 0, 0, 0, KOLKATA);   // a Thursday

    private static List<CalendarFeed.Event> read(String events) {
        return read(events, 7);
    }

    private static List<CalendarFeed.Event> read(String events, int days) {
        String ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" + events + "END:VCALENDAR\r\n";
        return CalendarFeed.upcoming(Collections.singletonList(ics), NOW, days, 20);
    }

    @Test
    public void utcAndZonedTimesComeOutInTheTvsZone() {
        List<CalendarFeed.Event> e = read("BEGIN:VEVENT\r\nUID:a\r\nSUMMARY:Dentist\r\n"
                + "DTSTART:20261022T103000Z\r\nDTEND:20261022T110000Z\r\nEND:VEVENT\r\n"
                + "BEGIN:VEVENT\r\nUID:b\r\nSUMMARY:Call\r\nDTSTART;TZID=Europe/London:20261022T090000\r\n"
                + "DTEND;TZID=Europe/London:20261022T093000\r\nEND:VEVENT\r\n");
        assertEquals(2, e.size());
        assertEquals("Call", e.get(0).title);
        assertEquals(13, e.get(0).start.getHour());            // 09:00 London (BST) is 13:30 in India
        assertEquals(30, e.get(0).start.getMinute());
        assertEquals("Dentist", e.get(1).title);
        assertEquals(16, e.get(1).start.getHour());            // 10:30 UTC is 16:00 in India
    }

    @Test
    public void pastEventsAreLeftOutButOneUnderWayStays() {
        List<CalendarFeed.Event> e = read("BEGIN:VEVENT\r\nUID:a\r\nSUMMARY:Done\r\n"
                + "DTSTART;TZID=Asia/Kolkata:20261022T070000\r\nDTEND;TZID=Asia/Kolkata:20261022T080000\r\nEND:VEVENT\r\n"
                + "BEGIN:VEVENT\r\nUID:b\r\nSUMMARY:Running\r\n"
                + "DTSTART;TZID=Asia/Kolkata:20261022T083000\r\nDTEND;TZID=Asia/Kolkata:20261022T100000\r\nEND:VEVENT\r\n");
        assertEquals(1, e.size());
        assertEquals("Running", e.get(0).title);
    }

    @Test
    public void allDayEventsAreMarked() {
        List<CalendarFeed.Event> e = read("BEGIN:VEVENT\r\nUID:a\r\nSUMMARY:Diwali\r\n"
                + "DTSTART;VALUE=DATE:20261108\r\nDTEND;VALUE=DATE:20261109\r\nEND:VEVENT\r\n");
        assertEquals(0, e.size());                               // beyond the 7-day window
        e = read("BEGIN:VEVENT\r\nUID:a\r\nSUMMARY:Holiday\r\n"
                + "DTSTART;VALUE=DATE:20261024\r\nDTEND;VALUE=DATE:20261025\r\nEND:VEVENT\r\n");
        assertEquals(1, e.size());
        assertTrue(e.get(0).allDay);
    }

    @Test
    public void weeklyRepeatsWithAnExceptionAndAMovedOccurrence() {
        List<CalendarFeed.Event> e = read("BEGIN:VEVENT\r\nUID:s\r\nSUMMARY:Standup\r\n"
                + "DTSTART;TZID=Asia/Kolkata:20261001T103000\r\nDTEND;TZID=Asia/Kolkata:20261001T110000\r\n"
                + "RRULE:FREQ=WEEKLY;BYDAY=MO,TH\r\nEXDATE;TZID=Asia/Kolkata:20261026T103000\r\nEND:VEVENT\r\n"
                + "BEGIN:VEVENT\r\nUID:s\r\nSUMMARY:Standup (moved)\r\nRECURRENCE-ID;TZID=Asia/Kolkata:20261029T103000\r\n"
                + "DTSTART;TZID=Asia/Kolkata:20261029T150000\r\nDTEND;TZID=Asia/Kolkata:20261029T153000\r\nEND:VEVENT\r\n",
                9);
        // Over 9 days (to Sat 31 Oct 09:00): Thu 22 Oct 10:30 stays; Mon 26 is excluded; Thu 29 is moved to 15:00.
        assertEquals(2, e.size());
        assertEquals("Standup", e.get(0).title);
        assertEquals(22, e.get(0).start.getDayOfMonth());
        assertEquals("Standup (moved)", e.get(1).title);
        assertEquals(15, e.get(1).start.getHour());
    }

    @Test
    public void cancelledEventsAndFinishedSeriesAreLeftOut() {
        List<CalendarFeed.Event> e = read("BEGIN:VEVENT\r\nUID:c\r\nSUMMARY:Off\r\nSTATUS:CANCELLED\r\n"
                + "DTSTART;TZID=Asia/Kolkata:20261023T100000\r\nEND:VEVENT\r\n"
                + "BEGIN:VEVENT\r\nUID:d\r\nSUMMARY:Course\r\nDTSTART;TZID=Asia/Kolkata:20261001T180000\r\n"
                + "RRULE:FREQ=DAILY;COUNT=5\r\nEND:VEVENT\r\n");
        assertEquals(0, e.size());
    }

    @Test
    public void yearlyBirthdaysAndLongTitlesUnfold() {
        List<CalendarFeed.Event> e = read("BEGIN:VEVENT\r\nUID:y\r\nSUMMARY:Birthday of a very long\r\n"
                + "  name\r\nDTSTART;VALUE=DATE:19901025\r\nRRULE:FREQ=YEARLY\r\nEND:VEVENT\r\n");
        assertEquals(1, e.size());
        assertEquals("Birthday of a very long name", e.get(0).title);
        assertEquals(2026, e.get(0).start.getYear());
    }
}
