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
        val incoming = decodeOps(channel.readText())
        var pulled = 0
        for (op in incoming) if (src.acceptIncomingOp(op, feedId)) pulled++
        val remoteVv = OpCodec.decodeVv(channel.readText())
        val right = FeedRight.from(channel.readText())
        val toRemote = src.feedMissingFor(feedId, remoteVv)
        channel.writeText(encodeOps(toRemote))
        // Bilder direkt holen/geben – die Fremdgruppe erreicht die FRITZ!Box des Originals nicht.
        BlobExchange.asInitiator(channel, blobs)
        return ForeignResult(pulled = pulled, pushed = toRemote.size, right = right)
    }

    fun runOriginal(src: FeedScopedSource, feedId: String, right: FeedRight, channel: SecureChannel, blobs: BlobSync? = null): SyncResult {
        val remoteVv = OpCodec.decodeVv(channel.readText())
        val toRemote = src.feedMissingFor(feedId, remoteVv)
        channel.writeText(encodeOps(toRemote))
        channel.writeText(OpCodec.encodeVv(src.feedVersionVector(feedId)))
        channel.writeText(right.name)
        val incoming = decodeOps(channel.readText())
        var pulled = 0
        for (op in incoming) if (src.acceptForeignOp(op, feedId, right)) pulled++
        BlobExchange.asResponder(channel, blobs)
        return SyncResult(pulled = pulled, pushed = toRemote.size)
    }

    private fun encodeOps(ops: List<OpDto>): String = buildString {
        append(ops.size).append('\n')
        for (op in ops) append(OpCodec.encodeOpLine(op)).append('\n')
    }

    private fun decodeOps(text: String): List<OpDto> {
        if (text.isBlank()) return emptyList()
        val lines = text.split('\n')
        val count = lines[0].toIntOrNull() ?: return emptyList()
        val out = ArrayList<OpDto>(count)
        var i = 1
        while (i <= count && i < lines.size) {
            val line = lines[i]
            if (line.isNotBlank()) out += OpCodec.decodeOpLine(line)
            i++
        }
        return out
    }
}
