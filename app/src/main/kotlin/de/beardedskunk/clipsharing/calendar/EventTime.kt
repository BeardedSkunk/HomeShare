package de.beardedskunk.clipsharing.calendar

import de.beardedskunk.clipsharing.data.EventData
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

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
            val startDate = parseDate(e.start)
            val endDate = parseDate(e.end).let { if (!it.isAfter(startDate)) startDate.plusDays(1) else it }
            val startMs = startDate.atStartOfDay(UTC).toInstant().toEpochMilli()
            val endMs = endDate.atStartOfDay(UTC).toInstant().toEpochMilli()
            return EventTimes(startMs, endMs, "UTC", (endMs - startMs) / 1000)
        }
        val zone = runCatching { ZoneId.of(e.tz.ifBlank { defaultTz }) }
            .getOrElse { runCatching { ZoneId.of(defaultTz) }.getOrDefault(ZoneId.systemDefault()) }
        val startDt = parseDateTime(e.start)
        val endDt = parseDateTime(e.end).let { if (it.isBefore(startDt)) startDt else it }
        val startMs = startDt.atZone(zone).toInstant().toEpochMilli()
        val endMs = endDt.atZone(zone).toInstant().toEpochMilli()
        return EventTimes(startMs, endMs, zone.id, (endMs - startMs) / 1000)
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
