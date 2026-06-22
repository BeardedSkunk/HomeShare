package de.beardedskunk.homeshare.sync

import de.beardedskunk.homeshare.core.Hlc
import de.beardedskunk.homeshare.core.MetaCodec
import de.beardedskunk.homeshare.core.NodeContent
import de.beardedskunk.homeshare.core.NodeType
import de.beardedskunk.homeshare.core.NodeVersion
import java.util.Base64

/**
 * Eine Operation (Knoten-Versionsknoten) im übertragbaren Format. Optionale Felder reisen als
 * erweiterbare [meta]-Map (sortierte Klartext-Keys); [formatVersion] ist der Kompatibilitäts-Hebel.
 * [rootId], [seq] und [deviceName] fließen NICHT in die versionId.
 */
data class OpDto(
    val versionId: String,
    val nodeId: String,
    val parentId: String,
    val rootId: String,
    val deviceId: String,
    val seq: Long,
    val hlcWall: Long,
    val hlcCounter: Int,
    val deleted: Boolean,
    val type: NodeType,
    val orderKey: String,
    val text: String,
    val meta: Map<String, String>,
    val formatVersion: Int,
    val parents: List<String>,
    val deviceName: String = "",
) {
    fun toVersion(): NodeVersion = NodeVersion(
        nodeId = nodeId,
        parents = parents.toSet(),
        deviceId = deviceId,
        hlc = Hlc(hlcWall, hlcCounter),
        content = NodeContent.fromMeta(parentId, type, orderKey, text, deleted, meta),
        formatVersion = formatVersion,
    )

    /** Integritätsprüfung: stimmt die mitgelieferte Id mit dem Inhalt überein? */
    fun isConsistent(): Boolean = toVersion().versionId == versionId

    companion object {
        fun from(v: NodeVersion, rootId: String, seq: Long, deviceName: String = ""): OpDto = OpDto(
            versionId = v.versionId,
            nodeId = v.nodeId,
            parentId = v.content.parentId,
            rootId = rootId,
            deviceId = v.deviceId,
            seq = seq,
            hlcWall = v.hlc.wallMillis,
            hlcCounter = v.hlc.counter,
            deleted = v.content.deleted,
            type = v.content.type,
            orderKey = v.content.orderKey,
            text = v.content.text,
            meta = v.content.metaMap(),
            formatVersion = v.formatVersion,
            parents = v.parents.toList(),
            deviceName = deviceName,
        )
    }
}

/**
 * Versions-Begrüßung beim Sync-Start — **eingefrorenes** Format (eine Zeile, Space-getrennt, Freitext
 * base64). Trägt die Kompatibilitäts-Version [formatVersion], die menschenlesbare [appVersion] und den
 * [deviceName]. Damit erkennt selbst eine veraltete App eine neuere Gegenstelle.
 */
data class Hello(val formatVersion: Int, val appVersion: String, val deviceName: String)

/**
 * Einfaches, eigenkontrolliertes Wire-Format (zeilenbasiert, Freitext base64-kodiert). Bewusst ohne
 * externe Serialisierungsbibliothek, voll testbar.
 */
object OpCodec {
    // Eingefrorener Transport-Marker. Evolution läuft über `fmt` (im Op) + die offene meta-Map, NICHT
    // über den Header. Body-Layout ab hier ebenfalls eingefroren.
    private const val HEADER = "HSNODE1"
    private const val HELLO = "HSHELLO"

    fun encodeOp(d: OpDto): String = buildString {
        append(HEADER).append('\n')
        append(d.versionId).append('\n')
        append(d.formatVersion).append('\n')
        append(d.nodeId).append('\n')
        append(d.parentId).append('\n')
        append(d.rootId).append('\n')
        append(d.deviceId).append('\n')
        append(d.seq).append('\n')
        append(d.hlcWall).append('\n')
        append(d.hlcCounter).append('\n')
        append(if (d.deleted) "1" else "0").append('\n')
        append(d.type.name).append('\n')
        append(b64(d.orderKey)).append('\n')
        append(d.parents.joinToString(",")).append('\n')
        append(b64(MetaCodec.encode(d.meta))).append('\n') // meta kann '\n' enthalten -> base64
        append(b64(d.text)).append('\n')
        append(b64(d.deviceName))
    }

    fun decodeOp(s: String): OpDto {
        val p = s.split('\n')
        require(p[0] == HEADER) { "Unbekanntes Format: ${p[0]}" }
        return OpDto(
            versionId = p[1],
            formatVersion = p.getOrElse(2) { "1" }.toIntOrNull() ?: 1,
            nodeId = p[3],
            parentId = p[4],
            rootId = p[5],
            deviceId = p[6],
            seq = p[7].toLong(),
            hlcWall = p[8].toLong(),
            hlcCounter = p[9].toInt(),
            deleted = p[10] == "1",
            type = runCatching { NodeType.valueOf(p[11]) }.getOrDefault(NodeType.TEXT),
            orderKey = unb64(p.getOrElse(12) { "" }),
            parents = splitCsv(p.getOrElse(13) { "" }),
            meta = MetaCodec.decode(unb64(p.getOrElse(14) { "" })),
            text = unb64(p.getOrElse(15) { "" }),
            deviceName = p.getOrNull(16)?.takeIf { it.isNotBlank() }?.let { unb64(it) } ?: "",
        )
    }

    /** Eingefrorene Versions-Begrüßung (eine Zeile). */
    fun encodeHello(h: Hello): String = "$HELLO ${h.formatVersion} ${b64(h.appVersion)} ${b64(h.deviceName)}"

    fun decodeHello(s: String): Hello? {
        val p = s.trim().split(' ')
        if (p.size < 4 || p[0] != HELLO) return null
        val fmt = p[1].toIntOrNull() ?: return null
        return Hello(fmt, runCatching { unb64(p[2]) }.getOrDefault(""), runCatching { unb64(p[3]) }.getOrDefault(""))
    }

    /** String-Liste: "count;b64,b64,..." (base64 enthält kein Komma -> sicher). */
    fun encodeList(list: List<String>): String =
        "${list.size};" + list.joinToString(",") { b64(it) }

    fun decodeList(s: String): List<String> {
        if (s.isBlank()) return emptyList()
        val i = s.indexOf(';')
        if (i < 0) return emptyList()
        val count = s.substring(0, i).toIntOrNull() ?: 0
        if (count == 0) return emptyList()
        return s.substring(i + 1).split(',').map { unb64(it) }
    }

    /** Ganze Op als eine base64-Zeile -> bequem fürs Stream-Protokoll. */
    fun encodeOpLine(op: OpDto): String = b64(encodeOp(op))
    fun decodeOpLine(line: String): OpDto = decodeOp(unb64(line))

    fun encodeVv(vv: Map<String, PeerState>): String =
        vv.entries.joinToString("\n") { (dev, st) ->
            if (st.gaps.isEmpty()) "$dev ${st.maxSeq}"
            else "$dev ${st.maxSeq} ${st.gaps.joinToString(",")}"
        }

    fun decodeVv(s: String): Map<String, PeerState> {
        if (s.isBlank()) return emptyMap()
        val out = HashMap<String, PeerState>()
        for (line in s.split('\n')) {
            if (line.isBlank()) continue
            val parts = line.split(' ')
            if (parts.size < 2) continue
            val dev = parts[0]
            val max = parts[1].toLongOrNull() ?: continue
            val gaps = parts.getOrNull(2)?.split(',')?.mapNotNull { it.toLongOrNull() } ?: emptyList()
            out[dev] = PeerState(max, gaps)
        }
        return out
    }

    private fun b64(s: String): String = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
    private fun unb64(s: String): String = if (s.isEmpty()) "" else String(Base64.getDecoder().decode(s), Charsets.UTF_8)
    private fun splitCsv(s: String): List<String> = if (s.isEmpty()) emptyList() else s.split(',').filter { it.isNotEmpty() }
}

/**
 * Wissensstand je Autor-Gerät: höchste bekannte Seq **plus** die Lücken darunter. Ohne Lücken kann
 * ein reiner „höchste-Seq"-Vektor fehlende Ops in der MITTE nicht erkennen -> Geräte konvergieren nie.
 */
data class PeerState(val maxSeq: Long, val gaps: List<Long> = emptyList())

/** Quelle/Senke von Operationen für einen Sync (vom Repository implementiert, im Test in-memory). */
interface OpSource {
    fun versionVector(): Map<String, PeerState>
    fun missingFor(remote: Map<String, PeerState>): List<OpDto>
    fun ingestOp(op: OpDto): Boolean

    /** Aktuell angezeigte Blob-Hashes (Bilder/Dateien) für gezielten Blob-Abgleich. */
    fun displayedBlobHashes(): Set<String>
}

data class SyncResult(val pulled: Int, val pushed: Int)

/**
 * Blob-Transfer (Voll-Bilder/-Dateien) direkt zwischen Geräten beim Peer-Sync. Jede Seite nennt die
 * aktuell angezeigten Blobs, die ihr lokal fehlen ([wanted]); die Gegenseite schickt, was sie hat.
 */
interface BlobSync {
    fun wanted(): Set<String>
    fun has(sha: String): Boolean
    fun read(sha: String): ByteArray?
    fun store(sha: String, bytes: ByteArray)
}

/**
 * Subtree-bezogene Quelle/Senke für den **gruppenübergreifenden** Sync (#10): nur die Ops EINES
 * Feeds/Wurzelknotens (über `root_id`), mit Rechtedurchsetzung beim Annehmen von Fremd-Pushes.
 * Der `rootId`-Parameter ist der geteilte Wurzelknoten.
 */
interface FeedScopedSource {
    fun feedVersionVector(rootId: String): Map<String, PeerState>
    fun feedMissingFor(rootId: String, remote: Map<String, PeerState>): List<OpDto>
    fun acceptIncomingOp(op: OpDto, rootId: String): Boolean
    fun acceptForeignOp(op: OpDto, rootId: String, right: de.beardedskunk.homeshare.data.FeedRight): Boolean
}

/** Reconciliation per Versions-Vektor (reine Logik, ohne Transport). Idempotent und konvergent. */
object SyncReconciler {
    fun reconcile(local: OpSource, remote: OpSource): SyncResult {
        val toLocal = remote.missingFor(local.versionVector())
        var pulled = 0
        for (op in toLocal) if (local.ingestOp(op)) pulled++

        val toRemote = local.missingFor(remote.versionVector())
        var pushed = 0
        for (op in toRemote) if (remote.ingestOp(op)) pushed++

        return SyncResult(pulled = pulled, pushed = pushed)
    }
}
