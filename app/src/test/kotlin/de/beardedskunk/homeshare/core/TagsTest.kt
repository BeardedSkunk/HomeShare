package de.beardedskunk.homeshare.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagsTest {

    // --- normalize ---

    @Test fun normalize_trim() {
        assertEquals("Urlaub", Tags.normalize("  Urlaub  "))
    }

    @Test fun normalize_leer_gibtNull() {
        assertNull(Tags.normalize(""))
        assertNull(Tags.normalize("   "))
    }

    // --- add ---

    @Test fun add_neuesTag_wirdHinzugefuegt() {
        val result = Tags.add(emptyList(), "Urlaub", emptyList())
        assertEquals(listOf("Urlaub"), result)
    }

    @Test fun add_caseInsensitivVorhanden_unveraendert() {
        val tags = listOf("Urlaub")
        assertEquals(tags, Tags.add(tags, "urlaub", emptyList()))
        assertEquals(tags, Tags.add(tags, "URLAUB", emptyList()))
    }

    @Test fun add_vokabularSchreibweiseGewinnt() {
        // vocab hat "Urlaub", Nutzer tippt "urlaub" -> "Urlaub" landet in der Liste
        val result = Tags.add(emptyList(), "urlaub", listOf("Urlaub", "Arbeit"))
        assertEquals(listOf("Urlaub"), result)
    }

    @Test fun add_leerEingabe_unveraendert() {
        val tags = listOf("A")
        assertEquals(tags, Tags.add(tags, "", emptyList()))
        assertEquals(tags, Tags.add(tags, "  ", emptyList()))
    }

    // --- remove ---

    @Test fun remove_caseInsensitiv() {
        val tags = listOf("Urlaub", "Arbeit")
        assertEquals(listOf("Arbeit"), Tags.remove(tags, "urlaub"))
        assertEquals(listOf("Urlaub"), Tags.remove(tags, "ARBEIT"))
    }

    @Test fun remove_nichtVorhanden_unveraendert() {
        val tags = listOf("Urlaub")
        assertEquals(tags, Tags.remove(tags, "unbekannt"))
    }

    // --- mergeSets ---

    @Test fun mergeSets_beideSeiten_fuegenVerschiedeneTagsHinzu() {
        // base: leer; a fügt "X" an, b fügt "Y" an → beide im Ergebnis
        val result = Tags.mergeSets(emptyList(), listOf("X"), listOf("Y"))
        assertTrue(result.containsAll(listOf("X", "Y")))
        assertEquals(2, result.size)
    }

    @Test fun mergeSets_eineSeiteEntfernt_andereUnveraendert() {
        // base: [A, B]; a entfernt B; b ist unverändert → B fehlt im Ergebnis
        val result = Tags.mergeSets(listOf("A", "B"), listOf("A"), listOf("A", "B"))
        assertEquals(listOf("A"), result)
    }

    @Test fun mergeSets_eineSeiteEntfernt_andereHinzugefuegt() {
        // base: [A]; a entfernt A; b fügt B hinzu → A entfernt, B bleibt
        val result = Tags.mergeSets(listOf("A"), emptyList(), listOf("A", "B"))
        assertEquals(listOf("B"), result)
    }

    @Test fun mergeSets_deterministisch_abUnabhaengigVonReihenfolge() {
        // a und b vertauscht → gleiche Menge (Reihenfolge innerhalb egal, aber Menge identisch)
        val r1 = Tags.mergeSets(emptyList(), listOf("X"), listOf("Y"))
        val r2 = Tags.mergeSets(emptyList(), listOf("Y"), listOf("X"))
        assertEquals(r1.toSet(), r2.toSet())
    }

    // --- End-to-End über Node.autoMergeContent ---

    private fun ver(
        nodeId: String,
        parents: Set<String>,
        device: String,
        wall: Long,
        tags: List<String> = emptyList(),
        text: String = "",
    ) = NodeVersion(nodeId, parents, device, Hlc(wall, 0), NodeContent(text = text, tags = tags))

    @Test fun autoMerge_divergenteTags_ergibtUnion() {
        val id = "n1"
        val base = ver(id, emptySet(), "A", 1, tags = listOf("Alt"))
        val onA = ver(id, setOf(base.versionId), "A", 2, tags = listOf("Alt", "Neu-A"))
        val onB = ver(id, setOf(base.versionId), "B", 2, tags = listOf("Alt", "Neu-B"))
        val post = Node(id).apply { listOf(base, onA, onB).forEach { ingest(it) } }
        assertTrue(post.hasContentConflict())
        val merged = post.autoMergeContent()
        assertNotNull("divergente Tag-Ergänzungen → Union, kein manueller Konflikt", merged)
        val resultTags = merged!!.tags
        assertTrue(resultTags.containsAll(listOf("Alt", "Neu-A", "Neu-B")))
    }
}
