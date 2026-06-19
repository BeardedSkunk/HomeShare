package de.beardedskunk.homeshare.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvictionPlannerTest {

    private fun blob(sha: String, bytes: Long, age: Long, backed: Boolean = false) =
        BlobInfo(sha, bytes, age, backed)

    @Test
    fun underBudget_evictsNothing() {
        val plan = EvictionPlanner.plan(
            listOf(blob("a", 100, 1), blob("b", 100, 2)),
            maxBytes = 1000,
        )
        assertTrue(plan.toEvict.isEmpty())
        assertTrue(plan.unconfirmed.isEmpty())
    }

    @Test
    fun overBudget_evictsOldestFirst_untilUnderBudget() {
        val blobs = listOf(
            blob("new", 100, age = 30),
            blob("old", 100, age = 10),
            blob("mid", 100, age = 20),
        )
        // Budget 150 -> 300 vorhanden, 150 muessen weg -> aelteste (old, mid).
        val plan = EvictionPlanner.plan(blobs, maxBytes = 150)
        assertEquals(listOf("old", "mid"), plan.toEvict)
    }

    @Test
    fun unconfirmed_areFlagged_butStillEvicted() {
        val blobs = listOf(
            blob("old", 100, age = 1, backed = false),
            blob("new", 100, age = 2, backed = true),
        )
        val plan = EvictionPlanner.plan(blobs, maxBytes = 100)
        assertEquals(listOf("old"), plan.toEvict)
        assertEquals(listOf("old"), plan.unconfirmed) // nicht anderswo gesichert -> warnen
    }
}
