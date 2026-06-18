package de.beardedskunk.clipsharing.calendar

import de.beardedskunk.clipsharing.data.Recurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class IcsParserTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    @Test fun timedEventWithTzidReminderAndRrule() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:Team Meeting
            DTSTART;TZID=Europe/Berlin:20260620T140000
            DTEND;TZID=Europe/Berlin:20260620T150000
            LOCATION:Büro
            DESCRIPTION:Wichtig\, bitte vorbereiten
            RRULE:FREQ=WEEKLY
            TRANSP:OPAQUE
            BEGIN:VALARM
            TRIGGER:-PT15M
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val e = IcsParser.parse(ics, berlin)!!
        assertEquals("Team Meeting", e.title)
        assertFalse(e.allDay)
        assertEquals("2026-06-20T14:00:00+02:00[Europe/Berlin]", e.start)
        assertEquals("2026-06-20T15:00:00+02:00[Europe/Berlin]", e.end)
        assertEquals("Büro", e.location)
        assertEquals("Wichtig, bitte vorbereiten", e.description)
        assertEquals(Recurrence.WEEKLY, e.recurrence)
        assertEquals(15, e.reminderMinutes)
        assertTrue(e.busy)
    }

    @Test fun allDayEventEndIsInclusive() {
        val ics = """
            BEGIN:VEVENT
            SUMMARY:Urlaub
            DTSTART;VALUE=DATE:20260701
            DTEND;VALUE=DATE:20260704
            TRANSP:TRANSPARENT
            END:VEVENT
        """.trimIndent()
        val e = IcsParser.parse(ics, berlin)!!
        assertTrue(e.allDay)
        assertEquals("2026-07-01", e.start)
        // ICS-DTEND 0704 ist exklusiv -> inklusiv letzter Tag 0703.
        assertEquals("2026-07-03", e.end)
        assertFalse(e.busy)
    }

    @Test fun utcTimeConvertedToDefaultZone() {
        val ics = """
            BEGIN:VEVENT
            SUMMARY:Call
            DTSTART:20260620T120000Z
            DTEND:20260620T123000Z
            END:VEVENT
        """.trimIndent()
        val e = IcsParser.parse(ics, berlin)!!
        assertEquals("2026-06-20T14:00:00+02:00[Europe/Berlin]", e.start)
        assertEquals("2026-06-20T14:30:00+02:00[Europe/Berlin]", e.end)
    }

    @Test fun foldedLinesAndNoEventReturnsNull() {
        // gefaltete (umgebrochene) DESCRIPTION-Zeile wird wieder zusammengesetzt.
        val ics = """
            BEGIN:VEVENT
            SUMMARY:Lang
            DTSTART:20260101T090000Z
            DESCRIPTION:Teil eins
              und Teil zwei
            END:VEVENT
        """.trimIndent()
        assertEquals("Teil eins und Teil zwei", IcsParser.parse(ics, berlin)!!.description)
        assertNull(IcsParser.parse("kein kalender", berlin))
    }
}
