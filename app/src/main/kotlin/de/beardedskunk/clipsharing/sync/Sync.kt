package de.beardedskunk.clipsharing.sync

import de.beardedskunk.clipsharing.core.Hlc
import de.beardedskunk.clipsharing.core.PostContent
import de.beardedskunk.clipsharing.core.PostVersion
import java.util.Base64

/**
 * Eine Operation (Versionsknoten) im uebertragbaren Format. Enthaelt zusaetzlich
 * zur reinen [PostVersion] die Routing-/Sync-Metadaten [feedId] und [seq].
 */
data class OpDto(
    val versionId: String,
    val feedId: String,
    val postId: String,
    val deviceId: String,
    val seq: Long,
    val hlcWall: Long,
    val hlcCounter: Int,
    val deleted: Boolean,
    val text: String,
    val parents: List<String>,
    val imageHashes: List<String>,
    val imageTitles: List<String> = emptyList(),
    /** Menschlicher Name des Autor-Geraets (Metadatum, fliesst NICHT in versionId). */
    val deviceName: String = "",
) {
    fun toVersion(): PostVersion = PostVersion(
        postId = postId,
        parents = parents.toSet(),
        deviceId = deviceId,
        hlc = Hlc(hlcWall, hlcCounter),
        content = PostContent(text = text, imageHashes = imageHashes, imageTitles = imageTitles, deleted = deleted),
    )

    /** Integritaetspruefung: stimmt die mitgelieferte Id mit dem Inhalt ueberein? */
    fun isConsistent(): Boolean = toVersion().versionId == versionId

    companion object {
        fun from(v: PostVersion, feedId: String, seq: Long, deviceName: String = ""): OpDto = OpDto(
            versionId = v.versionId,
            feedId = feedId,
            postId = v.postId,
            deviceId = v.deviceId,
            seq = seq,
            hlcWall = v.hlc.wallMillis,
            hlcCounter = v.hlc.counter,
            deleted = v.content.deleted,
            text = v.content.text,
            parents = v.parents.toList(),
            imageHashes = v.content.imageHashes,
            imageTitles = v.content.imageTitles,
            deviceName = deviceName,
        )
    }
}

/**
 * Einfaches, eigenkontrolliertes Wire-Format (zeilenbasiert, Text base64-kodiert,
 * damit Zeilenumbrueche im Text nicht stoeren). Bewusst ohne externe Serialisierungs-
 * bibliothek -> keine zusaetzlichen Versions-/Plugin-Abhaengigkeiten, voll testbar.
 */
object OpCodec {
    private const val HEADER = "CLIPOP1"

    fun encodeOp(d: OpDto): String = buildString {
        append(HEADER).append('\n')
        append(d.versionId).append('\n')
        append(d.feedId).append('\n')
        append(d.postId).append('\n')
        append(d.deviceId).append('\n')
        append(d.seq).append('\n')
        append(d.hlcWall).append('\n')
        append(d.hlcCounter).append('\n')
        append(if (d.deleted) "1" else "0").append('\n')
        append(b64(d.text)).append('\n')
        append(d.parents.joinToString(",")).append('\n')
        append(d.imageHashes.joinToString(",")).append('\n')
        append(encodeTitles(d.imageTitles)).append('\n')
        append(b64(d.deviceName))
    }

    fun decodeOp(s: String): OpDto {
        val p = s.split('\n')
        require(p[0] == HEADER) { "Unbekanntes Format: ${p[0]}" }
        return OpDto(
            versionId = p[1],
            feedId = p[2],
            postId = p[3],
            deviceId = p[4],
            seq = p[5].toLong(),
            hlcWall = p[6].toLong(),
            hlcCounter = p[7].toInt(),
            deleted = p[8] == "1",
            text = unb64(p[9]),
            parents = splitCsv(p.getOrElse(10) { "" }),
            imageHashes = splitCsv(p.getOrElse(11) { "" }),
            imageTitles = decodeTitles(p.getOrElse(12) { "" }),
            deviceName = p.getOrNull(13)?.takeIf { it.isNotBlank() }?.let { unb64(it) } ?: "",
        )
    }

    /** Titel-Liste: "count;b64,b64,..." (base64 enthaelt kein Komma -> sicher). */
    fun encodeTitles(list: List<String>): String =
        "${list.size};" + list.joinToString(",") { b64(it) }

    fun decodeTitles(s: String): List<String> {
        if (s.isBlank()) return emptyList()
        val i = s.indexOf(';')
        if (i < 0) return emptyList()
        val count = s.substring(0, i).toIntOrNull() ?: 0
        if (count == 0) return emptyList()
        // Bei count>0 IMMER splitten: "1;" -> rest "" -> [""] (eine leere Titel-Zeile).
        // Frueher wurde rest=="" als leere Liste behandelt -> ein einzelner leerer Titel
        // ging verloren, wodurch sich die versionId nach DB-Roundtrip aenderte
        // (Phantom-Konflikte bei Bildern ohne Titel).
        val rest = s.substring(i + 1)
        return rest.split(',').map { unb64(it) }
    }

    /** Ganze Op als eine einzelne (base64-)Zeile -> bequem fuers Stream-Protokoll. */
    fun encodeOpLine(op: OpDto): String = b64(encodeOp(op))
    fun decodeOpLine(line: String): OpDto = decodeOp(unb64(line))

    /**
     * Wissensstand: je Zeile "deviceId maxSeq[ gap1,gap2,...]". Die optionalen Luecken
     * (fehlende Seqs unter maxSeq) ermoeglichen das Schliessen von Loechern beim Sync.
     * deviceId enthaelt nie Leerzeichen (UUID bzw. feste Namen).
     */
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
    private fun unb64(s: String): String = String(Base64.getDecoder().decode(s), Charsets.UTF_8)
    private fun splitCsv(s: String): List<String> = if (s.isEmpty()) emptyList() else s.split(',').filter { it.isNotEmpty() }
}

/**
 * Wissensstand je Autor-Geraet: hoechste bekannte Seq **plus** die Luecken darunter
 * (fehlende Seqs). Ohne die Luecken kann ein reiner „hoechste-Seq"-Vektor fehlende
 * Ops in der MITTE nicht erkennen – dann konvergieren Geraete nie (genau der Fehler,
 * der haengende Konflikte verursacht hat). [gaps] ist normalerweise leer.
 */
data class PeerState(val maxSeq: Long, val gaps: List<Long> = emptyList())

/**
 * Quelle/Senke von Operationen fuer einen Sync. Wird vom Repository implementiert;
 * im Test durch eine In-Memory-Variante ersetzt.
 */
interface OpSource {
    /** Wissensstand je Autor-Geraet (hoechste Seq + Luecken). */
    fun versionVector(): Map<String, PeerState>

    /** Alle lokalen Ops, die der Gegenseite (gegeben deren Wissensstand) fehlen. */
    fun missingFor(remote: Map<String, PeerState>): List<OpDto>

    /** Speist eine empfangene Op ein. @return true, wenn neu. */
    fun ingestOp(op: OpDto): Boolean

    /** Aktuell angezeigte Bild-Hashes (fuer gezielten Blob-Abgleich). */
    fun displayedImageHashes(): Set<String>
}

data class SyncResult(val pulled: Int, val pushed: Int)

/**
 * Blob-Transfer (Voll-Bilder) direkt zwischen Geräten beim Peer-Sync (#11) – ohne Umweg
 * über die FRITZ!Box. Jede Seite nennt die aktuell angezeigten Bilder, die ihr lokal fehlen
 * ([wanted]); die Gegenseite schickt, was sie davon hat.
 */
interface BlobSync {
    fun wanted(): Set<String>
    fun has(sha: String): Boolean
    fun read(sha: String): ByteArray?
    fun store(sha: String, bytes: ByteArray)
}

/**
 * Feed-bezogene Quelle/Senke fuer den **gruppenuebergreifenden** Sync (#10): nur die Ops
 * EINES Feeds, mit Rechtedurchsetzung beim Annehmen von Fremd-Pushes.
 */
interface FeedScopedSource {
    fun feedVersionVector(feedId: String): Map<String, PeerState>
    fun feedMissingFor(feedId: String, remote: Map<String, PeerState>): List<OpDto>
    /** Vom Original empfangene Op uebernehmen (nur Feed-Zugehoerigkeit pruefen). @return true=neu. */
    fun acceptIncomingOp(op: OpDto, feedId: String): Boolean
    /**
     * Fremd-Push gemaess Recht uebernehmen: nur bei write/merge; Merge-Ops (mehrere Eltern)
     * nur bei merge. @return true=uebernommen (neu).
     */
    fun acceptForeignOp(op: OpDto, feedId: String, right: de.beardedskunk.clipsharing.data.FeedRight): Boolean
}

/**
 * Reconciliation per Versions-Vektor (reine Logik, ohne Transport):
 * beide Seiten tauschen, was der jeweils anderen fehlt. Idempotent und konvergent.
 */
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
