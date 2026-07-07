package de.beardedskunk.homeshare.data

import de.beardedskunk.homeshare.core.NodeContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Kernlogik des Op-Log-Undo (docs/undo_redo.md): Aufzeichnung pro Anker, Gruppen,
 * Redo-Schwanz-Kappung, Limit, Invalidierung durch fremde Ops, Stale-Skip und das
 * restore-Marker-Verhalten. Der Fake-Executor verhält sich wie das Repository:
 * jede authorRestore-Op ruft onLocalOp zurück (suppress muss sie ignorieren).
 */
class UndoManagerTest {

    private lateinit var undo: UndoManager
    private lateinit var exec: FakeExecutor

    /** In-Memory-Knotenwelt: Version-Historie + Head pro Knoten, authort wie das Repo. */
    private inner class FakeExecutor : UndoExecutor {
        val versions = HashMap<String, MutableMap<String, NodeContent>>() // nodeId -> versionId -> Inhalt
        val heads = HashMap<String, String>()
        val authored = ArrayList<Pair<String, NodeContent>>()             // Reihenfolge aller Restore-Ops
        private var seq = 0

        fun newId() = "v${++seq}"

        /** Normale lokale Op (wie FeedRepository.author): persistiert und meldet an den Manager. */
        fun author(nodeId: String, content: NodeContent): String {
            val prev = heads[nodeId]
            val id = newId()
            versions.getOrPut(nodeId) { HashMap() }[id] = content
            heads[nodeId] = id
            undo.onLocalOp(nodeId, if (prev != null) setOf(prev) else emptySet(), id)
            return id
        }

        override fun soleHeadId(nodeId: String): String? = heads[nodeId]

        override fun versionContent(nodeId: String, versionId: String): NodeContent? =
            versions[nodeId]?.get(versionId)

        override fun authorRestore(nodeId: String, content: NodeContent): String {
            val prev = heads[nodeId]
            val id = newId()
            versions.getOrPut(nodeId) { HashMap() }[id] = content
            heads[nodeId] = id
            authored += nodeId to content
            undo.onLocalOp(nodeId, if (prev != null) setOf(prev) else emptySet(), id)
            return id
        }
    }

    @Before
    fun setUp() {
        undo = UndoManager()
        exec = FakeExecutor()
        undo.executor = exec
    }

    private fun content(text: String, deleted: Boolean = false, ext: Map<String, String> = emptyMap()) =
        NodeContent(text = text, deleted = deleted, ext = ext)

    // ---- Aufzeichnung ----

    @Test
    fun record_landsInActiveAnchorChain() {
        undo.pushAnchor("screen")
        exec.author("n1", content("a"))
        assertTrue(undo.canUndo("screen"))
        assertFalse(undo.canUndo("anderswo"))
        assertFalse(undo.canRedo("screen"))
    }

    @Test
    fun record_withoutAnchorInvalidatesInsteadOfRecording() {
        undo.pushAnchor("screen")
        exec.author("n1", content("a"))
        // Hintergrund-Schreiber (kein Anker): zeichnet nicht auf UND wirft bestehende Einträge zu n1 raus.
        undo.popAnchor("screen")
        exec.author("n1", content("b"))
        undo.pushAnchor("screen")
        assertFalse(undo.canUndo("screen"))
    }

    @Test
    fun record_mergeOpInvalidates() {
        undo.pushAnchor("screen")
        val v1 = exec.author("n1", content("a"))
        // Merge (mehrere Eltern) -> invalidate statt aufzeichnen.
        undo.onLocalOp("n1", setOf(v1, "fremd"), "merge")
        assertFalse(undo.canUndo("screen"))
    }

    @Test
    fun record_onTopmostAnchor() {
        undo.pushAnchor("liste")
        undo.pushAnchor("editor")
        exec.author("n1", content("a"))
        assertTrue(undo.canUndo("editor"))
        assertFalse(undo.canUndo("liste"))
        // pop entfernt das LETZTE Vorkommen; danach zeichnet wieder die Liste auf.
        undo.popAnchor("editor")
        exec.author("n2", content("b"))
        assertTrue(undo.canUndo("liste"))
    }

    @Test
    fun record_newActionCutsRedoTail() {
        undo.pushAnchor("s")
        exec.author("n1", content("a"))
        exec.author("n1", content("b"))
        undo.undo("s")
        assertTrue(undo.canRedo("s"))
        exec.author("n1", content("c")) // neue Aktion -> Redo weg
        assertFalse(undo.canRedo("s"))
        assertTrue(undo.canUndo("s"))
    }

    @Test
    fun record_limitDropsOldestEntries() {
        undo.pushAnchor("s")
        exec.author("n0", content("start"))
        repeat(150) { exec.author("n0", content("t$it")) }
        // Kette ist auf 100 gekappt: genau 100 Undos möglich, dann Schluss.
        var undos = 0
        while (undo.canUndo("s")) { undo.undo("s"); undos++ }
        assertEquals(100, undos)
    }

    // ---- Undo/Redo-Roundtrip ----

    @Test
    fun undoRedo_roundtripRestoresContentWithMarker() {
        undo.pushAnchor("s")
        val v1 = exec.author("n1", content("alt"))
        val v2 = exec.author("n1", content("neu"))

        undo.undo("s")
        val afterUndo = exec.versions["n1"]!![exec.heads["n1"]]!!
        assertEquals("alt", afterUndo.text)
        assertEquals(v1, afterUndo.ext[UndoMeta.RESTORE])

        undo.redo("s")
        val afterRedo = exec.versions["n1"]!![exec.heads["n1"]]!!
        assertEquals("neu", afterRedo.text)
        assertEquals(v2, afterRedo.ext[UndoMeta.RESTORE])

        // Und wieder zurück: das Bookkeeping (doneHead/undoneHead) muss nachgezogen sein.
        undo.undo("s")
        assertEquals("alt", exec.versions["n1"]!![exec.heads["n1"]]!!.text)
    }

    @Test
    fun undoRedo_iteratesThroughSequentialSavesOfSameNode() {
        // DER Kernfall des Auto-Save-Modells: mehrere Saves derselben Notiz, feingranular
        // durchiterierbar (Restore-Op wird Head -> Retargeting hält Nachbar-Einträge gültig).
        undo.pushAnchor("s")
        exec.author("n1", content("v1"))
        exec.author("n1", content("v2"))
        exec.author("n1", content("v3"))
        exec.author("n1", content("v4"))

        val seen = ArrayList<String>()
        while (undo.canUndo("s")) { undo.undo("s"); seen += exec.versions["n1"]!![exec.heads["n1"]]!!.text }
        assertEquals(listOf("v3", "v2", "v1"), seen.take(3))
        assertTrue(exec.versions["n1"]!![exec.heads["n1"]]!!.deleted) // letztes Undo = Anlegen zurück

        seen.clear()
        while (undo.canRedo("s")) { undo.redo("s"); seen += exec.versions["n1"]!![exec.heads["n1"]]!!.text }
        assertEquals(listOf("v1", "v2", "v3", "v4"), seen)
        assertFalse(exec.versions["n1"]!![exec.heads["n1"]]!!.deleted)
    }

    @Test
    fun undo_ofCreateAuthorsDeleteWithoutMarker() {
        undo.pushAnchor("s")
        exec.author("n1", content("angelegt")) // parents leer = create

        undo.undo("s")
        val afterUndo = exec.versions["n1"]!![exec.heads["n1"]]!!
        assertTrue(afterUndo.deleted)
        assertNull(afterUndo.ext[UndoMeta.RESTORE]) // nichts zu restaurieren

        undo.redo("s")
        val afterRedo = exec.versions["n1"]!![exec.heads["n1"]]!!
        assertFalse(afterRedo.deleted)
        assertEquals("angelegt", afterRedo.text)
    }

    @Test
    fun undo_ofDeleteRestoresUndeleted() {
        undo.pushAnchor("s")
        exec.author("n1", content("lebt"))
        exec.author("n1", content("lebt", deleted = true)) // deleteNode

        undo.undo("s")
        val restored = exec.versions["n1"]!![exec.heads["n1"]]!!
        assertFalse(restored.deleted)
        assertEquals("lebt", restored.text)
    }

    // ---- Gruppen ----

    @Test
    fun group_isOneEntryUndoneInReverseOrder() {
        undo.pushAnchor("s")
        exec.author("orig", content("wert"))
        undo.group {
            exec.author("orig", content("wert", ext = mapOf("marker" to "x"))) // Spawn-Sperre
            exec.author("kopie", content("klon"))                              // Kopie-Knoten
        }
        // Ein Undo nimmt BEIDE Ops zurück: Kopie gelöscht, Original wieder ohne Marker.
        undo.undo("s")
        assertTrue(exec.versions["kopie"]!![exec.heads["kopie"]]!!.deleted)
        assertNull(exec.versions["orig"]!![exec.heads["orig"]]!!.ext["marker"])
        // Reihenfolge: rückwärts (erst Kopie, dann Original) …
        assertEquals(listOf("kopie", "orig"), exec.authored.map { it.first })
        exec.authored.clear()
        // … Redo vorwärts (erst Original, dann Kopie).
        undo.redo("s")
        assertEquals(listOf("orig", "kopie"), exec.authored.map { it.first })
        assertFalse(exec.versions["kopie"]!![exec.heads["kopie"]]!!.deleted)
        assertEquals("x", exec.versions["orig"]!![exec.heads["orig"]]!!.ext["marker"])
    }

    @Test
    fun group_withoutAnchorRecordsNothing() {
        undo.group { exec.author("n1", content("sweep")) } // Fälligkeits-Sweep: kein Anker
        undo.pushAnchor("s")
        assertFalse(undo.canUndo("s"))
    }

    // ---- Invalidierung / Stale ----

    @Test
    fun invalidate_removesEntriesInAllChains() {
        undo.pushAnchor("a")
        exec.author("n1", content("x"))
        exec.author("n2", content("y"))
        undo.popAnchor("a")
        undo.pushAnchor("b")
        exec.author("n1", content("z"))

        undo.invalidate("n1") // fremde Op für n1 eingetroffen
        assertFalse(undo.canUndo("b"))
        assertTrue(undo.canUndo("a")) // n2-Eintrag bleibt
        undo.undo("a")
        assertEquals("n2", exec.authored.single().first)
    }

    @Test
    fun undo_skipsStaleEntryAndExecutesNextOlder() {
        undo.pushAnchor("s")
        exec.author("n1", content("a"))
        exec.author("n1", content("b"))
        // Head wandert an der Kette vorbei (z. B. Undo-Op eines anderen Geräts nach Sync-Lücke).
        exec.heads["n1"] = "fremder-head"
        exec.versions["n1"]!!["fremder-head"] = content("fremd")
        undo.undo("s") // beide Einträge sind stale -> entsorgt, nichts authort
        assertTrue(exec.authored.isEmpty())
        assertFalse(undo.canUndo("s"))
    }

    @Test
    fun undo_staleOnlyTopEntrySkipsToOlderNode() {
        undo.pushAnchor("s")
        exec.author("n1", content("alt"))
        exec.author("n1", content("neu"))
        exec.author("n2", content("x"))
        exec.author("n2", content("y"))
        // Nur n2 ist stale.
        exec.heads["n2"] = "fremd"
        exec.versions["n2"]!!["fremd"] = content("fremd")
        undo.undo("s") // n2-Eintrag fliegt, n1 wird zurückgedreht
        assertEquals("n1", exec.authored.single().first)
        assertEquals("alt", exec.versions["n1"]!![exec.heads["n1"]]!!.text)
    }

    @Test
    fun redo_staleEntryIsDropped() {
        undo.pushAnchor("s")
        exec.author("n1", content("a"))
        exec.author("n1", content("b"))
        undo.undo("s")
        // Nach dem Undo editiert jemand anders n1 -> Redo wäre falsch.
        exec.heads["n1"] = "fremd"
        exec.versions["n1"]!!["fremd"] = content("fremd")
        exec.authored.clear()
        undo.redo("s")
        assertTrue(exec.authored.isEmpty())
        assertFalse(undo.canRedo("s"))
    }

    // ---- restore-Marker-Hygiene ----

    @Test
    fun undo_stripsStaleMarkerFromRestoredContent() {
        undo.pushAnchor("s")
        // Version trägt (z. B. aus einem alten Undo) bereits einen Marker — beim Restaurieren
        // muss er durch den NEUEN Ziel-Marker ersetzt werden, nicht doppelt anfallen.
        val v1 = exec.author("n1", content("alt", ext = mapOf(UndoMeta.RESTORE to "uralt")))
        exec.author("n1", content("neu"))
        undo.undo("s")
        assertEquals(v1, exec.versions["n1"]!![exec.heads["n1"]]!!.ext[UndoMeta.RESTORE])
    }
}
