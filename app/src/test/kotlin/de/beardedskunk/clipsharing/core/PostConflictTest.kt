package de.beardedskunk.clipsharing.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifiziert die Kern-Konflikt-Logik aus [Post] anhand der vom Nutzer
 * geforderten Szenarien:
 *  - linearer Verlauf (eine Bearbeitung) -> blind uebernehmbar,
 *  - nebenlaeufige Bearbeitung -> Konflikt mit korrekter Diff-Basis,
 *  - Loeschen-vs-Editieren -> Konflikt,
 *  - manuelle Aufloesung -> ein Head, und auf anderen Geraeten keine erneute Frage,
 *  - Ingest idempotent und reihenfolge-unabhaengig.
 */
class PostConflictTest {

    private val postId = "p1"

    private fun v(
        parents: Set<String>,
        device: String,
        wall: Long,
        text: String = "",
        deleted: Boolean = false,
    ) = PostVersion(postId, parents, device, Hlc(wall, 0), PostContent(text = text, deleted = deleted))

    @Test
    fun linearHistory_hasSingleHead_withLatestContent() {
        val post = Post(postId)
        val c = v(emptySet(), "A", 1, "hallo")
        val e1 = v(setOf(c.versionId), "A", 2, "hallo welt")
        val e2 = v(setOf(e1.versionId), "A", 3, "hallo welt!")
        listOf(c, e1, e2).forEach { post.ingest(it) }

        assertFalse(post.isConflicted())
        assertEquals(e2.versionId, post.current()?.versionId)
        assertEquals("hallo welt!", post.current()?.content?.text)
    }

    @Test
    fun concurrentEdits_produceConflict_withCommonAncestorAsDiffBase() {
        val post = Post(postId)
        val base = v(emptySet(), "A", 1, "Treffen um 18 Uhr")
        // Zwei Geraete bearbeiten unabhaengig denselben Basis-Stand.
        val onA = v(setOf(base.versionId), "A", 2, "Treffen um 19 Uhr")
        val onB = v(setOf(base.versionId), "B", 2, "Treffen um 20 Uhr")
        listOf(base, onA, onB).forEach { post.ingest(it) }

        assertTrue(post.isConflicted())
        assertEquals(2, post.heads().size)
        assertNull("Bei Konflikt gibt es keinen eindeutigen aktuellen Stand", post.current())

        val lca = post.lowestCommonAncestor(onA.versionId, onB.versionId)
        assertEquals("Diff-Basis muss der gemeinsame Vorfahr sein", base.versionId, lca?.versionId)
    }

    @Test
    fun deleteVsEdit_isConflict() {
        val post = Post(postId)
        val base = v(emptySet(), "A", 1, "wichtige Notiz")
        val deletedOnA = v(setOf(base.versionId), "A", 2, deleted = true)
        val editedOnB = v(setOf(base.versionId), "B", 2, "wichtige Notiz (aktualisiert)")
        listOf(base, deletedOnA, editedOnB).forEach { post.ingest(it) }

        assertTrue(post.isConflicted())
        val heads = post.heads()
        assertTrue(heads.any { it.content.deleted })
        assertTrue(heads.any { !it.content.deleted && it.content.text.contains("aktualisiert") })
    }

    @Test
    fun resolution_collapsesToSingleHead_andDoesNotReAskOnOtherReplica() {
        // Geraet A: Konflikt aufbauen und aufloesen.
        val a = Post(postId)
        val base = v(emptySet(), "A", 1, "Plan")
        val onA = v(setOf(base.versionId), "A", 2, "Plan A")
        val onB = v(setOf(base.versionId), "B", 2, "Plan B")
        listOf(base, onA, onB).forEach { a.ingest(it) }
        assertTrue(a.isConflicted())

        val merge = a.resolveConflict(PostContent(text = "Plan B"), deviceId = "A", hlc = Hlc(3, 0))
        assertFalse(a.isConflicted())
        assertEquals(merge.versionId, a.current()?.versionId)
        assertEquals(setOf(onA.versionId, onB.versionId), merge.parents)

        // Geraet C bekommt spaeter alle Versionen inkl. Merge -> kein erneuter Konflikt.
        val c = Post(postId)
        listOf(base, onB, onA, merge).forEach { c.ingest(it) } // andere Reihenfolge
        assertFalse("Merge-Version klaert den Konflikt fuer alle", c.isConflicted())
        assertEquals(merge.versionId, c.current()?.versionId)
        assertEquals("Plan B", c.current()?.content?.text)
    }

    @Test
    fun threeDevices_offlineEdits_resolveOnOne_clearsOnAllOthers() {
        // Genau das vom Nutzer beschriebene Szenario:
        //  1) D1 legt Post an, D2+D3 syncen ihn ein.
        //  2) D2 und D3 gehen offline und aendern den Post unterschiedlich.
        //  3) Alle kommen zurueck -> auf ALLEN drei Geraeten ein echter Konflikt.
        //  4) NUR EIN Geraet loest auf -> die Merge-Version klaert ihn fuer alle anderen,
        //     ohne dass dort erneut gefragt wird.
        val base = v(emptySet(), "D1", 1, "Einkaufsliste")
        val onD2 = v(setOf(base.versionId), "D2", 5, "Einkaufsliste: Milch")
        val onD3 = v(setOf(base.versionId), "D3", 5, "Einkaufsliste: Brot")

        // Jedes Geraet hat nach dem Wiederankoppeln alle drei Versionen.
        fun replicaWithAll(): Post = Post(postId).apply { listOf(base, onD2, onD3).forEach { ingest(it) } }
        val d1 = replicaWithAll(); val d2 = replicaWithAll(); val d3 = replicaWithAll()
        listOf(d1, d2, d3).forEach {
            assertTrue("alle drei sehen den Konflikt", it.hasContentConflict())
            assertEquals(2, it.heads().size)
        }

        // Nur D1 loest auf – Eltern sind ALLE aktuellen Heads (D2- und D3-Fassung).
        val merge = d1.resolveConflict(PostContent(text = "Einkaufsliste: Milch, Brot"), "D1", Hlc(9, 0))
        assertEquals(setOf(onD2.versionId, onD3.versionId), merge.parents)
        assertFalse(d1.hasContentConflict())

        // Die Merge-Version verteilt sich; D2 und D3 erhalten genau diese eine Op.
        assertTrue(d2.ingest(merge)); assertTrue(d3.ingest(merge))
        listOf(d2, d3).forEach {
            assertFalse("nach Eintreffen des Merges kein Konflikt mehr", it.hasContentConflict())
            assertEquals(merge.versionId, it.shownHead()?.versionId)
            assertEquals("Einkaufsliste: Milch, Brot", it.shownHead()?.content?.text)
        }
    }

    @Test
    fun identicalOfflineEdits_areNotAConflict() {
        // Bonus: D2 und D3 korrigieren offline unabhaengig denselben Tippfehler – gleicher
        // Inhalt, aber andere versionId (anderes Geraet/HLC). Effektiv kein Konflikt.
        val base = v(emptySet(), "D1", 1, "Hallo Welt")
        val onD2 = v(setOf(base.versionId), "D2", 5, "Hallo, Welt")
        val onD3 = v(setOf(base.versionId), "D3", 6, "Hallo, Welt")
        assertTrue("verschiedene versionId trotz gleichem Inhalt", onD2.versionId != onD3.versionId)

        val post = Post(postId).apply { listOf(base, onD2, onD3).forEach { ingest(it) } }
        assertEquals("technisch zwei Heads", 2, post.heads().size)
        assertFalse("aber inhaltsgleich -> kein zu entscheidender Konflikt", post.hasContentConflict())
        assertEquals("Hallo, Welt", post.shownHead()?.content?.text)

        // Eine spaetere echte Bearbeitung kollabiert die Heads wieder zu einem.
        val next = v(post.heads().map { it.versionId }.toSet(), "D2", 7, "Hallo, Welt!")
        post.ingest(next)
        assertEquals(1, post.heads().size)
        assertFalse(post.hasContentConflict())
    }

    @Test
    fun ingest_isIdempotent_andOrderIndependent() {
        val base = v(emptySet(), "A", 1, "x")
        val onA = v(setOf(base.versionId), "A", 2, "xa")
        val onB = v(setOf(base.versionId), "B", 2, "xb")

        val p1 = Post(postId)
        assertTrue(p1.ingest(base))
        assertFalse("zweiter Ingest derselben Version ist No-op", p1.ingest(base))
        listOf(onA, onB).forEach { p1.ingest(it) }

        val p2 = Post(postId)
        listOf(onB, onA, base, onA).forEach { p2.ingest(it) } // andere Reihenfolge + Duplikat

        assertEquals(
            p1.heads().map { it.versionId }.toSet(),
            p2.heads().map { it.versionId }.toSet(),
        )
    }

    @Test
    fun sameContentAndParents_yieldStableVersionIdAcrossDevicesIngest() {
        // Inhaltsadressierung: identischer Knoten -> identische versionId.
        val base1 = v(emptySet(), "A", 1, "gleich")
        val base2 = v(emptySet(), "A", 1, "gleich")
        assertEquals(base1.versionId, base2.versionId)
        assertNotNull(base1.versionId)
    }
}
