package de.beardedskunk.clipsharing.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextDiffTest {

    /** Aus den Segmenten muss sich Original (ohne INSERT) und Ziel (ohne DELETE) ergeben. */
    private fun reconstructA(segs: List<DiffSegment>) =
        segs.filter { it.op != DiffOp.INSERT }.joinToString("") { it.text }

    private fun reconstructB(segs: List<DiffSegment>) =
        segs.filter { it.op != DiffOp.DELETE }.joinToString("") { it.text }

    @Test
    fun equalTexts_areAllEqual() {
        val segs = TextDiff.diff("hallo welt", "hallo welt")
        assertTrue(segs.all { it.op == DiffOp.EQUAL })
        assertEquals("hallo welt", reconstructA(segs))
    }

    @Test
    fun wordChange_isShownAsDeleteAndInsert() {
        val segs = TextDiff.diff("Treffen um 18 Uhr", "Treffen um 20 Uhr")
        assertTrue(segs.any { it.op == DiffOp.DELETE && it.text.contains("18") })
        assertTrue(segs.any { it.op == DiffOp.INSERT && it.text.contains("20") })
        assertEquals("Treffen um 18 Uhr", reconstructA(segs))
        assertEquals("Treffen um 20 Uhr", reconstructB(segs))
    }

    @Test
    fun pureInsertion_andDeletion_reconstruct() {
        val ins = TextDiff.diff("a b", "a b c")
        assertEquals("a b", reconstructA(ins))
        assertEquals("a b c", reconstructB(ins))

        val del = TextDiff.diff("a b c", "a c")
        assertEquals("a b c", reconstructA(del))
        assertEquals("a c", reconstructB(del))
    }
}
