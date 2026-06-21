package de.beardedskunk.homeshare.sync

import de.beardedskunk.homeshare.core.Hlc
import de.beardedskunk.homeshare.core.NodeContent
import de.beardedskunk.homeshare.core.NodeType
import de.beardedskunk.homeshare.core.NodeVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncTest {

    /** Minimalistische In-Memory-Quelle fuer den Reconciler-Test. */
    private class MemSource : OpSource {
        val ops = LinkedHashMap<String, OpDto>()
        override fun versionVector(): Map<String, PeerState> =
            ops.values.groupBy { it.deviceId }.mapValues { e ->
                val seqs = e.value.map { it.seq }
                val present = seqs.toHashSet()
                val max = seqs.max()
                PeerState(max, (1L..max).filter { it !in present })
            }

        override fun missingFor(remote: Map<String, PeerState>): List<OpDto> =
            ops.values.filter { op ->
                val st = remote[op.deviceId]
                st == null || op.seq > st.maxSeq || op.seq in st.gaps
            }

        override fun ingestOp(op: OpDto): Boolean {
            if (ops.containsKey(op.versionId)) return false
            ops[op.versionId] = op
            return true
        }

        override fun displayedBlobHashes(): Set<String> = emptySet()
    }

    private fun op(device: String, seq: Long, text: String, parents: Set<String> = emptySet()): OpDto {
        val v = NodeVersion("node-$device-$seq", parents, device, Hlc(seq, 0), NodeContent(text = text))
        return OpDto.from(v, rootId = "feed1", seq = seq)
    }

    @Test
    fun opCodec_roundTrips_allFields() {
        val dto = OpDto(
            versionId = "abc123",
            nodeId = "p1",
            parentId = "par0",
            rootId = "f1",
            deviceId = "devA",
            seq = 42,
            hlcWall = 1000,
            hlcCounter = 3,
            deleted = false,
            type = NodeType.IMAGE,
            orderKey = "0|hallo welt",
            color = 0xFF112233.toInt(),
            childDefault = NodeType.CALENDAR,
            done = true,
            blobHash = "sha256abc",
            fileName = "ünüsual, name.png",
            mime = "image/png",
            tags = listOf("a,b", "c"),
            text = "Zeile 1\nZeile 2 mit , Komma\nZeile 3",
            parents = listOf("par1", "par2"),
            deviceName = "Pixel 8",
        )
        assertEquals(dto, OpCodec.decodeOp(OpCodec.encodeOp(dto)))
    }

    @Test
    fun opCodec_roundTrips_withEmptyParentsAndTags() {
        val dto = op("devA", 1, "hallo")
        assertEquals(dto, OpCodec.decodeOp(OpCodec.encodeOp(dto)))
    }

    @Test
    fun listCodec_roundTrips_includingEmptyEntries() {
        // Regression: ein einzelner leerer Eintrag (z.B. leerer Tag) muss erhalten bleiben,
        // sonst aendert sich die versionId nach dem DB-Roundtrip (Phantom-Konflikt).
        val cases = listOf(
            emptyList<String>(), listOf(""), listOf("a"), listOf("", ""), listOf("x", "", "y"),
        )
        for (list in cases) {
            assertEquals(list, OpCodec.decodeList(OpCodec.encodeList(list)))
        }
    }

    @Test
    fun opCodec_roundTrips_imageNodeWithBlob_andStaysConsistent() {
        val v = NodeVersion(
            "p1", emptySet(), "devA", Hlc(1, 0),
            NodeContent(type = NodeType.IMAGE, blobHash = "sha1", text = ""),
        )
        val dto = OpDto.from(v, rootId = "feed1", seq = 1)
        val back = OpCodec.decodeOp(OpCodec.encodeOp(dto))
        assertEquals(dto, back)
        assertTrue(back.isConsistent())
    }

    @Test
    fun vvCodec_roundTrips() {
        val vv = mapOf("devA" to PeerState(5, listOf(2, 4)), "devB" to PeerState(2))
        assertEquals(vv, OpCodec.decodeVv(OpCodec.encodeVv(vv)))
        assertEquals(emptyMap<String, PeerState>(), OpCodec.decodeVv(OpCodec.encodeVv(emptyMap())))
    }

    @Test
    fun opDto_consistencyCheck_detectsTampering() {
        val good = op("devA", 1, "echt")
        assertTrue(good.isConsistent())
        val tampered = good.copy(text = "manipuliert")
        assertFalse("Geaenderter Inhalt passt nicht mehr zur versionId", tampered.isConsistent())
    }

    @Test
    fun reconcile_makesBothSidesConverge() {
        val a = MemSource().apply { ingestOp(op("A", 1, "a1")); ingestOp(op("A", 2, "a2")) }
        val b = MemSource().apply { ingestOp(op("B", 1, "b1")) }

        val result = SyncReconciler.reconcile(a, b)
        assertEquals(1, result.pulled) // A bekommt b1
        assertEquals(2, result.pushed) // B bekommt a1, a2
        assertEquals(a.ops.keys, b.ops.keys)
        assertEquals(3, a.ops.size)
    }

    @Test
    fun vvCarriesGaps_andEncodeDecodeRoundTrips() {
        // Quelle mit Loch: device X hat Seq 1 und 3, aber NICHT 2.
        val s = MemSource().apply { ingestOp(op("X", 1, "x1")); ingestOp(op("X", 3, "x3")) }
        val vv = s.versionVector()
        assertEquals(3L, vv["X"]!!.maxSeq)
        assertEquals(listOf(2L), vv["X"]!!.gaps)
        assertEquals(vv, OpCodec.decodeVv(OpCodec.encodeVv(vv)))
    }

    @Test
    fun reconcile_fillsSequenceGap_notJustNewerOps() {
        // Regression gegen den Konvergenz-Killer: A hat eine Luecke in der MITTE
        // (X:1, X:3 – Seq 2 fehlt), B hat alle drei. Mit reinem "hoechste-Seq"-Vektor
        // wuerde B nichts senden (A meldet max=3) und A bliebe fuer immer unvollstaendig.
        val a = MemSource().apply { ingestOp(op("X", 1, "x1")); ingestOp(op("X", 3, "x3")) }
        val b = MemSource().apply {
            ingestOp(op("X", 1, "x1")); ingestOp(op("X", 2, "x2")); ingestOp(op("X", 3, "x3"))
        }
        SyncReconciler.reconcile(a, b)
        assertEquals("A muss die Luecke (Seq 2) geschlossen bekommen", b.ops.keys, a.ops.keys)
        assertEquals(3, a.ops.size)
    }

    @Test
    fun reconcile_isIdempotent() {
        val a = MemSource().apply { ingestOp(op("A", 1, "a1")) }
        val b = MemSource().apply { ingestOp(op("B", 1, "b1")) }
        SyncReconciler.reconcile(a, b)
        val second = SyncReconciler.reconcile(a, b)
        assertEquals(0, second.pulled)
        assertEquals(0, second.pushed)
    }
}
