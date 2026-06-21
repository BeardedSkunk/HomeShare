package de.beardedskunk.homeshare.sync

import de.beardedskunk.homeshare.core.Hlc
import de.beardedskunk.homeshare.core.NodeContent
import de.beardedskunk.homeshare.core.NodeType
import de.beardedskunk.homeshare.core.NodeVersion
import java.util.Base64

/**
 * Eine Operation (Knoten-Versionsknoten) im übertragbaren Format. Enthält zusätzlich zur reinen
 * [NodeVersion] das Routing-Metadatum [rootId] (oberster Vorfahr-Knoten = Feed) und [seq].
 * [rootId]/[seq]/[deviceName] fließen NICHT in die versionId.
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
    val color: Int?,
    val childDefault: NodeType?,
    val done: Boolean,
    val blobHash: String?,
    val fileName: String?,
    val mime: String?,
    val tags: List<String>,
    val text: String,
    val parents: List<String>,
    val deviceName: String = "",
) {
    fun toVersion(): NodeVersion = NodeVersion(
        nodeId = nodeId,
        parents = parents.toSet(),
        deviceId = deviceId,
        hlc = Hlc(hlcWall, hlcCounter),
        content = NodeContent(
            parentId = parentId, type = type, orderKey = orderKey, text = text, done = done,
            blobHash = blobHash, fileName = fileName, mime = mime, color = color,
            childDefault = childDefault, tags = tags, deleted = deleted,
        ),
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
            color = v.content.color,
            childDefault = v.content.childDefault,
            done = v.content.done,
            blobHash = v.content.blobHash,
            fileName = v.content.fileName,
            mime = v.content.mime,
            tags = v.content.tags,
            text = v.content.text,
            parents = v.parents.toList(),
            deviceName = deviceName,
        )
    }
}

/**
 * Einfaches, eigenkontrolliertes Wire-Format (zeilenbasiert, Freitext base64-kodiert). Bewusst ohne
 * externe Serialisierungsbibliothek, voll testbar.
 */
object OpCodec {
    private const val HEADER = "HSNODE1"

    fun encodeOp(d: OpDto): String = buildString {
        append(HEADER).append('\n')
        append(d.versionId).append('\n')
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
        append(d.color?.toString() ?: "").append('\n')
        append(d.childDefault?.name ?: "").append('\n')
        append(if (d.done) "1" else "0").append('\n')
        append(d.blobHash ?: "").append('\n')
        append(d.mime ?: "").append('\n')
        append(b64(d.fileName ?: "")).append('\n')
        append(encodeList(d.tags)).append('\n')
        append(d.parents.joinToString(",")).append('\n')
        append(b64(d.text)).append('\n')
        append(b64(d.deviceName))
    }

    fun decodeOp(s: String): OpDto {
        val p = s.split('\n')
        require(p[0] == HEADER) { "Unbekanntes Format: ${p[0]}" }
        return OpDto(
            versionId = p[1],
            nodeId = p[2],
            parentId = p[3],
            rootId = p[4],
            deviceId = p[5],
            seq = p[6].toLong(),
            hlcWall = p[7].toLong(),
            hlcCounter = p[8].toInt(),
            deleted = p[9] == "1",
            type = runCatching { NodeType.valueOf(p[10]) }.getOrDefault(NodeType.TEXT),
            orderKey = unb64(p.getOrElse(11) { "" }),
            color = p.getOrElse(12) { "" }.toIntOrNull(),
            childDefault = p.getOrElse(13) { "" }.takeIf { it.isNotBlank() }?.let { runCatching { NodeType.valueOf(it) }.getOrNull() },
            done = p.getOrElse(14) { "0" } == "1",
            blobHash = p.getOrElse(15) { "" }.takeIf { it.isNotBlank() },
            mime = p.getOrElse(16) { "" }.takeIf { it.isNotBlank() },
            fileName = unb64(p.getOrElse(17) { "" }).takeIf { it.isNotBlank() },
            tags = decodeList(p.getOrElse(18) { "" }),
            parents = splitCsv(p.getOrElse(19) { "" }),
            text = unb64(p.getOrElse(20) { "" }),
            deviceName = p.getOrNull(21)?.takeIf { it.isNotBlank() }?.let { unb64(it) } ?: "",
        )
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
