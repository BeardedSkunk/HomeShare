package de.beardedskunk.homeshare.data

import de.beardedskunk.homeshare.core.NodeKind
import de.beardedskunk.homeshare.core.OrderKeys
import de.beardedskunk.homeshare.core.PrioBand
import de.beardedskunk.homeshare.core.Priority
import de.beardedskunk.homeshare.core.Ranked
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Fälligkeits-Zeitpunkt einer Aufgabe: Tag + Uhrzeit ([time] null = ganztägig). */
data class DueMoment(val day: LocalDate, val time: LocalTime?)

/**
 * Brücke zwischen [NodeState] und der reinen Band-Mathematik in
 * [de.beardedskunk.homeshare.core.Priority]. Reine Funktionen (keine DB) — die Repository-
 * Schreibpfade liegen in [FeedRepository] (setPriority/setPrioritySort/applyPriorityDrop).
 */
object PrioritySort {
    /** Auto-Sortierung am Container-Knoten aktiviert? */
    fun enabled(container: NodeState?): Boolean = container?.ext?.get(Priority.KEY_SORT) == "1"

    /** [DueMoment] aus dem Due-Kindknoten: getimt → ZonedDateTime in Systemzone, sonst nur Tag. */
    fun dueMoment(due: NodeState): DueMoment? = EventCodec.parse(due.text)?.let { e ->
        val zdt = if (!e.allDay) runCatching {
            ZonedDateTime.parse(e.start.trim()).withZoneSameInstant(ZoneId.systemDefault())
        }.getOrNull() else null
        if (zdt != null) {
            DueMoment(zdt.toLocalDate(), zdt.toLocalTime())
        } else {
            runCatching { LocalDate.parse(e.start.trim().take(10)) }.getOrNull()?.let { DueMoment(it, null) }
        }
    }

    /**
     * Effektives Band einer Zeile. Erledigte und Nicht-TODO-Zeilen sind immer [PrioBand.NONE];
     * ein vorhandenes Due-Date schlägt die Hand-Prio.
     */
    fun bandOf(n: NodeState, due: DueMoment?, now: LocalDateTime): PrioBand = when {
        n.kind != NodeKind.TODO || n.done -> PrioBand.NONE
        due != null -> Priority.dueBand(due.day, due.time, now)
        else -> Priority.handBand(n.ext)
    }

    fun ranked(n: NodeState, due: DueMoment?, now: LocalDateTime): Ranked = Ranked(
        n.nodeId,
        bandOf(n, due, now),
        OrderKeys.effective(n.orderKey, n.created),
        flexible = n.kind == NodeKind.TODO && !n.done && due == null,
    )

    /**
     * Anzeige-Sortierung: Band absteigend, innerhalb eines Bands bleibt die Eingangsreihenfolge
     * (die Aufrufer liefern bereits siblingOrder-sortiert → stabiles [sortedByDescending] genügt).
     */
    fun displaySort(list: List<NodeState>, dues: Map<String, DueMoment>, now: LocalDateTime): List<NodeState> =
        list.sortedByDescending { bandOf(it, dues[it.nodeId], now).level }

    /**
     * Zielreihenfolge fürs frische Einschalten der Auto-Sortierung: pro Band (absteigend) zuerst
     * die unerledigten Due-Aufgaben nach [Priority.dueSortInstant] aufsteigend (getimt vor All-Day
     * desselben Tages), dahinter der Rest in bisheriger Reihenfolge.
     */
    fun materializeOrder(list: List<NodeState>, dues: Map<String, DueMoment>, now: LocalDateTime): List<Ranked> {
        val ranked = list.map { ranked(it, dues[it.nodeId], now) }
        val byId = list.associateBy { it.nodeId }
        val out = ArrayList<Ranked>(ranked.size)
        for (level in 4 downTo 0) {
            val band = ranked.filter { it.band.level == level }
            val (dueBlock, rest) = band.partition {
                val n = byId.getValue(it.nodeId)
                val m = dues[it.nodeId]
                m != null && !n.done && n.kind == NodeKind.TODO
            }
            out += dueBlock.sortedBy { Priority.dueSortInstant(dues.getValue(it.nodeId).day, dues.getValue(it.nodeId).time) }
            out += rest
        }
        return out
    }
}
