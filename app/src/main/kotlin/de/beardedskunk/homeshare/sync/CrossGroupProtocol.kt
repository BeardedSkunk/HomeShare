package de.beardedskunk.homeshare.sync

import de.beardedskunk.homeshare.data.FeedRight

/**
 * Sync-Protokoll für EINEN geteilten Feed über Gruppengrenzen (#10), über einen mit dem
 * **capSecret** verschlüsselten [SecureChannel]. Fünf Nachrichten:
 *  1. Fremdgruppe -> ihr Feed-Versions-Vektor
 *  2. Original     -> der Fremdgruppe fehlende Ops dieses Feeds (read ist immer erlaubt)
 *  3. Original     -> sein Feed-Versions-Vektor
 *  4. Original     -> die aktuelle Rechtestufe (für UI-Gating der Fremdgruppe)
 *  5. Fremdgruppe -> dem Original fehlende Ops (werden nur gemäß Recht übernommen)
 *
 * Die Fremdgruppe ist immer Initiator (sie holt/pusht ihren Fremdfeed). Das Original setzt
 * die Rechte autoritativ durch ([FeedScopedSource.acceptForeignOp]).
 */
object CrossGroupProtocol {

    data class ForeignResult(val pulled: Int, val pushed: Int, val right: FeedRight)

    fun runForeign(src: FeedScopedSource, feedId: String, channel: SecureChannel, blobs: BlobSync? = null): ForeignResult {
        channel.writeText(OpCodec.encodeVv(src.feedVersionVector(feedId)))
        val incoming = OpCodec.decodeOps(channel.readText())
        var pulled = 0
        for (op in incoming) if (src.acceptIncomingOp(op, feedId)) pulled++
        val remoteVv = OpCodec.decodeVv(channel.readText())
        val right = FeedRight.from(channel.readText())
        val toRemote = src.feedMissingFor(feedId, remoteVv)
        channel.writeText(OpCodec.encodeOps(toRemote))
        // Bilder direkt holen/geben – die Fremdgruppe erreicht die FRITZ!Box des Originals nicht.
        BlobExchange.asInitiator(channel, blobs)
        return ForeignResult(pulled = pulled, pushed = toRemote.size, right = right)
    }

    fun runOriginal(src: FeedScopedSource, feedId: String, right: FeedRight, channel: SecureChannel, blobs: BlobSync? = null): SyncResult {
        val remoteVv = OpCodec.decodeVv(channel.readText())
        val toRemote = src.feedMissingFor(feedId, remoteVv)
        channel.writeText(OpCodec.encodeOps(toRemote))
        channel.writeText(OpCodec.encodeVv(src.feedVersionVector(feedId)))
        channel.writeText(right.name)
        val incoming = OpCodec.decodeOps(channel.readText())
        var pulled = 0
        for (op in incoming) if (src.acceptForeignOp(op, feedId, right)) pulled++
        BlobExchange.asResponder(channel, blobs)
        return SyncResult(pulled = pulled, pushed = toRemote.size)
    }
}
