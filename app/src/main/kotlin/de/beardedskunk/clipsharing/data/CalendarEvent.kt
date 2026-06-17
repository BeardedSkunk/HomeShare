package de.beardedskunk.clipsharing.data

/** Wiederholung als Preset; mappt auf eine iCalendar-RRULE (FREQ=…). */
enum class Recurrence(val rrule: String?, val label: String) {
    NONE(null, "Keine"),
    DAILY("FREQ=DAILY", "Täglich"),
    WEEKLY("FREQ=WEEKLY", "Wöchentlich"),
    MONTHLY("FREQ=MONTHLY", "Monatlich"),
    YEARLY("FREQ=YEARLY", "Jährlich");

    companion object {
        fun fromRrule(s: String?): Recurrence {
            if (s.isNullOrBlank()) return NONE
            return entries.firstOrNull { it.rrule != null && s.contains(it.rrule) } ?: NONE
        }
    }
}

/**
 * Strukturierter Kalendereintrag. **Kanonische Speicherung ist Markdown** (siehe [EventCodec]) –
 * damit synct ein Eintrag unverändert über den bestehenden Op-/DAG-Mechanismus.
 *
 * Zeiten als vollständige ISO-8601-Strings **inkl. Zeitzone**:
 *  - getimt: ISO-ZonedDateTime, z. B. `2026-06-20T14:00:00+02:00[Europe/Berlin]`
 *  - ganztägig: ISO-Datum, z. B. `2026-06-20`
 */
data class EventData(
    val title: String,
    val start: String,
    val end: String,
    val allDay: Boolean = false,
    val location: String = "",
    val description: String = "",
    val reminderMinutes: Int? = null,
    val recurrence: Recurrence = Recurrence.NONE,
    val busy: Boolean = true,
)

/**
 * Serialisiert [EventData] als Markdown und zurück. Aufbau:
 * ```
 * <Titel>
 *
 * ```event
 * start: 2026-06-20T14:00:00+02:00[Europe/Berlin]
 * end: 2026-06-20T15:00:00+02:00[Europe/Berlin]
 * allDay: false
 * location: …
 * rrule: FREQ=WEEKLY
 * reminder: 10
 * busy: true
 * ```
 *
 * <freie Beschreibung in Markdown>
 * ```
 */
object EventCodec {
    private const val OPEN = "```event"
    private const val FENCE = "```"

    fun isEvent(text: String): Boolean = text.contains(OPEN)

    fun encode(e: EventData): String = buildString {
        append(e.title.trim()).append("\n\n")
        append(OPEN).append('\n')
        append("start: ").append(e.start).append('\n')
        append("end: ").append(e.end).append('\n')
        append("allDay: ").append(e.allDay).append('\n')
        if (e.location.isNotBlank()) append("location: ").append(e.location.replace("\n", " ")).append('\n')
        e.recurrence.rrule?.let { append("rrule: ").append(it).append('\n') }
        e.reminderMinutes?.let { append("reminder: ").append(it).append('\n') }
        append("busy: ").append(e.busy).append('\n')
        append(FENCE)
        if (e.description.isNotBlank()) append("\n\n").append(e.description.trim())
    }

    fun parse(text: String): EventData? {
        val open = text.indexOf(OPEN)
        if (open < 0) return null
        val title = text.substring(0, open).trim().substringBefore('\n').trim()
        val afterOpen = open + OPEN.length
        val close = text.indexOf("\n$FENCE", afterOpen)
        if (close < 0) return null
        val block = text.substring(afterOpen, close)
        val desc = text.substring(close + 1 + FENCE.length).trim()
        val map = HashMap<String, String>()
        for (line in block.split('\n')) {
            val i = line.indexOf(':')
            if (i <= 0) continue
            map[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val start = map["start"]?.takeIf { it.isNotBlank() } ?: return null
        return EventData(
            title = title,
            start = start,
            end = map["end"]?.takeIf { it.isNotBlank() } ?: start,
            allDay = map["allday"].toBoolStrict(),
            location = map["location"].orEmpty(),
            description = desc,
            reminderMinutes = map["reminder"]?.toIntOrNull(),
            recurrence = Recurrence.fromRrule(map["rrule"]),
            busy = map["busy"]?.let { it.toBoolStrict() } ?: true,
        )
    }

    private fun String?.toBoolStrict(): Boolean = this?.trim()?.lowercase() == "true"
}

/**
 * Feed-Metadaten, die im Feed-Op (text) mitreisen, damit das „Kalender"-Flag mitsynct.
 * Zeile 1 = Name; eine Folgezeile `::kalender::` markiert einen Kalender-Feed.
 * Alte Feeds (nur Name) bleiben normale Feeds.
 */
object FeedMeta {
    private const val CAL_MARKER = "::kalender::"

    fun encode(name: String, calendar: Boolean): String =
        if (calendar) name.trim() + "\n" + CAL_MARKER else name.trim()

    fun decodeName(text: String): String = text.substringBefore('\n').trim()

    fun decodeCalendar(text: String): Boolean =
        text.split('\n').any { it.trim() == CAL_MARKER }
}
