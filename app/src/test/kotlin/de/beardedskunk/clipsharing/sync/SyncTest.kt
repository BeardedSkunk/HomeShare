package de.beardedskunk.clipsharing.sync

import de.beardedskunk.clipsharing.core.Hlc
import de.beardedskunk.clipsharing.core.PostContent
import de.beardedskunk.clipsharing.core.PostVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncTest {

    /** Minimalistische In-Memory-Quelle fuer den Reconciler-Test. */
    private class MemSource : OpSource {
        val ops = LinkedHashMap<String, OpDto>()
        override fun versionVector(): Map<String, Long> =
            ops.values.groupBy { it.deviceId }.mapValues { e -> e.value.maxOf { it.seq } }

        override fun missingFor(remote: Map<String, Long>): List<OpDto> =
            ops.values.filter { it.seq > (remote[it.deviceId] ?: 0L) }

        override fun ingestOp(op: OpDto): Boolean {
            if (ops.containsKey(op.versionId)) return false
            ops[op.versionId] = op
            return true
        }

        override fun displayedImageHashes(): Set<String> = emptySet()
    }

    private fun op(device: String, seq: Long, text: String, parents: Set<String> = emptySet()): OpDto {
        val v = PostVersion("post-$device-$seq", parents, device, Hlc(seq, 0), PostContent(text = text))
        return OpDto.from(v, feedId = "feed1", seq = seq)
    }

    @Test
    fun opCodec_roundTrips_includingNewlinesAndImages() {
        val dto = OpDto(
            versionId = "abc123",
            feedId = "f1",
            postId = "p1",
            deviceId = "devA",
            seq = 42,
            hlcWall = 1000,
            hlcCounter = 3,
            deleted = false,
            text = "Zeile 1\nZeile 2 mit , Komma\nZeile 3",
            parents = listOf("par1", "par2"),
            imageHashes = listOf("sha1", "sha2"),
        )
        assertEquals(dto, OpCodec.decodeOp(OpCodec.encodeOp(dto)))
    }

    @Test
    fun opCodec_roundTrips_withEmptyParentsAndImages() {
        val dto = op("devA", 1, "hallo")
        assertEquals(dto, OpCodec.decodeOp(OpCodec.encodeOp(dto)))
    }

    @Test
    fun vvCodec_roundTrips() {
        val vv = mapOf("devA" to 5L, "devB" to 2L)
        assertEquals(vv, OpCodec.decodeVv(OpCodec.encodeVv(vv)))
        assertEquals(emptyMap<String, Long>(), OpCodec.decodeVv(OpCodec.encodeVv(emptyMap())))
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
    fun reconcile_isIdempotent() {
        val a = MemSource().apply { ingestOp(op("A", 1, "a1")) }
        val b = MemSource().apply { ingestOp(op("B", 1, "b1")) }
        SyncReconciler.reconcile(a, b)
        val second = SyncReconciler.reconcile(a, b)
        assertEquals(0, second.pulled)
        assertEquals(0, second.pushed)
    }
}
