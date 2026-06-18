package de.beardedskunk.clipsharing.sync

import de.beardedskunk.clipsharing.core.Hlc
import de.beardedskunk.clipsharing.core.PostContent
import de.beardedskunk.clipsharing.core.PostVersion
import de.beardedskunk.clipsharing.crypto.GroupCrypto
import de.beardedskunk.clipsharing.data.FeedRight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** Gruppenuebergreifender Feed-Sync (#10): Feed-Begrenzung + Rechtedurchsetzung. */
class CrossGroupProtocolTest {

    private class MemFeedSource(seed: List<OpDto> = emptyList()) : FeedScopedSource {
        val ops = LinkedHashMap<String, OpDto>().apply { seed.forEach { put(it.versionId, it) } }
        private fun feedOps(feedId: String) = ops.values.filter { it.feedId == feedId }
        override fun feedVersionVector(feedId: String): Map<String, PeerState> =
            feedOps(feedId).groupBy { it.deviceId }.mapValues { e ->
                val s = e.value.map { it.seq }
                PeerState(s.max(), (1L..s.max()).filter { it !in s.toHashSet() })
            }
        override fun feedMissingFor(feedId: String, remote: Map<String, PeerState>): List<OpDto> =
            feedOps(feedId).filter { op ->
                val st = remote[op.deviceId]; st == null || op.seq > st.maxSeq || op.seq in st.gaps
            }
        override fun acceptIncomingOp(op: OpDto, feedId: String): Boolean {
            if (op.feedId != feedId || ops.containsKey(op.versionId)) return false
            ops[op.versionId] = op; return true
        }
        override fun acceptForeignOp(op: OpDto, feedId: String, right: FeedRight): Boolean {
            if (op.feedId != feedId || !right.canWrite()) return false
            if (op.parents.size > 1 && !right.canMerge()) return false
            if (ops.containsKey(op.versionId)) return false
            ops[op.versionId] = op; return true
        }
    }

    private fun op(device: String, seq: Long, feedId: String, text: String, parents: Set<String> = emptySet()) =
        OpDto.from(PostVersion("p-$feedId-$device-$seq", parents, device, Hlc(seq, 0), PostContent(text = text)), feedId, seq)

    private val key = GroupCrypto.keyFromToken(GroupCrypto.randomToken(32))

    /** Führt einen Cross-Group-Run für [feedId]/[right] über ein Loopback-Socket-Paar aus. */
    private fun run(foreign: MemFeedSource, original: MemFeedSource, feedId: String, right: FeedRight): CrossGroupProtocol.ForeignResult {
        val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        var fr: CrossGroupProtocol.ForeignResult? = null
        var err: Throwable? = null
        val orig = thread {
            try { server.use { it.accept() }.use { s -> s.soTimeout = 4000; CrossGroupProtocol.runOriginal(original, feedId, right, SecureChannel(s.getInputStream(), s.getOutputStream(), key)) } }
            catch (e: Throwable) { err = e }
        }
        Socket(InetAddress.getLoopbackAddress(), server.localPort).use { s ->
            s.soTimeout = 4000
            fr = CrossGroupProtocol.runForeign(foreign, feedId, SecureChannel(s.getInputStream(), s.getOutputStream(), key))
        }
        orig.join(6000)
        err?.let { throw AssertionError("Original warf: $it", it) }
        assertFalse(orig.isAlive)
        return fr!!
    }

    @Test fun foreignPullsOnlyTheSharedFeed_notOtherFeeds() {
        val original = MemFeedSource(listOf(
            op("O", 1, "feedA", "a1"), op("O", 2, "feedA", "a2"), op("O", 1, "feedB", "geheim-anderer-feed"),
        ))
        val foreign = MemFeedSource()
        val r = run(foreign, original, "feedA", FeedRight.READ)
        assertEquals(2, r.pulled)
        assertEquals(FeedRight.READ, r.right)
        // Nur feedA-Ops angekommen, feedB NICHT.
        assertEquals(setOf("feedA"), foreign.ops.values.map { it.feedId }.toSet())
        assertEquals(2, foreign.ops.size)
    }

    @Test fun readRight_rejectsForeignPush_writeRightAccepts() {
        val foreignOp = op("F", 1, "feedA", "fremd-eintrag")
        // READ: Original lehnt den Push ab.
        val origR = MemFeedSource(); run(MemFeedSource(listOf(foreignOp)), origR, "feedA", FeedRight.READ)
        assertFalse("read darf nicht pushen", origR.ops.containsKey(foreignOp.versionId))
        // WRITE: Original nimmt den Push an.
        val origW = MemFeedSource(); run(MemFeedSource(listOf(foreignOp)), origW, "feedA", FeedRight.WRITE)
        assertTrue("write darf pushen", origW.ops.containsKey(foreignOp.versionId))
    }

    @Test fun mergeOp_onlyAcceptedWithMergeRight() {
        val base = op("O", 1, "feedA", "basis")
        val mergeOp = op("F", 5, "feedA", "aufgeloest", parents = setOf(base.versionId, "anderer-head"))
        // WRITE (aber kein merge): Merge-Op (mehrere Eltern) wird abgelehnt.
        val origW = MemFeedSource(listOf(base)); run(MemFeedSource(listOf(base, mergeOp)), origW, "feedA", FeedRight.WRITE)
        assertFalse("write ohne merge darf keinen Konflikt loesen", origW.ops.containsKey(mergeOp.versionId))
        // MERGE: akzeptiert.
        val origM = MemFeedSource(listOf(base)); run(MemFeedSource(listOf(base, mergeOp)), origM, "feedA", FeedRight.MERGE)
        assertTrue("merge darf Konflikt loesen", origM.ops.containsKey(mergeOp.versionId))
    }
}
