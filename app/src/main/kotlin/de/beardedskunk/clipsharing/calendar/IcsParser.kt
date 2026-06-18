package de.beardedskunk.clipsharing.calendar

import de.beardedskunk.clipsharing.data.EventCodec
import de.beardedskunk.clipsharing.data.EventData
import de.beardedskunk.clipsharing.data.FeedRepository
import de.beardedskunk.clipsharing.data.Recurrence
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Sehr leichter iCalendar-Parser für den Import von Terminen aus anderen Apps (Tbd (a)).
 * Liest das erste VEVENT aus .ics-/text/calendar-Inhalt in ein [EventData]. Unterstützt
 * Zeilen-Unfolding, DTSTART/DTEND (ganztägig per VALUE=DATE, lokal, UTC 'Z', TZID),
 * SUMMARY, LOCATION, DESCRIPTION, RRULE (FREQ-Preset), TRANSP (frei/gebucht) und
 * VALARM-TRIGGER (relative Erinnerung). Die App speichert das Ergebnis als Markdown
 * (über [EventCodec]), damit es unverändert über den bestehenden Sync läuft.
 */
object IcsParser {

    fun parse(ics: String, defaultZone: ZoneId = ZoneId.systemDefault()): EventData? {
        val lines = unfold(ics)
        val begin = lines.indexOfFirst { it.trim().equals("BEGIN:VEVENT", ignoreCase = true) }
        if (begin < 0) return null
        val endRel = lines.drop(begin + 1).indexOfFirst { it.trim().equals("END:VEVENT", ignoreCase = true) }
        val block = if (endRel < 0) lines.drop(begin + 1) else lines.subList(begin + 1, begin + 1 + endRel)

        var summary = ""
        var location = ""
        var description = ""
        var rrule: String? = null
        var busy = true
        var dtStart: Parsed? = null
        var dtEnd: Parsed? = null
        var reminder: Int? = null
        var inAlarm = false

        for (raw in block) {
            val (name, params, value) = splitProp(raw) ?: continue
            when (name.uppercase()) {
                "BEGIN" -> if (value.equals("VALARM", true)) inAlarm = true
                "END" -> if (value.equals("VALARM", true)) inAlarm = false
                "TRIGGER" -> if (inAlarm) reminder = parseTriggerMinutes(value) ?: reminder
                "SUMMARY" -> if (!inAlarm) summary = unescape(value)
                "LOCATION" -> if (!inAlarm) location = unescape(value)
                "DESCRIPTION" -> if (!inAlarm) description = unescape(value)
                "RRULE" -> if (!inAlarm) rrule = value
                "TRANSP" -> if (!inAlarm && value.equals("TRANSPARENT", true)) busy = false
                "DTSTART" -> if (!inAlarm) dtStart = parseDate(params, value, defaultZone)
                "DTEND" -> if (!inAlarm) dtEnd = parseDate(params, value, defaultZone)
            }
        }
        val ds = dtStart ?: return null
        val startStr: String
        val endStr: String
        if (ds.dateOnly) {
            val sd = ds.date!!
            startStr = sd.toString()
            // Ganztägiges DTEND in ICS ist exklusiv -> inklusiv = einen Tag zurück.
            val ed = dtEnd?.date?.minusDays(1)?.takeIf { !it.isBefore(sd) } ?: sd
            endStr = ed.toString()
        } else {
            startStr = ds.zdt!!.format(DateTimeFormatter.ISO_ZONED_DATE_TIME)
            endStr = (dtEnd?.zdt ?: ds.zdt).format(DateTimeFormatter.ISO_ZONED_DATE_TIME)
        }
        return EventData(
            title = summary.ifBlank { "(ohne Titel)" },
            start = startStr,
            end = endStr,
            allDay = ds.dateOnly,
            location = location,
            description = description,
            reminderMinutes = reminder,
            recurrence = Recurrence.fromRrule(rrule),
            busy = busy,
        )
    }

    private data class Parsed(val dateOnly: Boolean, val date: LocalDate?, val zdt: ZonedDateTime?)

    private fun parseDate(params: Map<String, String>, value: String, defaultZone: ZoneId): Parsed? {
        val v = value.trim()
        if (params["VALUE"].equals("DATE", true) || (v.length == 8 && !v.contains('T'))) {
            return runCatching { Parsed(true, LocalDate.parse(v, DateTimeFormatter.BASIC_ISO_DATE), null) }.getOrNull()
        }
        val utc = v.endsWith("Z")
        val core = v.removeSuffix("Z")
        val ldt = runCatching { LocalDateTime.parse(core, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")) }.getOrNull()
            ?: return null
        val zone = when {
            utc -> ZoneOffset.UTC
            params["TZID"] != null -> runCatching { ZoneId.of(params["TZID"]) }.getOrDefault(defaultZone)
            else -> defaultZone
        }
        var zdt = ldt.atZone(zone)
        if (utc) zdt = zdt.withZoneSameInstant(defaultZone) // UTC in der lokalen Zone darstellen
        return Parsed(false, null, zdt)
    }

    /** ISO-8601-Dauer wie -PT15M / -PT1H / -P1D -> Minuten vor Beginn. */
    private fun parseTriggerMinutes(value: String): Int? {
        val body = value.trim().removePrefix("-").removePrefix("+")
        val m = Regex("""P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?""").matchEntire(body) ?: return null
        val days = m.groupValues[1].toIntOrNull() ?: 0
        val hours = m.groupValues[2].toIntOrNull() ?: 0
        val mins = m.groupValues[3].toIntOrNull() ?: 0
        return days * 1440 + hours * 60 + mins
    }

    private fun unfold(ics: String): List<String> {
        val out = ArrayList<String>()
        for (line in ics.replace("\r\n", "\n").replace("\r", "\n").split('\n')) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && out.isNotEmpty()) {
                out[out.size - 1] = out[out.size - 1] + line.substring(1)
            } else {
                out.add(line)
            }
        }
        return out
    }

    private fun splitProp(line: String): Triple<String, Map<String, String>, String>? {
        if (line.isBlank()) return null
        val colon = line.indexOf(':')
        if (colon < 0) return null
        val left = line.substring(0, colon)
        val value = line.substring(colon + 1)
        val parts = left.split(';')
        val params = HashMap<String, String>()
        for (p in parts.drop(1)) {
            val eq = p.indexOf('=')
            if (eq > 0) params[p.substring(0, eq).uppercase()] = p.substring(eq + 1).trim('"')
        }
        return Triple(parts[0], params, value)
    }

    private fun unescape(s: String): String = s
        .replace("\\N", "\n").replace("\\n", "\n")
        .replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")
}

/**
 * Importiert das erste VEVENT aus [ics] als neuen Eintrag im Kalender-Feed [feedId].
 * Gibt true zurück, wenn ein Termin erkannt und angelegt wurde.
 */
fun importIcsToFeed(repo: FeedRepository, feedId: String, ics: String, defaultZone: ZoneId = ZoneId.systemDefault()): Boolean {
    val ev = IcsParser.parse(ics, defaultZone) ?: return false
    repo.createPost(feedId, EventCodec.encode(ev))
    return true
}
