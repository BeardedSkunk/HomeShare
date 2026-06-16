package de.beardedskunk.clipsharing.sync

/**
 * Sync-Protokoll ueber einen verschluesselten [SecureChannel] (vier Nachrichten):
 *  1. Initiator -> sein Versions-Vektor
 *  2. Antwortender -> dem Initiator fehlende Ops
 *  3. Antwortender -> sein Versions-Vektor
 *  4. Initiator -> dem Antwortenden fehlende Ops
 *
 * Da der Kanal verschluesselt und per Gruppen-Passphrase authentifiziert ist,
 * koppeln sich nur Geraete derselben Gruppe erfolgreich.
 */
object PeerProtocol {

    fun runInitiator(local: OpSource, channel: SecureChannel): SyncResult {
        channel.writeText(OpCodec.encodeVv(local.versionVector()))
        val incoming = decodeOps(channel.readText())
        var pulled = 0
        for (op in incoming) if (local.ingestOp(op)) pulled++
        val remoteVv = OpCodec.decodeVv(channel.readText())
        val toRemote = local.missingFor(remoteVv)
        channel.writeText(encodeOps(toRemote))
        return SyncResult(pulled = pulled, pushed = toRemote.size)
    }

    fun runResponder(local: OpSource, channel: SecureChannel): SyncResult {
        val remoteVv = OpCodec.decodeVv(channel.readText())
        val toRemote = local.missingFor(remoteVv)
        channel.writeText(encodeOps(toRemote))
        channel.writeText(OpCodec.encodeVv(local.versionVector()))
        val incoming = decodeOps(channel.readText())
        var pulled = 0
        for (op in incoming) if (local.ingestOp(op)) pulled++
        return SyncResult(pulled = pulled, pushed = toRemote.size)
    }

    /** Ops-Block: erste Zeile Anzahl, dann je Op eine base64-Zeile. */
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
