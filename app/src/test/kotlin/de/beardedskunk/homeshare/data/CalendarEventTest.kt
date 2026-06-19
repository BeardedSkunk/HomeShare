package de.beardedskunk.homeshare.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarEventTest {

    @Test fun event_roundTrips_timedWithEverything() {
        val e = EventData(
            title = "Zahnarzt",
            start = "2026-06-20T14:00:00+02:00[Europe/Berlin]",
            end = "2026-06-20T15:00:00+02:00[Europe/Berlin]",
            allDay = false,
            location = "Praxis Dr. Müller",
            description = "Bitte Versichertenkarte\n- [ ] Karte einstecken",
            reminderMinutes = 30,
            recurrence = Recurrence.WEEKLY,
            busy = true,
        )
        val back = EventCodec.parse(EventCodec.encode(e))
        assertEquals(e, back)
    }

    @Test fun event_roundTrips_allDayMinimal() {
        val e = EventData(title = "Urlaub", start = "2026-07-01", end = "2026-07-14", allDay = true)
        assertEquals(e, EventCodec.parse(EventCodec.encode(e)))
    }

    @Test fun event_titleIsFirstLine_descriptionAfterBlock() {
        val text = EventCodec.encode(
            EventData(title = "Titel", start = "2026-01-01T09:00", end = "2026-01-01T10:00", description = "Notiz"),
        )
        assertTrue(text.startsWith("Titel"))
        assertTrue(EventCodec.isEvent(text))
        assertEquals("Notiz", EventCodec.parse(text)!!.description)
    }

    @Test fun event_parseRejectsNonEvent() {
        assertNull(EventCodec.parse("Nur ein normaler Post\nohne Event-Block"))
        assertFalse(EventCodec.isEvent("normaler text"))
    }

    @Test fun event_defaults_whenFieldsMissing() {
        val text = "Kurz\n\n```event\nstart: 2026-03-03T08:00\n```"
        val e = EventCodec.parse(text)!!
        assertEquals("Kurz", e.title)
        assertEquals("2026-03-03T08:00", e.start)
        assertEquals("2026-03-03T08:00", e.end) // end faellt auf start zurueck
        assertFalse(e.allDay)
        assertEquals(Recurrence.NONE, e.recurrence)
        assertNull(e.reminderMinutes)
        assertTrue(e.busy)
    }

    @Test fun feedMeta_encodesCalendarFlag_backwardCompatible() {
        assertEquals("Termine", FeedMeta.encode("Termine", false))
        val cal = FeedMeta.encode("Termine", true)
        assertEquals("Termine", FeedMeta.decodeName(cal))
        assertTrue(FeedMeta.decodeCalendar(cal))
        // Alter Feed: nur Name -> kein Kalender.
        assertFalse(FeedMeta.decodeCalendar("Alter Feed"))
        assertEquals("Alter Feed", FeedMeta.decodeName("Alter Feed"))
    }
}
