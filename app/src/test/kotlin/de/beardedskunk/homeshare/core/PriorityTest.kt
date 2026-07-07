package de.beardedskunk.homeshare.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Reine Band-Mathematik: [Priority.dueBand]-Schwellen, [Priority.handBand], [Priority.dueSortInstant],
 * sowie die Drop-/Rekey-Planung ([resolveDrop], [rekeyPlan]).
 */
class PriorityTest {

    private val now = LocalDateTime.of(2026, 7, 7, 12, 0)
    private val today: LocalDate = now.toLocalDate()

    // ---- dueBand ----

    @Test fun dueBand_timedTodayFuture_red() =
        assertEquals(PrioBand.RED, Priority.dueBand(today, LocalTime.of(18, 0), now))

    @Test fun dueBand_timedTodayPast_overdue() =
        assertEquals(PrioBand.OVERDUE, Priority.dueBand(today, LocalTime.of(9, 0), now))

    @Test fun dueBand_timedExactlyNow_overdue() =
        assertEquals(PrioBand.OVERDUE, Priority.dueBand(today, LocalTime.of(12, 0), now))

    @Test fun dueBand_allDayToday_red_notOverdue() =
        assertEquals(PrioBand.RED, Priority.dueBand(today, null, now))

    @Test fun dueBand_allDayYesterday_overdue() =
        assertEquals(PrioBand.OVERDUE, Priority.dueBand(today.minusDays(1), null, now))

    @Test fun dueBand_thresholds() {
        assertEquals(PrioBand.RED, Priority.dueBand(today.plusDays(1), null, now))
        assertEquals(PrioBand.ORANGE, Priority.dueBand(today.plusDays(2), null, now))
        assertEquals(PrioBand.ORANGE, Priority.dueBand(today.plusDays(7), null, now))
        assertEquals(PrioBand.YELLOW, Priority.dueBand(today.plusDays(8), null, now))
        assertEquals(PrioBand.YELLOW, Priority.dueBand(today.plusDays(14), null, now))
        assertEquals(PrioBand.NONE, Priority.dueBand(today.plusDays(15), null, now))
    }

    // ---- dueSortInstant ----

    @Test fun dueSortInstant_timedBeforeAllDaySameDay() {
        val timed = Priority.dueSortInstant(today, LocalTime.of(9, 0))
        val allDay = Priority.dueSortInstant(today, null)
        assertTrue(timed.isBefore(allDay))
    }

    // ---- handBand ----

    @Test fun handBand_variants() {
        assertEquals(PrioBand.NONE, Priority.handBand(emptyMap()))
        assertEquals(PrioBand.NONE, Priority.handBand(mapOf(Priority.KEY_PRIO to "0")))
        assertEquals(PrioBand.NONE, Priority.handBand(mapOf(Priority.KEY_PRIO to "quatsch")))
        assertEquals(PrioBand.YELLOW, Priority.handBand(mapOf(Priority.KEY_PRIO to "1")))
        assertEquals(PrioBand.ORANGE, Priority.handBand(mapOf(Priority.KEY_PRIO to "2")))
        assertEquals(PrioBand.RED, Priority.handBand(mapOf(Priority.KEY_PRIO to "3")))
    }

    // ---- rekeyPlan ----

    private fun r(id: String, key: String, band: PrioBand = PrioBand.NONE, flex: Boolean = false) =
        Ranked(id, band, key, flex)

    @Test fun rekeyPlan_alreadySorted_empty() {
        assertTrue(rekeyPlan(listOf(r("a", "1"), r("b", "2"), r("c", "3"))).isEmpty())
    }

    @Test fun rekeyPlan_reversed_allButFirst_strictlyIncreasing() {
        val plan = rekeyPlan(listOf(r("a", "8"), r("b", "4"), r("c", "2")))
        assertEquals(2, plan.size)
        assertEquals(listOf("b", "c"), plan.map { it.first })
        // Neue Keys strikt steigend und über der ersten Grenze "8".
        assertTrue("8" < plan[0].second)
        assertTrue(plan[0].second < plan[1].second)
    }

    @Test fun rekeyPlan_blockUnderUpperBound() {
        // "5","2","3" müssen zwischen "5" (prev) und "9" (nächste gültige Grenze) landen.
        val plan = rekeyPlan(listOf(r("a", "1"), r("b", "5"), r("c", "2"), r("d", "3"), r("e", "9")))
        assertEquals(listOf("c", "d"), plan.map { it.first })
        assertTrue("5" < plan[0].second && plan[0].second < plan[1].second && plan[1].second < "9")
    }

    // ---- resolveDrop ----

    @Test fun resolveDrop_flexibleStaysInBand_reorderOnly() {
        val d = listOf(r("r1", "1", PrioBand.RED), r("m", "9", PrioBand.RED, flex = true), r("r2", "4", PrioBand.RED))
        val plan = resolveDrop(d, 1)
        assertNull(plan.newPrioLevel)
        assertEquals("1", plan.lo)
        assertEquals("4", plan.hi)
    }

    @Test fun resolveDrop_flexiblePromotedToNeighborBand() {
        val d = listOf(r("o1", "1", PrioBand.ORANGE), r("m", "5", PrioBand.YELLOW, flex = true), r("o2", "4", PrioBand.ORANGE))
        val plan = resolveDrop(d, 1)
        assertEquals(2, plan.newPrioLevel)
        assertEquals("1", plan.lo)
        assertEquals("4", plan.hi)
    }

    @Test fun resolveDrop_flexibleAmongOverdue_clampRed_snapTop() {
        val d = listOf(
            r("ov1", "1", PrioBand.OVERDUE),
            r("m", "9", PrioBand.YELLOW, flex = true),
            r("ov2", "4", PrioBand.OVERDUE),
            r("red1", "2", PrioBand.RED),
        )
        val plan = resolveDrop(d, 1)
        assertEquals(3, plan.newPrioLevel)
        assertNull(plan.lo)
        assertEquals("2", plan.hi)
    }

    @Test fun resolveDrop_dueTooHigh_snapTopOfOwnBand() {
        val d = listOf(
            r("ov1", "1", PrioBand.OVERDUE),
            r("m", "5", PrioBand.RED, flex = false),
            r("ov2", "4", PrioBand.OVERDUE),
            r("red1", "2", PrioBand.RED),
        )
        val plan = resolveDrop(d, 1)
        assertNull(plan.newPrioLevel)
        assertNull(plan.lo)
        assertEquals("2", plan.hi)
    }

    @Test fun resolveDrop_dueTooLow_snapBottomOfOwnBand() {
        val d = listOf(
            r("red1", "2", PrioBand.RED),
            r("y1", "1", PrioBand.YELLOW),
            r("m", "5", PrioBand.RED, flex = false),
            r("y2", "4", PrioBand.YELLOW),
        )
        val plan = resolveDrop(d, 2)
        assertNull(plan.newPrioLevel)
        assertEquals("2", plan.lo)
        assertNull(plan.hi)
    }

    @Test fun resolveDrop_dueOnlyMemberOfBand_skip() {
        val d = listOf(r("y1", "1", PrioBand.YELLOW), r("m", "5", PrioBand.RED, flex = false), r("y2", "4", PrioBand.YELLOW))
        assertTrue(resolveDrop(d, 1).skip)
    }

    @Test fun resolveDrop_noOpDrop_skip() {
        val d = listOf(r("r1", "1", PrioBand.RED), r("m", "3", PrioBand.RED, flex = true), r("r2", "4", PrioBand.RED))
        assertTrue(resolveDrop(d, 1).skip)
    }
}
