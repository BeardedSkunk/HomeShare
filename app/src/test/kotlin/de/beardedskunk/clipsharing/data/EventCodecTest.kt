package de.beardedskunk.clipsharing.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sichert die Markdown-Serialisierung der Kalender-Einträge ([EventCodec]), auf der der
 * Android-Kalender-Sync ([de.beardedskunk.clipsharing.calendar.CalendarSync]) aufsetzt:
 * parst der Codec den gespeicherten Eintrag nicht, landet kein Termin im Kalender.
 */
class EventCodecTest {

    @Test fun timedEvent_roundTrips() {
        val e = EventData(
            title = "Team Meeting",
            start = "2026-06-20T14:00:00+02:00[Europe/Berlin]",
            end = "2026-06-20T15:00:00+02:00[Europe/Berlin]",
            allDay = false,
            location = "Büro",
            description = "Bitte vorbereiten",
            reminderMinutes = 15,
            recurrence = Recurrence.WEEKLY,
            busy = true,
        )
        assertEquals(e, EventCodec.parse(EventCodec.encode(e)))
    }

    @Test fun parsesRealSyncedEntry_zest() {
        // Exakt der über den Sync angekommene cal-2-Eintrag, der im Android-Kalender landen muss.
        val text = "zest\n\n```event\nstart: 2026-06-18T12:00+02:00[Europe/Berlin]\n" +
            "end: 2026-06-18T13:00+02:00[Europe/Berlin]\nallDay: false\nlocation: dabei\nbusy: true\n```"
        val e = EventCodec.parse(text)!!
        assertEquals("zest", e.title)
        assertEquals("dabei", e.location)
        assertFalse(e.allDay)
        assertTrue(e.busy)
        assertEquals("2026-06-18T12:00+02:00[Europe/Berlin]", e.start)
    }

    @Test fun allDayAndFreeBusy_andNonEventReturnsNull() {
        val e = EventData(title = "Urlaub", start = "2026-07-01", end = "2026-07-03", allDay = true, busy = false)
        val back = EventCodec.parse(EventCodec.encode(e))!!
        assertTrue(back.allDay)
        assertFalse(back.busy)
        assertNull(EventCodec.parse("nur ein normaler Post ohne event-Block"))
        assertFalse(EventCodec.isEvent("kein event"))
    }
}
