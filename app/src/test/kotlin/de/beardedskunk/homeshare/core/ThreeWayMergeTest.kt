package de.beardedskunk.homeshare.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Sichert den deterministischen 3-Wege-Merge ab: nicht-überlappende Änderungen werden
 * automatisch zusammengeführt, echte Überlappungen melden Konflikt (null). Plus die für
 * die Konvergenz kritische Reihenfolge-Unabhängigkeit.
 */
class ThreeWayMergeTest {

    @Test fun nonOverlappingEdits_mergeCleanly() {
        val base = "Zeile1\nZeile2\nZeile3"
        val a = "Zeile1-NEU\nZeile2\nZeile3"   // A ändert oben
        val b = "Zeile1\nZeile2\nZeile3-NEU"   // B ändert unten
        assertEquals("Zeile1-NEU\nZeile2\nZeile3-NEU", ThreeWayMerge.text(base, a, b))
    }

    @Test fun overlappingEdits_sameLine_conflict() {
        val base = "Treffen um 18 Uhr"
        val a = "Treffen um 19 Uhr"
        val b = "Treffen um 20 Uhr"
        assertNull(ThreeWayMerge.text(base, a, b))
    }

    @Test fun identicalEdits_areClean() {
        val base = "x"; val a = "y"; val b = "y"
        assertEquals("y", ThreeWayMerge.text(base, a, b))
    }

    @Test fun oneSideUnchanged_takesOther() {
        val base = "a\nb\nc"
        assertEquals("a\nB2\nc", ThreeWayMerge.text(base, base, "a\nB2\nc"))
        assertEquals("a\nB2\nc", ThreeWayMerge.text(base, "a\nB2\nc", base))
    }

    @Test fun insertionsAtDifferentPlaces_merge() {
        val base = "1\n2\n3"
        val a = "0\n1\n2\n3"      // oben eingefügt
        val b = "1\n2\n3\n4"      // unten eingefügt
        assertEquals("0\n1\n2\n3\n4", ThreeWayMerge.text(base, a, b))
    }

    @Test fun orderIndependent_forConvergence() {
        // merge(o,a,b) muss == merge(o,b,a) sein, sonst divergieren Geräte.
        val o = "p\nq\nr\ns"
        val a = "p\nq-x\nr\ns"
        val b = "p\nq\nr\ns-y"
        assertEquals(ThreeWayMerge.text(o, a, b), ThreeWayMerge.text(o, b, a))
    }

    @Test fun list_mergesReferencesCleanly_andConflicts() {
        // Bild-Referenzen: A hängt hash an, B lässt unverändert -> Vereinigung.
        assertEquals(listOf("h1", "h2"), ThreeWayMerge.list(listOf("h1"), listOf("h1", "h2"), listOf("h1")))
        // Beide an derselben Stelle unterschiedlich -> Konflikt.
        assertNull(ThreeWayMerge.list(listOf("h1"), listOf("hA"), listOf("hB")))
    }
}
