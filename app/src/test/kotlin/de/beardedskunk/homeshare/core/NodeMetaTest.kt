package de.beardedskunk.homeshare.core

import de.beardedskunk.homeshare.sync.Hello
import de.beardedskunk.homeshare.sync.OpCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sichert das erweiterbare Meta-System ab: bekannte Felder reisen als sortierte Klartext-Map,
 * unbekannte/zukünftige Keys werden wortwörtlich erhalten, und das Hinzufügen eines neuen Keys
 * ändert NUR die versionId der Knoten, die ihn setzen (Kern-Garantie gegen Zwangs-Wipes).
 */
class NodeMetaTest {

    private fun ver(content: NodeContent) = NodeVersion("n1", emptySet(), "A", Hlc(1, 0), content)

    @Test fun metaMap_roundTripsThroughFromMeta_includingUnknownKeys() {
        val c = NodeContent(
            type = NodeType.IMAGE, text = "Logo", childDefault = null,
            blobHash = "sha", mime = "image/png", tags = listOf("a", "b"), color = -42,
            ext = mapOf("zukunft" to "wert mit , und \n Umbruch", "pinned" to "1"),
        )
        val back = NodeContent.fromMeta(c.parentId, c.type, c.orderKey, c.text, c.deleted, c.metaMap())
        assertEquals(c.metaMap(), back.metaMap())
        assertEquals("sha", back.blobHash)
        assertEquals(listOf("a", "b"), back.tags)
        assertEquals("wert mit , und \n Umbruch", back.ext["zukunft"])
    }

    @Test fun unknownKey_onlyChangesVersionIdOfNodesThatSetIt() {
        val plain = ver(NodeContent(type = NodeType.TEXT, text = "Hallo"))
        val plainAgain = ver(NodeContent(type = NodeType.TEXT, text = "Hallo"))
        // Derselbe Knoten ohne den neuen Key -> stabile versionId (egal dass die App den Key KENNT).
        assertEquals(plain.versionId, plainAgain.versionId)
        // Ein Knoten, der den neuen Key SETZT, bekommt eine andere versionId.
        val withKey = ver(NodeContent(type = NodeType.TEXT, text = "Hallo", ext = mapOf("pinned" to "1")))
        assertNotEquals(plain.versionId, withKey.versionId)
    }

    @Test fun listVsNote_isChildDefaultPresence() {
        val list = NodeContent(type = NodeType.TEXT, text = "L", childDefault = NodeKind.NOTE)
        val note = NodeContent(type = NodeType.TEXT, text = "N")
        // Liste = childDefault gesetzt (steht als Meta-Key); Notiz = kein childDefault.
        assertEquals(NodeKind.NOTE.name, list.metaMap()[MetaKey.CHILD_DEFAULT])
        assertTrue(list.childDefault != null)
        assertNull(note.childDefault)
        assertNull(note.metaMap()[MetaKey.CHILD_DEFAULT])
    }

    @Test fun formatVersion_isPartOfVersionId() {
        val c = NodeContent(type = NodeType.TEXT, text = "x")
        val v1 = NodeVersion("n", emptySet(), "A", Hlc(1, 0), c, formatVersion = 1)
        val v2 = NodeVersion("n", emptySet(), "A", Hlc(1, 0), c, formatVersion = 2)
        assertNotEquals(v1.versionId, v2.versionId)
    }

    @Test fun metaCodec_roundTrips_withSpecialChars() {
        val m = sortedMapOf("a" to "", "b" to "x=y\nz", "lang" to "ä".repeat(50))
        assertEquals(m, MetaCodec.decode(MetaCodec.encode(m)))
        assertEquals(emptyMap<String, String>(), MetaCodec.decode(MetaCodec.encode(emptyMap())))
    }

    @Test fun helloCodec_roundTrips() {
        val h = Hello(formatVersion = 3, appVersion = "1.23", deviceName = "Pixel 8 Pro")
        assertEquals(h, OpCodec.decodeHello(OpCodec.encodeHello(h)))
        assertNull(OpCodec.decodeHello("kein hello"))
    }
}
