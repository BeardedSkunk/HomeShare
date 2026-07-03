package de.beardedskunk.homeshare.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Invarianten der fraktionalen Sortierschlüssel:
 *  - between(a,b) liegt strikt zwischen den Grenzen und endet nie auf '0',
 *  - Seeds sind monoton in der HLC (ungeordnete Alt-Knoten behalten Erzeugungs-Reihenfolge),
 *  - wiederholtes Einfügen an derselben Grenze konvergiert nicht gegen Kollisionen (Fuzz),
 *  - orderKey-only-Konflikt wird per Last-Writer-Wins automatisch gemergt.
 */
class OrderKeysTest {

    @Test
    fun between_openBounds() {
        assertEquals("8", OrderKeys.between(null, null))
        assertTrue(OrderKeys.between("8", null) > "8")
        assertTrue(OrderKeys.between(null, "8") < "8")
    }

    @Test
    fun between_adjacentDigits_insertsBetween() {
        val k = OrderKeys.between("8", "9")
        assertTrue("8" < k && k < "9")
        val k2 = OrderKeys.between("8f", "9")
        assertTrue("8f" < k2 && k2 < "9")
        val k3 = OrderKeys.between("abc", "abd")
        assertTrue("abc" < k3 && k3 < "abd")
    }

    @Test
    fun between_neverEndsWithZero() {
        var lo: String? = null
        // Immer wieder direkt vor dieselbe obere Grenze einfügen.
        repeat(64) {
            val k = OrderKeys.between(lo, "1")
            assertFalse("Schlüssel '$k' endet auf 0", k.endsWith('0'))
            assertTrue((lo == null || lo!! < k) && k < "1")
            lo = k
        }
    }

    @Test
    fun between_fuzz_randomPairs() {
        val rnd = Random(42)
        val digits = "0123456789abcdef"
        repeat(500) {
            val a = (1..rnd.nextInt(1, 8)).map { digits[rnd.nextInt(16)] }.joinToString("")
            val b = (1..rnd.nextInt(1, 8)).map { digits[rnd.nextInt(16)] }.joinToString("")
            if (a >= b) return@repeat
            val k = OrderKeys.between(a, b)
            assertTrue("'$a' < '$k' < '$b' verletzt", a < k && k < b)
            assertFalse(k.endsWith('0'))
        }
    }

    @Test
    fun seed_isMonotoneInHlc() {
        val a = OrderKeys.seed(Hlc(1000, 0))
        val b = OrderKeys.seed(Hlc(1000, 1))
        val c = OrderKeys.seed(Hlc(1001, 0))
        assertTrue(a < b && b < c)
        // Und between() funktioniert auch zwischen Seeds (Drag zwischen Alt-Knoten).
        val k = OrderKeys.between(a, b)
        assertTrue(a < k && k < b)
    }

    @Test
    fun effective_prefersExplicitKey() {
        assertEquals("42", OrderKeys.effective("42", Hlc(99, 0)))
        assertEquals(OrderKeys.seed(Hlc(99, 0)), OrderKeys.effective("", Hlc(99, 0)))
    }

    // ---- Merge-Verhalten: Umsortieren darf nie einen manuellen Konflikt erzeugen ----

    private fun v(parents: Set<String>, device: String, wall: Long, text: String, order: String = "") =
        NodeVersion("n1", parents, device, Hlc(wall, 0), NodeContent(text = text, orderKey = order))

    @Test
    fun concurrentReorder_sameNode_autoMergesLastWriterWins() {
        val node = Node("n1")
        val base = v(emptySet(), "A", 1, "eintrag")
        val dragA = v(setOf(base.versionId), "A", 2, "eintrag", order = "4")
        val dragB = v(setOf(base.versionId), "B", 3, "eintrag", order = "c")
        listOf(base, dragA, dragB).forEach { node.ingest(it) }

        assertTrue(node.isConflicted())
        val merged = node.autoMergeContent()
        assertNotNull("Reiner Reorder-Konflikt muss automatisch mergen", merged)
        // Spätere Uhr (B, wall=3) gewinnt.
        assertEquals("c", merged!!.orderKey)
        assertEquals("eintrag", merged.text)
    }

    @Test
    fun reorderPlusTextEdit_bothMerge() {
        val node = Node("n1")
        val base = v(emptySet(), "A", 1, "zeile1\nzeile2")
        val dragA = v(setOf(base.versionId), "A", 2, "zeile1\nzeile2", order = "4")
        val editB = v(setOf(base.versionId), "B", 2, "zeile1\nzeile2 neu")
        listOf(base, dragA, editB).forEach { node.ingest(it) }

        val merged = node.autoMergeContent()
        assertNotNull(merged)
        assertEquals("4", merged!!.orderKey)
        assertEquals("zeile1\nzeile2 neu", merged.text)
    }

    @Test
    fun reorderVsDelete_staysManual() {
        val node = Node("n1")
        val base = v(emptySet(), "A", 1, "eintrag")
        val dragA = v(setOf(base.versionId), "A", 2, "eintrag", order = "4")
        val delB = NodeVersion("n1", setOf(base.versionId), "B", Hlc(2, 1), NodeContent(text = "eintrag", deleted = true))
        listOf(base, dragA, delB).forEach { node.ingest(it) }

        assertNull("Löschen-vs-Drag bleibt manuell", node.autoMergeContent())
    }
}
