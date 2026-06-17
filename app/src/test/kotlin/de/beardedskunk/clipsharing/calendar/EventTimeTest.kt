package de.beardedskunk.clipsharing.calendar

import de.beardedskunk.clipsharing.data.EventData
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class EventTimeTest {

    @Test fun timed_usesEmbeddedZone() {
        val e = EventData(
            title = "x",
            start = "2026-06-20T14:00:00+02:00[Europe/Berlin]",
            end = "2026-06-20T15:00:00+02:00[Europe/Berlin]",
        )
        val t = EventTime.compute(e, defaultTz = "UTC")
        val expStart = ZonedDateTime.of(2026, 6, 20, 14, 0, 0, 0, ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli()
        val expEnd = ZonedDateTime.of(2026, 6, 20, 15, 0, 0, 0, ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli()
        assertEquals(expStart, t.startMillis)
        assertEquals(expEnd, t.endMillis)
        assertEquals("Europe/Berlin", t.timeZone)
        assertEquals(3600L, t.durationSeconds)
    }

    @Test fun timed_fallsBackToDefaultTzWhenNoZoneInString() {
        val e = EventData(title = "x", start = "2026-01-10T09:00", end = "2026-01-10T09:30")
        val t = EventTime.compute(e, defaultTz = "Europe/Berlin")
        val exp = ZonedDateTime.of(2026, 1, 10, 9, 0, 0, 0, ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli()
        assertEquals(exp, t.startMillis)
        assertEquals("Europe/Berlin", t.timeZone)
    }

    @Test fun allDay_isUtcMidnight_endExclusiveNextDay() {
        val e = EventData(title = "x", start = "2026-06-20", end = "2026-06-20", allDay = true)
        val t = EventTime.compute(e, defaultTz = "Europe/Berlin")
        assertEquals(Instant.parse("2026-06-20T00:00:00Z").toEpochMilli(), t.startMillis)
        // Eintägig (Ende==Start, inklusiv) -> DTEND exklusiv = Folgetag.
        assertEquals(Instant.parse("2026-06-21T00:00:00Z").toEpochMilli(), t.endMillis)
        assertEquals("UTC", t.timeZone)
    }

    @Test fun allDay_multiDayRange_endInclusivePlusOne() {
        // 01.-03. (inklusiv, 3 Tage) -> DTEND exklusiv = 04.
        val e = EventData(title = "x", start = "2026-07-01", end = "2026-07-03", allDay = true)
        val t = EventTime.compute(e, defaultTz = "UTC")
        assertEquals(Instant.parse("2026-07-01T00:00:00Z").toEpochMilli(), t.startMillis)
        assertEquals(Instant.parse("2026-07-04T00:00:00Z").toEpochMilli(), t.endMillis)
    }

    @Test fun isoDuration_formats() {
        assertEquals("PT3600S", EventTime.isoDuration(3600, allDay = false))
        assertEquals("P1D", EventTime.isoDuration(86_400, allDay = true))
        assertEquals("P2D", EventTime.isoDuration(172_800, allDay = true))
    }
}
