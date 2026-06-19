package de.beardedskunk.homeshare.calendar

import de.beardedskunk.homeshare.data.EventData
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Für den Android-Kalender berechnete Zeiten (Millis seit Epoche). */
data class EventTimes(
    val startMillis: Long,
    val endMillis: Long,
    val timeZone: String,
    val durationSeconds: Long,
)

/**
 * Wandelt die lokalen ISO-Zeiten eines [EventData] in die vom CalendarProvider erwarteten
 * Millis um. Ganztägige Events folgen der CalendarContract-Konvention: DTSTART/DTEND auf
 * Mitternacht **UTC**, Zeitzone "UTC", DTEND = Folgetag (exklusiv).
 */
object EventTime {
    private val UTC = ZoneId.of("UTC")

    fun compute(e: EventData, defaultTz: String): EventTimes {
        if (e.allDay) {
            // Gespeichertes Enddatum ist INKLUSIV (letzter Tag); CalendarContract braucht
            // DTEND exklusiv -> +1 Tag. Mitternacht UTC (CalendarContract-Konvention).
            val startDate = parseDate(e.start)
            val endInclusive = parseDate(e.end).let { if (it.isBefore(startDate)) startDate else it }
            val startMs = startDate.atStartOfDay(UTC).toInstant().toEpochMilli()
            val endMs = endInclusive.plusDays(1).atStartOfDay(UTC).toInstant().toEpochMilli()
            return EventTimes(startMs, endMs, "UTC", (endMs - startMs) / 1000)
        }
        val startZdt = zoned(e.start, defaultTz)
        val endZdt = zoned(e.end, defaultTz).let { if (it.isBefore(startZdt)) startZdt else it }
        val startMs = startZdt.toInstant().toEpochMilli()
        val endMs = endZdt.toInstant().toEpochMilli()
        return EventTimes(startMs, endMs, startZdt.zone.id, (endMs - startMs) / 1000)
    }

    /** Robustes Parsen des ISO-Zeitstrings: ZonedDateTime > OffsetDateTime > lokal+DefaultTz. */
    private fun zoned(s: String, defaultTz: String): ZonedDateTime {
        val t = s.trim()
        runCatching { return ZonedDateTime.parse(t) }
        runCatching { return OffsetDateTime.parse(t).toZonedDateTime() }
        val zone = runCatching { ZoneId.of(defaultTz) }.getOrDefault(ZoneId.systemDefault())
        return parseDateTime(t).atZone(zone)
    }

    /** RFC2445-Dauer für wiederkehrende Events (kein DTEND erlaubt). */
    fun isoDuration(durationSeconds: Long, allDay: Boolean): String {
        if (allDay) {
            val days = (durationSeconds / 86_400).coerceAtLeast(1)
            return "P${days}D"
        }
        val secs = durationSeconds.coerceAtLeast(0)
        return "PT${secs}S"
    }

    private fun parseDate(s: String): LocalDate = LocalDate.parse(s.trim().substringBefore('T'))

    private fun parseDateTime(s: String): LocalDateTime {
        val t = s.trim()
        return if (t.contains('T')) LocalDateTime.parse(t) else LocalDate.parse(t).atStartOfDay()
    }
}
