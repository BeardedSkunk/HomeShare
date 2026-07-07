package de.beardedskunk.homeshare.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/**
 * Wiederholungsregel für Aufgaben: iCal-RRULE-kompatible **Untermenge mit Tages-Granularität**
 * (bewusst keine Stunden/Minuten). Abgedeckt:
 *
 *  - `FREQ=DAILY|WEEKLY|MONTHLY|YEARLY` + `INTERVAL=n` (alle n Tage/Wochen/Monate/Jahre)
 *  - wöchentlich an bestimmten Wochentagen: `BYDAY=MO,FR`
 *  - monatlich an Monatstagen: `BYMONTHDAY=1,15,-1` (-1 = letzter Tag des Monats)
 *  - monatlich nach Position: `BYDAY=3MO` (3. Montag), `BYDAY=-1WE` (letzter Mittwoch);
 *    Ordinale 1..4 und -1 — „vorletzter" u. ä. gibt es bewusst nicht (UI-Deckelung)
 *  - Ende: `UNTIL=<Datum>` oder `COUNT=n` (n = verbleibende Kopien, zählt pro erzeugter Kopie
 *    herunter, siehe [decremented])
 *
 * Der String reist als ext-Meta-Key am Aufgabenknoten (siehe TaskRepeat) — kein Format-Bump nötig.
 */
data class RRule(
    val freq: Freq,
    val interval: Int = 1,
    val byDay: List<ByDay> = emptyList(),
    val byMonthDay: List<Int> = emptyList(),
    val until: LocalDate? = null,
    val count: Int? = null,
) {
    enum class Freq { DAILY, WEEKLY, MONTHLY, YEARLY }

    /** Wochentag mit Position im Monat: [ordinal] 0 = „jeder" (wöchentlich), 1..4, -1 = letzter. */
    data class ByDay(val ordinal: Int, val day: DayOfWeek)

    /** Kanonischer RRULE-String (Roundtrip-stabil zu [parse]). */
    fun format(): String = buildString {
        append("FREQ=").append(freq.name)
        if (interval != 1) append(";INTERVAL=").append(interval)
        if (byDay.isNotEmpty()) {
            append(";BYDAY=")
            append(byDay.joinToString(",") { (o, d) -> (if (o != 0) o.toString() else "") + CODE_OF.getValue(d) })
        }
        if (byMonthDay.isNotEmpty()) append(";BYMONTHDAY=").append(byMonthDay.joinToString(","))
        until?.let { append(";UNTIL=").append(it.format(DateTimeFormatter.BASIC_ISO_DATE)) }
        count?.let { append(";COUNT=").append(it) }
    }

    /**
     * Nächstes Vorkommen STRIKT nach [anchor]; null wenn die Regel erschöpft ist (COUNT aufgebraucht
     * oder Ergebnis hinter UNTIL). Für Catch-up nach langer App-Pause einfach wiederholt aufrufen,
     * bis das Ergebnis in der Zukunft liegt (es entsteht trotzdem nur EINE Kopie).
     */
    fun nextAfter(anchor: LocalDate): LocalDate? {
        if (count != null && count <= 0) return null
        val next = when (freq) {
            Freq.DAILY -> anchor.plusDays(interval.toLong())
            Freq.WEEKLY -> if (byDay.isEmpty()) anchor.plusWeeks(interval.toLong()) else nextWeekly(anchor)
            Freq.MONTHLY -> when {
                byMonthDay.isNotEmpty() -> nextMonthlyByMonthDay(anchor)
                byDay.isNotEmpty() -> nextMonthlyByWeekday(anchor)
                else -> anchor.plusMonths(interval.toLong())
            }
            Freq.YEARLY -> anchor.plusYears(interval.toLong())
        } ?: return null
        return if (until != null && next.isAfter(until)) null else next
    }

    /** Regel für die erzeugte Kopie: COUNT zählt pro Kopie herunter; null = Kette endet hier. */
    fun decremented(): RRule? = when {
        count == null -> this
        count <= 1 -> null
        else -> copy(count = count - 1)
    }

    /** Wochen-Raster ist an der Woche des Ankers verankert (Montag als Wochenbeginn). */
    private fun nextWeekly(anchor: LocalDate): LocalDate? {
        val days = byDay.map { it.day }.toSet()
        val anchorWeek = weekIndex(anchor)
        var d = anchor.plusDays(1)
        repeat(7 * interval + 7) {
            if (d.dayOfWeek in days && (weekIndex(d) - anchorWeek) % interval == 0L) return d
            d = d.plusDays(1)
        }
        return null
    }

    /** Monats-Raster am Monat des Ankers verankert; nicht existierende Tage (30. Februar) fallen aus. */
    private fun nextMonthlyByMonthDay(anchor: LocalDate): LocalDate? {
        for (k in 0..48) {
            val month = anchor.withDayOfMonth(1).plusMonths(k.toLong() * interval)
            val len = month.lengthOfMonth()
            byMonthDay.mapNotNull { raw ->
                val d = if (raw < 0) len + 1 + raw else raw
                if (d in 1..len) month.withDayOfMonth(d) else null
            }.filter { it.isAfter(anchor) }.minOrNull()?.let { return it }
        }
        return null
    }

    private fun nextMonthlyByWeekday(anchor: LocalDate): LocalDate? {
        for (k in 0..48) {
            val month = anchor.withDayOfMonth(1).plusMonths(k.toLong() * interval)
            byDay.map { (ord, day) ->
                if (ord == -1) month.with(TemporalAdjusters.lastInMonth(day))
                else month.with(TemporalAdjusters.dayOfWeekInMonth(ord.coerceAtLeast(1), day))
            }.filter { it.isAfter(anchor) }.minOrNull()?.let { return it }
        }
        return null
    }

    /** Deutscher Beschreibungstext für die UI, z. B. „monatlich am 3. Montag, noch 5-mal". */
    fun summary(): String = buildString {
        when (freq) {
            Freq.DAILY -> append(if (interval == 1) "täglich" else "alle $interval Tage")
            Freq.WEEKLY -> {
                append(if (interval == 1) "wöchentlich" else "alle $interval Wochen")
                if (byDay.isNotEmpty()) {
                    append(" am ").append(joinGerman(byDay.map { GERMAN_DAY.getValue(it.day) }))
                }
            }
            Freq.MONTHLY -> {
                append(if (interval == 1) "monatlich" else "alle $interval Monate")
                if (byMonthDay.isNotEmpty()) {
                    append(" am ").append(joinGerman(byMonthDay.map { if (it == -1) "letzten Tag" else "$it." }))
                } else if (byDay.isNotEmpty()) {
                    append(" am ").append(joinGerman(byDay.map { (o, d) ->
                        (if (o == -1) "letzten" else "$o.") + " " + GERMAN_DAY.getValue(d)
                    }))
                }
            }
            Freq.YEARLY -> append(if (interval == 1) "jährlich" else "alle $interval Jahre")
        }
        until?.let { append(", bis ").append(it.format(GERMAN_DATE)) }
        count?.let { append(", noch $it-mal") }
    }

    companion object {
        private val CODE_OF = mapOf(
            DayOfWeek.MONDAY to "MO", DayOfWeek.TUESDAY to "TU", DayOfWeek.WEDNESDAY to "WE",
            DayOfWeek.THURSDAY to "TH", DayOfWeek.FRIDAY to "FR", DayOfWeek.SATURDAY to "SA",
            DayOfWeek.SUNDAY to "SU",
        )
        private val DAY_OF = CODE_OF.entries.associate { (d, c) -> c to d }
        private val GERMAN_DAY = mapOf(
            DayOfWeek.MONDAY to "Montag", DayOfWeek.TUESDAY to "Dienstag", DayOfWeek.WEDNESDAY to "Mittwoch",
            DayOfWeek.THURSDAY to "Donnerstag", DayOfWeek.FRIDAY to "Freitag", DayOfWeek.SATURDAY to "Samstag",
            DayOfWeek.SUNDAY to "Sonntag",
        )
        private val GERMAN_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")

        private fun weekIndex(d: LocalDate): Long = Math.floorDiv(d.toEpochDay() + 3, 7)

        private fun joinGerman(parts: List<String>): String = when (parts.size) {
            0 -> ""
            1 -> parts[0]
            else -> parts.dropLast(1).joinToString(", ") + " und " + parts.last()
        }

        /** Toleranter Parser; null bei fehlendem/unbekanntem FREQ. Fremde Keys werden ignoriert. */
        fun parse(s: String?): RRule? {
            if (s.isNullOrBlank()) return null
            val map = HashMap<String, String>()
            for (part in s.trim().split(';')) {
                val i = part.indexOf('=')
                if (i <= 0) continue
                map[part.substring(0, i).trim().uppercase()] = part.substring(i + 1).trim()
            }
            val freq = map["FREQ"]?.let { f -> Freq.entries.firstOrNull { it.name == f.uppercase() } } ?: return null
            return RRule(
                freq = freq,
                interval = (map["INTERVAL"]?.toIntOrNull() ?: 1).coerceAtLeast(1),
                byDay = map["BYDAY"]?.split(',')?.mapNotNull { parseByDay(it.trim()) } ?: emptyList(),
                byMonthDay = map["BYMONTHDAY"]?.split(',')
                    ?.mapNotNull { it.trim().toIntOrNull() }
                    ?.filter { it in 1..31 || it in -31..-1 }
                    ?: emptyList(),
                until = map["UNTIL"]?.let { parseUntil(it) },
                count = map["COUNT"]?.toIntOrNull(),
            )
        }

        private fun parseByDay(s: String): ByDay? {
            if (s.length < 2) return null
            val code = s.takeLast(2).uppercase()
            val day = DAY_OF[code] ?: return null
            val ord = s.dropLast(2).let { if (it.isEmpty()) 0 else it.toIntOrNull() ?: return null }
            return ByDay(ord, day)
        }

        /** UNTIL: iCal-Basic (`20260806`, auch mit Zeitanteil) oder ISO (`2026-08-06`). */
        private fun parseUntil(s: String): LocalDate? = runCatching {
            if (s.contains('-')) LocalDate.parse(s.take(10))
            else LocalDate.parse(s.take(8), DateTimeFormatter.BASIC_ISO_DATE)
        }.getOrNull()
    }
}
