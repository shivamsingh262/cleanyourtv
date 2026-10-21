package com.gathin.tvhome;

import org.junit.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Telegram "/event" text: days, times and titles as people type them. */
public class EventCommandTest {

    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 10, 22, 14, 0, 0, 0, KOLKATA);   // Thu 14:00

    private static String at(String text) {
        EventCommand.Parsed p = EventCommand.parse(text, NOW);
        if (p == null) return null;
        return p.start.toLocalDate() + (p.allDay ? " all-day " : " " + p.start.toLocalTime() + " ") + p.title;
    }

    @Test
    public void aTimeAloneIsTodayOrTomorrowOnceItHasPassed() {
        assertEquals("2026-10-22 18:00 Dentist", at("18:00 Dentist"));
        assertEquals("2026-10-23 09:30 Gym", at("9:30 Gym"));
        assertEquals("2026-10-22 18:30 Call mum", at("6:30pm Call mum"));
        assertEquals("2026-10-22 19:00 Dinner", at("7 pm Dinner"));
        assertEquals("2026-10-22 16:00 Tea", at("at 16.00 Tea"));
    }

    @Test
    public void dayWords() {
        assertEquals("2026-10-23 10:30 Standup", at("tomorrow 10:30 Standup"));
        assertEquals("2026-10-23 10:30 Standup", at("tmrw 10:30am Standup"));
        assertEquals("2026-10-22 21:00 Movie", at("tonight 9pm Movie"));
    }

    @Test
    public void weekdaysAreTheComingOneAndTodaysMeansNextWeek() {
        assertEquals("2026-10-23 18:00 Dinner", at("fri 6pm Dinner"));
        assertEquals("2026-10-23 18:00 Dinner", at("Friday 18:00 Dinner"));
        assertEquals("2026-10-29 10:00 Review", at("thu 10:00 Review"));
        assertEquals("2026-10-24 all-day Diwali shopping", at("sat Diwali shopping"));
    }

    @Test
    public void datesInTheShapesPeopleUse() {
        assertEquals("2026-10-25 09:00 Flight", at("25 oct 9am Flight"));
        assertEquals("2026-10-25 09:00 Flight", at("oct 25 9am Flight"));
        assertEquals("2026-11-08 all-day Diwali", at("8th nov Diwali"));
        assertEquals("2026-12-31 23:00 Party", at("31/12 23:00 Party"));
        assertEquals("2027-01-05 all-day Back to work", at("5/1 Back to work"));
        assertEquals("2027-03-02 10:00 Visa", at("2027-03-02 10:00 Visa"));
        assertEquals("2027-10-01 all-day Old day", at("1 oct Old day"));   // gone by this year: next year's
    }

    @Test
    public void nothingToReadIsRefused() {
        assertNull(at(""));
        assertNull(at("Dentist"));               // no day and no time
        assertNull(at("18:00"));                 // no title
        assertNull(at("31 feb Party"));          // no such day
        assertNull(at("25:00 Late"));            // no such time
        assertTrue(at("2 people over at 7pm") == null || at("2 people over at 7pm").contains("people"));
    }
}
