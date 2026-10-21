package com.gathin.tvhome;

import org.junit.Test;

import java.time.LocalDate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Dates in a photo's Telegram caption, for On this day; and where an agenda event goes, for its weather. */
public class TelegramPhotosTest {

    @Test
    public void captionDates() {
        assertEquals(LocalDate.of(2023, 12, 1), TelegramPhotos.captionDate("Dec 2023"));
        assertEquals(LocalDate.of(2023, 12, 2), TelegramPhotos.captionDate("Goa, 2 Dec 2023"));
        assertEquals(LocalDate.of(2023, 12, 2), TelegramPhotos.captionDate("December 2, 2023"));
        assertEquals(LocalDate.of(2023, 12, 2), TelegramPhotos.captionDate("2023-12-02"));
        assertEquals(LocalDate.of(2023, 12, 2), TelegramPhotos.captionDate("2/12/2023"));
        assertEquals(LocalDate.of(2024, 9, 1), TelegramPhotos.captionDate("sept 2024 trip"));
        assertNull(TelegramPhotos.captionDate("Beach day"));
        assertNull(TelegramPhotos.captionDate(""));
    }

    @Test
    public void eventPlaces() {
        assertEquals("Dehradun", HomeInfo.place("Travel to Dehradun"));
        assertEquals("New Delhi", HomeInfo.place("Flight to New Delhi"));
        assertEquals("Mussoorie", HomeInfo.place("Lunch @Mussoorie"));
        assertEquals("Cyber Hub", HomeInfo.place("Dinner @ Cyber Hub"));
        assertNull(HomeInfo.place("Talk to mum"));
        assertNull(HomeInfo.place("Dentist"));
    }
}
