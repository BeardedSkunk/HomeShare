package de.beardedskunk.homeshare.data

import de.beardedskunk.homeshare.core.Hlc
import de.beardedskunk.homeshare.core.NodeType
import de.beardedskunk.homeshare.core.PrioBand
import de.beardedskunk.homeshare.core.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Brücke NodeState ↔ Band: [PrioritySort.dueMoment], [PrioritySort.bandOf], [PrioritySort.displaySort]
 * und die einmalige [PrioritySort.materializeOrder].
 */
class PrioritySortTest {

    private val now = LocalDateTime.of(2026, 7, 7, 12, 0)
    private var seq = 0L

    private fun st(
        id: String,
        type: NodeType = NodeType.TODO,
        orderKey: String = "",
        done: Boolean = false,
        ext: Map<String, String> = emptyMap(),
    ) = NodeState(
        nodeId = id, parentId = "list", rootId = "feed", type = type, headVersionId = "h-$id",
        orderKey = orderKey, text = id, done = done, ext = ext,
        created = Hlc(++seq, 0), updated = Hlc(seq, 0),
    )

    private fun allDayText(day: String) =
        EventCodec.encode(EventData(title = "Fällig", start = day, end = day, allDay = true))

    // ---- dueMoment ----

    @Test fun dueMoment_allDay_timeNull() {
        val due = st("d", type = NodeType.CALENDAR).copy(text = allDayText("2026-07-20"))
        val m = PrioritySort.dueMoment(due)!!
        assertEquals("2026-07-20", m.day.toString())
        assertNull(m.time)
    }

    @Test fun dueMoment_timed_localTime() {
        val zone = ZoneId.systemDefault()
        val zdt = ZonedDateTime.of(2026, 7, 20, 9, 0, 0, 0, zone)
        val text = EventCodec.encode(EventData(title = "Fällig", start = zdt.toString(), end = zdt.toString(), allDay = false))
        val m = PrioritySort.dueMoment(st("d", type = NodeType.CALENDAR).copy(text = text))!!
        assertEquals("2026-07-20", m.day.toString())
        assertEquals(LocalTime.of(9, 0), m.time)
    }

    @Test fun dueMoment_garbage_null() {
        assertNull(PrioritySort.dueMoment(st("d", type = NodeType.CALENDAR).copy(text = "kein event")))
    }

    // ---- bandOf ----

    @Test fun bandOf_doneTodo_isNone() {
        val n = st("t", done = true)
        assertEquals(PrioBand.NONE, PrioritySort.bandOf(n, DueMoment(now.toLocalDate().plusDays(1), null), now))
    }

    @Test fun bandOf_noteWithPrio_isNone() {
        val n = st("t", type = NodeType.TEXT, ext = mapOf(Priority.KEY_PRIO to "3"))
        assertEquals(PrioBand.NONE, PrioritySort.bandOf(n, null, now))
    }

    @Test fun bandOf_handPrioMasksDue() {
        val n = st("t", ext = mapOf(Priority.KEY_PRIO to "1"))
        // Bunte Hand-Prio maskiert den Termin: GELB (Prio), nicht RED (Due morgen).
        assertEquals(PrioBand.YELLOW, PrioritySort.bandOf(n, DueMoment(now.toLocalDate().plusDays(1), null), now))
    }

    @Test fun bandOf_dueWhenNoHandPrio() {
        val n = st("t")
        // Ohne Hand-Prio leitet der Termin das Band ab: Due morgen = RED.
        assertEquals(PrioBand.RED, PrioritySort.bandOf(n, DueMoment(now.toLocalDate().plusDays(1), null), now))
    }

    @Test fun bandOf_handPrioWhenNoDue() {
        val n = st("t", ext = mapOf(Priority.KEY_PRIO to "2"))
        assertEquals(PrioBand.ORANGE, PrioritySort.bandOf(n, null, now))
    }

    // ---- displaySort ----

    @Test fun displaySort_bandsDescending_stableWithinBand() {
        val a = st("a", orderKey = "1", ext = mapOf(Priority.KEY_PRIO to "1")) // GELB
        val b = st("b", orderKey = "2", ext = mapOf(Priority.KEY_PRIO to "3")) // ROT
        val c = st("c", orderKey = "3", ext = mapOf(Priority.KEY_PRIO to "1")) // GELB
        val d = st("d", orderKey = "4") // KEINE
        val sorted = PrioritySort.displaySort(listOf(a, b, c, d), emptyMap(), now)
        // ROT zuerst, dann die beiden GELB in Eingangsreihenfolge (a vor c), dann KEINE.
        assertEquals(listOf("b", "a", "c", "d"), sorted.map { it.nodeId })
    }

    // ---- materializeOrder ----

    @Test fun materializeOrder_dueBlockByTime_thenRest() {
        val zone = ZoneId.systemDefault()
        val tomorrow = now.toLocalDate().plusDays(1) // morgen = RED
        fun timed(h: Int) = ZonedDateTime.of(tomorrow.year, tomorrow.monthValue, tomorrow.dayOfMonth, h, 0, 0, 0, zone)
        // Alle im RED-Band: zwei getimte Due-Tasks + eine All-Day-Due + eine Hand-Prio-3 ohne Due.
        val late = st("late", orderKey = "1")
        val early = st("early", orderKey = "2")
        val allday = st("allday", orderKey = "3")
        val hand = st("hand", orderKey = "4", ext = mapOf(Priority.KEY_PRIO to "3"))
        val dues = mapOf(
            "late" to DueMoment(timed(19).toLocalDate(), timed(19).toLocalTime()),
            "early" to DueMoment(timed(8).toLocalDate(), timed(8).toLocalTime()),
            "allday" to DueMoment(tomorrow, null),
        )
        val order = PrioritySort.materializeOrder(listOf(late, early, allday, hand), dues, now)
        // Due-Block nach Zeit: early(08) < late(19) < allday(Tagesende); dahinter der Rest (hand).
        assertEquals(listOf("early", "late", "allday", "hand"), order.map { it.nodeId })
    }

    @Test fun materializeOrder_doneDueTask_notInDueBlock() {
        val a = st("a", orderKey = "1") // unerledigt, Hand-Prio ROT
            .copy(ext = mapOf(Priority.KEY_PRIO to "3"))
        val doneDue = st("done", orderKey = "2", done = true) // erledigt → KEINE, trotz Due
        val dues = mapOf("done" to DueMoment(now.toLocalDate().plusDays(1), null))
        val order = PrioritySort.materializeOrder(listOf(a, doneDue), dues, now)
        // a (ROT) vor done (KEINE); done ist NICHT im ROT-Due-Block gelandet.
        assertEquals(listOf("a", "done"), order.map { it.nodeId })
    }
}
