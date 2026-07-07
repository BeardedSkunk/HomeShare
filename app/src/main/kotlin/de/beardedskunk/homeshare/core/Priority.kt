package de.beardedskunk.homeshare.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * Prioritäts-Bänder für Aufgaben (absteigend wichtig). Reine Wert-Mathematik — kein NodeState,
 * keine DB (JVM-Unit-testbar). Die Brücke zu [de.beardedskunk.homeshare.data.NodeState] liegt in
 * data/PrioritySort.kt.
 *
 * | Band       | level | Bedingung                               | Farbe        |
 * |------------|-------|-----------------------------------------|--------------|
 * | ÜBERFÄLLIG | 4     | nur automatisch: Termin verstrichen     | Bright Red   |
 * | ROT        | 3     | Due heute/morgen ODER Hand-Prio 3       | Rot          |
 * | ORANGE     | 2     | Due in 2–7 Tagen ODER Hand-Prio 2       | Orange       |
 * | GELB       | 1     | Due in 8–14 Tagen ODER Hand-Prio 1      | Gelb         |
 * | KEINE      | 0     | Due > 14 Tage, keine Prio, erledigt …   | keine        |
 */
enum class PrioBand(val level: Int) { NONE(0), YELLOW(1), ORANGE(2), RED(3), OVERDUE(4) }

object Priority {
    /** ext-Key der Hand-Priorität ("1".."3"); fehlt/0 = keine. NICHT in [MetaKey.KNOWN] aufnehmen! */
    const val KEY_PRIO = "prio"

    /** ext-Key am Container: "1" = Kinder nach Priorität sortieren. NICHT in [MetaKey.KNOWN]! */
    const val KEY_SORT = "prioSort"

    fun handBand(ext: Map<String, String>): PrioBand =
        when (ext[KEY_PRIO]?.toIntOrNull() ?: 0) {
            1 -> PrioBand.YELLOW
            2 -> PrioBand.ORANGE
            3 -> PrioBand.RED
            else -> PrioBand.NONE
        }

    /**
     * Band aus dem Due-Zeitpunkt. [time] null = ganztägig (überfällig erst ab dem Folgetag; ein
     * getimter Termin dagegen, sobald der Zeitpunkt selbst verstrichen ist).
     */
    fun dueBand(day: LocalDate, time: LocalTime?, now: LocalDateTime): PrioBand {
        val overdue = if (time != null) !LocalDateTime.of(day, time).isAfter(now)
        else day.isBefore(now.toLocalDate())
        if (overdue) return PrioBand.OVERDUE
        return when (ChronoUnit.DAYS.between(now.toLocalDate(), day)) {
            in Long.MIN_VALUE..1L -> PrioBand.RED // heute/morgen (Überfälliges ist oben schon raus)
            in 2L..7L -> PrioBand.ORANGE
            in 8L..14L -> PrioBand.YELLOW
            else -> PrioBand.NONE
        }
    }

    /**
     * Sortier-Zeitpunkt innerhalb eines Bands: getimt = echter Zeitpunkt, All-Day = Tagesende
     * ([LocalTime.MAX], sortiert damit hinter alle getimten Einträge desselben Tages).
     */
    fun dueSortInstant(day: LocalDate, time: LocalTime?): LocalDateTime =
        LocalDateTime.of(day, time ?: LocalTime.MAX)
}

/**
 * Eine Zeile für Band-Sortier-Rechnungen. [effKey] = [OrderKeys.effective]; [flexible] = true nur
 * für unerledigte TODO OHNE Due-Date (Prio per Drag zwischen Bändern änderbar).
 */
data class Ranked(val nodeId: String, val band: PrioBand, val effKey: String, val flexible: Boolean)

/**
 * Ergebnis eines Drops in der auto-sortierten Liste: [newPrioLevel] = neuer Hand-Prio-Level
 * (null = unverändert, 0 = Key entfernen) + orderKey-Grenzen [lo]/[hi]. [skip] = Position schon
 * korrekt, es wird nichts geschrieben.
 */
data class DropPlan(val newPrioLevel: Int?, val lo: String?, val hi: String?, val skip: Boolean = false)

/**
 * Plant einen Drop in der auto-sortierten Liste. [displayed] = Anzeige-Reihenfolge NACH dem Zug
 * (das gezogene Element steht an Index [to], alle übrigen sind untereinander bandsortiert).
 *
 * - Flexible Zeile (No-Due-TODO): rutscht sie in ein anderes Band, wird ihre Hand-Prio auf das
 *   Ziel-Band gesetzt (max. ROT; ÜBERFÄLLIG ist nicht vergebbar → Snap oben ins ROT-Band).
 * - Band-fixe Zeile (Due, erledigt, Nicht-TODO): landet immer im eigenen Band — zu hoch gezogen
 *   ganz oben, zu tief ganz unten, sonst zwischen den Band-Nachbarn.
 */
fun resolveDrop(displayed: List<Ranked>, to: Int): DropPlan {
    val moved = displayed[to]
    val slotHi = displayed.getOrNull(to - 1)?.band?.level ?: 4 // höchstes an dieser Stelle erlaubtes Band
    val slotLo = displayed.getOrNull(to + 1)?.band?.level ?: 0 // niedrigstes

    // Nächster Nachbar mit Band-Level [level] oberhalb (kleinerer Index) bzw. unterhalb von [to].
    fun keyAbove(level: Int): String? {
        for (i in to - 1 downTo 0) if (displayed[i].band.level == level) return displayed[i].effKey
        return null
    }
    fun keyBelow(level: Int): String? {
        for (i in to + 1 until displayed.size) if (displayed[i].band.level == level) return displayed[i].effKey
        return null
    }
    // Erstes/letztes Mitglied eines Bands in der gesamten Liste (moved ausgenommen).
    fun firstOf(level: Int): String? =
        displayed.filterIndexed { i, r -> i != to && r.band.level == level }.firstOrNull()?.effKey
    fun lastOf(level: Int): String? =
        displayed.filterIndexed { i, r -> i != to && r.band.level == level }.lastOrNull()?.effKey

    var lo: String?
    var hi: String?
    var newPrioLevel: Int?

    if (moved.flexible) {
        val ziel = moved.band.level.coerceIn(slotLo, slotHi).coerceAtMost(3)
        if (ziel < slotLo) {
            // No-Due-Aufgabe zwischen Überfällige gezogen: Snap ganz oben ins ROT-Band.
            lo = null
            hi = firstOf(3)
        } else {
            lo = keyAbove(ziel)
            hi = keyBelow(ziel)
        }
        newPrioLevel = if (ziel != moved.band.level) ziel else null
    } else {
        val b = moved.band.level
        when {
            b in slotLo..slotHi -> {
                lo = keyAbove(b)
                hi = keyBelow(b)
            }
            b < slotLo -> { // zu hoch gezogen → ganz oben im eigenen Band
                lo = null
                hi = firstOf(b)
                if (hi == null) return DropPlan(null, null, null, skip = true) // Band sonst leer
            }
            else -> { // zu tief gezogen → ganz unten im eigenen Band
                lo = lastOf(b)
                if (lo == null) return DropPlan(null, null, null, skip = true)
                hi = null
            }
        }
        newPrioLevel = null
    }

    val loF = lo
    val hiF = hi
    if (loF != null && hiF != null && loF >= hiF) hi = null // Defensive wie reorderNode
    // Kein-Op-Drop: kein Band-Wechsel und der Schlüssel liegt schon zwischen den Nachbarn.
    if (newPrioLevel == null &&
        (lo == null || lo < moved.effKey) &&
        (hi == null || moved.effKey < hi)
    ) {
        return DropPlan(null, lo, hi, skip = true)
    }
    return DropPlan(newPrioLevel, lo, hi)
}

/**
 * Minimale orderKey-Neuvergabe (greedy), damit die effKey-Reihenfolge von [target] strikt
 * monoton steigt. Gibt nur die zu ändernden (nodeId, neuerKey)-Paare zurück; bereits korrekt
 * einsortierte Knoten bleiben unberührt.
 */
fun rekeyPlan(target: List<Ranked>): List<Pair<String, String>> {
    val plan = ArrayList<Pair<String, String>>()
    var prev: String? = null
    var i = 0
    while (i < target.size) {
        val key = target[i].effKey
        if (prev == null || key > prev) {
            prev = key
            i++
        } else {
            // Block [i, j) muss neu vergeben werden, bis wieder ein Schlüssel > lower auftaucht.
            val lower = prev
            var j = i + 1
            while (j < target.size && !(target[j].effKey > lower)) j++
            val hi = if (j < target.size) target[j].effKey else null
            var below: String? = lower
            for (k in i until j) {
                val nk = OrderKeys.between(below, hi)
                plan += target[k].nodeId to nk
                below = nk
            }
            prev = below
            i = j
        }
    }
    return plan
}
