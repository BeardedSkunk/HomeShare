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

    fun runInitiator(local: OpSource, channel: SecureChannel, blobs: BlobSync? = null): SyncResult {
        channel.writeText(OpCodec.encodeVv(local.versionVector()))
        val incoming = decodeOps(channel.readText())
        var pulled = 0
        for (op in incoming) if (local.ingestOp(op)) pulled++
        val remoteVv = OpCodec.decodeVv(channel.readText())
        val toRemote = local.missingFor(remoteVv)
        channel.writeText(encodeOps(toRemote))
        BlobExchange.asInitiator(channel, blobs)
        return SyncResult(pulled = pulled, pushed = toRemote.size)
    }

    fun runResponder(local: OpSource, channel: SecureChannel, blobs: BlobSync? = null): SyncResult {
        val remoteVv = OpCodec.decodeVv(channel.readText())
        val toRemote = local.missingFor(remoteVv)
        channel.writeText(encodeOps(toRemote))
        channel.writeText(OpCodec.encodeVv(local.versionVector()))
        val incoming = decodeOps(channel.readText())
        var pulled = 0
        for (op in incoming) if (local.ingestOp(op)) pulled++
        BlobExchange.asResponder(channel, blobs)
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

/**
 * Direkter Blob-Austausch (#11) im Anschluss an den Op-Abgleich – von beiden Protokollen
 * genutzt (Gruppe wie Cross-Group). Vier Nachrichten, spiegelbildlich, damit kein Deadlock:
 * Initiator nennt erst seinen Wunsch + empfaengt, dann liest er den Gegenwunsch + sendet.
 * [blobs] == null -> Phase wird komplett uebersprungen (Tests / Box-Pfad).
 */
internal object BlobExchange {
    /** Blobs groesser als das schicken wir nicht (Frame-Limit 16 MiB, plus GCM-Overhead). */
    private const val MAX_BLOB = 15 * 1024 * 1024

    fun asInitiator(channel: SecureChannel, blobs: BlobSync?) {
        if (blobs == null) return
        channel.writeText(blobs.wanted().joinToString("\n"))
        receive(channel, blobs)
        send(channel, blobs, readWanted(channel))
    }

    fun asResponder(channel: SecureChannel, blobs: BlobSync?) {
        if (blobs == null) return
        send(channel, blobs, readWanted(channel))
        channel.writeText(blobs.wanted().joinToString("\n"))
        receive(channel, blobs)
    }

    private fun readWanted(channel: SecureChannel): Set<String> =
        channel.readText().split('\n').filter { it.isNotBlank() }.toSet()

    /** Schickt die gewuenschten Blobs, die wir haben (1 Header-Frame mit Anzahl, dann je Blob 2 Frames). */
    private fun send(channel: SecureChannel, blobs: BlobSync, want: Set<String>) {
        val ready = want.asSequence()
            .filter { blobs.has(it) }
            .mapNotNull { sha -> blobs.read(sha)?.let { sha to it } }
            .filter { it.second.size <= MAX_BLOB }
            .toList()
        channel.writeText(ready.size.toString())
        for ((sha, bytes) in ready) {
            channel.writeText(sha)
            channel.writeBytes(bytes)
        }
    }

    private fun receive(channel: SecureChannel, blobs: BlobSync) {
        val n = channel.readText().toIntOrNull() ?: 0
        repeat(n) {
            val sha = channel.readText()
            val bytes = channel.readBytes()
            runCatching { blobs.store(sha, bytes) } // store prueft den Hash selbst
        }
    }
}
