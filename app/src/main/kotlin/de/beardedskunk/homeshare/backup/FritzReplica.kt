package de.beardedskunk.homeshare.backup

import android.util.Log
import de.beardedskunk.homeshare.core.NodeType
import de.beardedskunk.homeshare.core.ROOT
import de.beardedskunk.homeshare.data.BlobStore
import de.beardedskunk.homeshare.sync.OpDto
import de.beardedskunk.homeshare.sync.OpSource
import de.beardedskunk.homeshare.sync.PeerState
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

data class FritzConfig(
    val host: String,
    val port: Int,
    val user: String,
    val password: String,
    val baseDir: String,
    val group: String,
    /** Gruppen-Passphrase (Klartext) – wird in group.json zur Zugangskontrolle abgelegt. */
    val passphrase: String,
    val deviceId: String,
    /** true = FTPES (verschluesselt); false = Klartext-FTP (Standard im Heimnetz). */
    val useFtps: Boolean = false,
)

data class ReplicaResult(
    val pulledOps: Int,
    val pushedOps: Int,
    val pushedBlobs: Int,
    val pulledBlobs: Int,
    /** benoetigte Op-Dateien, die trotz Retries NICHT von der Box geladen werden konnten (FTPS-Abbruch). */
    val droppedOps: Int = 0,
)

/**
 * Passive Replik auf der FRITZ!Box ueber FTP. **Alle Dateien liegen im Klartext**
 * (lesbar/anschaubar):
 *   <baseDir>/<group>/group.json                 (Gruppen-Metadaten inkl. Passphrase)
 *   <baseDir>/<group>/log/<deviceId>__<seq>.json (lesbare Op-/Versions-Daten)
 *   <baseDir>/<group>/blobs/<sha>                (rohe Bilddateien)
 *
 * Flaches Log-Layout (Dateiname kodiert Geraet+Seq) -> kein Unterordner-Listing
 * noetig, das auf eingebetteten FTP-Servern oft unzuverlaessig ist.
 *
 * Zugangskontrolle: Das erste Geraet legt group.json mit seiner Passphrase an;
 * weitere Geraete duerfen nur mit derselben Passphrase synchronisieren.
 */
class FritzReplica(
    private val cfg: FritzConfig,
    private val source: OpSource,
    private val blobStore: BlobStore,
) {
    private val root: String = "${cfg.baseDir.trimEnd('/')}/${cfg.group}"
    private val logDir = "$root/log"
    private val blobsDir = "$root/blobs"

    fun sync(): ReplicaResult {
        val c = connect()
        try {
            for (dir in listOf(cfg.baseDir.trimEnd('/'), root, logDir, blobsDir)) c.makeDirectory(dir)
            ensureGroup(c)
            val (pulled, dropped) = pullOps(c)
            val pushedOps = pushOps(c)
            val pushedBlobs = pushBlobs(c)
            val pulledBlobs = pullBlobs(c)
            Log.i(TAG, "Sync fertig: pulledOps=$pulled dropped=$dropped pushedOps=$pushedOps pushedBlobs=$pushedBlobs pulledBlobs=$pulledBlobs")
            return ReplicaResult(pulled, pushedOps, pushedBlobs, pulledBlobs, dropped)
        } finally {
            runCatching { c.logout() }
            runCatching { c.disconnect() }
        }
    }

    fun fetchBlob(sha: String): ByteArray? {
        val c = connect()
        return try {
            retrieveBytes(c, "$blobsDir/$sha")
        } finally {
            runCatching { c.logout() }
            runCatching { c.disconnect() }
        }
    }

    // --------------------------------------------------------- Gruppen-Datei

    private fun ensureGroup(c: FTPClient) {
        val path = "$root/group.json"
        val existing = retrieveText(c, path)
        if (existing == null) {
            val obj = JSONObject()
                .put("version", 1)
                .put("passphrase", cfg.passphrase)
                .put("createdBy", cfg.deviceId)
            if (!storeBytes(c, path, obj.toString(2).toByteArray(Charsets.UTF_8))) {
                error("group.json konnte nicht geschrieben werden (Reply ${c.replyCode})")
            }
            Log.i(TAG, "group.json neu angelegt (Gruppe beansprucht)")
        } else {
            val stored = runCatching { JSONObject(existing).optString("passphrase") }.getOrDefault("")
            if (stored != cfg.passphrase) {
                error("Falsche Gruppen-Passphrase – diese Gruppe gehört zu einer anderen Passphrase.")
            }
        }
    }

    // ------------------------------------------------------------- Schritte

    /** @return (uebernommen, nicht-ladbar) – nicht-ladbar = benoetigte Datei nach Retries nicht geholt. */
    private fun pullOps(c: FTPClient): Pair<Int, Int> {
        val local = source.versionVector()
        val localGaps = local.mapValues { it.value.gaps.toHashSet() }
        val names = (c.listNames(logDir) ?: emptyArray()).map { it.substringAfterLast('/') }
            .filter { it.endsWith(".json") }
        Log.i(TAG, "pullOps: ${names.size} Dateien im log, lokal=$local")
        var pulled = 0
        var dropped = 0
        for (name in names) {
            val (device, seq) = parseLogName(name) ?: continue
            val st = local[device]
            // Vorhanden = bekanntes Geraet, Seq <= max UND nicht in einer Luecke.
            // -> Luecken (fehlende Seqs in der Mitte) werden so von der Box nachgeladen.
            if (st != null && seq <= st.maxSeq && seq !in (localGaps[device] ?: emptySet())) continue
            val text = retrieveText(c, "$logDir/$name")
            if (text == null) { dropped++; continue } // benoetigt, aber nicht ladbar -> sichtbar machen
            val op = runCatching { opFromJson(text) }.getOrNull() ?: continue
            if (source.ingestOp(op)) pulled++
        }
        return pulled to dropped
    }

    private fun pushOps(c: FTPClient): Int {
        val remote = remoteState(c)
        val toPush = source.missingFor(remote)
        Log.i(TAG, "pushOps: ${toPush.size} zu senden, remote=$remote")
        var pushed = 0
        for (op in toPush) {
            val path = "$logDir/${op.deviceId}__${op.seq}.json"
            if (storeBytes(c, path, opToJson(op).toByteArray(Charsets.UTF_8))) pushed++
        }
        return pushed
    }

    private fun pushBlobs(c: FTPClient): Int {
        // Groessen-verifiziert: vorhandene, aber unvollstaendige Reste (z. B. nach
        // 426-Abbruch) werden geloescht und neu hochgeladen.
        val remote = (c.listFiles(blobsDir) ?: emptyArray()).filter { it.isFile }.associate { it.name to it.size }
        var pushed = 0
        for ((sha, size) in blobStore.fullSizes()) {
            if (remote[sha] == size) continue
            if (remote.containsKey(sha)) runCatching { c.deleteFile("$blobsDir/$sha") }
            val bytes = blobStore.readFull(sha) ?: continue
            if (storeBytes(c, "$blobsDir/$sha", bytes)) pushed++ else runCatching { c.deleteFile("$blobsDir/$sha") }
        }
        Log.i(TAG, "pushBlobs: $pushed Bilder (neu/repariert)")
        return pushed
    }

    /** Laedt fehlende, aktuell angezeigte Bilder von der Box (erzeugt dabei Thumbnails). */
    private fun pullBlobs(c: FTPClient): Int {
        var pulled = 0
        for (sha in source.displayedBlobHashes()) {
            if (blobStore.hasFull(sha)) continue
            val bytes = retrieveBytes(c, "$blobsDir/$sha") ?: continue
            runCatching { blobStore.putWithSha(sha, bytes) }
                .onSuccess { pulled++ }
                .onFailure {
                    // Unvollstaendige/falsche Box-Datei entfernen, damit der Eigentuemer sie neu pusht.
                    Log.w(TAG, "Blob $sha unvollstaendig auf Box -> geloescht")
                    runCatching { c.deleteFile("$blobsDir/$sha") }
                }
        }
        Log.i(TAG, "pullBlobs: $pulled neue Bilder geladen")
        return pulled
    }

    /** Wissensstand der Box (hoechste Seq + Luecken je Geraet), aus dem Datei-Listing. */
    private fun remoteState(c: FTPClient): Map<String, PeerState> {
        val seqs = HashMap<String, MutableList<Long>>()
        val names = (c.listNames(logDir) ?: emptyArray()).map { it.substringAfterLast('/') }
        for (name in names) {
            val (device, seq) = parseLogName(name) ?: continue
            seqs.getOrPut(device) { ArrayList() }.add(seq)
        }
        return seqs.mapValues { (_, list) ->
            val max = list.max()
            val present = list.toHashSet()
            PeerState(max, (1L..max).filter { it !in present })
        }
    }

    private fun parseLogName(name: String): Pair<String, Long>? {
        val base = name.removeSuffix(".json")
        val idx = base.lastIndexOf("__")
        if (idx <= 0) return null
        val device = base.substring(0, idx)
        val seq = base.substring(idx + 2).toLongOrNull() ?: return null
        return device to seq
    }

    // ----------------------------------------------------------- JSON (lesbar)

    private fun opToJson(op: OpDto): String =
        JSONObject()
            .put("versionId", op.versionId)
            .put("nodeId", op.nodeId)
            .put("parentId", op.parentId)
            .put("rootId", op.rootId)
            .put("deviceId", op.deviceId)
            .put("deviceName", op.deviceName)
            .put("seq", op.seq)
            .put("hlcWall", op.hlcWall)
            .put("hlcCounter", op.hlcCounter)
            .put("deleted", op.deleted)
            .put("type", op.type.name)
            .put("orderKey", op.orderKey)
            .put("color", op.color ?: JSONObject.NULL)
            .put("childDefault", op.childDefault?.name ?: JSONObject.NULL)
            .put("done", op.done)
            .put("blobHash", op.blobHash ?: JSONObject.NULL)
            .put("fileName", op.fileName ?: JSONObject.NULL)
            .put("mime", op.mime ?: JSONObject.NULL)
            .put("tags", JSONArray(op.tags))
            .put("parents", JSONArray(op.parents))
            .put("text", op.text)
            .toString(2)

    private fun opFromJson(s: String): OpDto {
        val o = JSONObject(s)
        fun strList(name: String): List<String> =
            o.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        fun nodeType(name: String): NodeType? =
            o.optString(name).takeIf { it.isNotBlank() }?.let { runCatching { NodeType.valueOf(it) }.getOrNull() }
        return OpDto(
            versionId = o.getString("versionId"),
            nodeId = o.getString("nodeId"),
            parentId = o.optString("parentId", ROOT),
            rootId = o.optString("rootId"),
            deviceId = o.getString("deviceId"),
            seq = o.getLong("seq"),
            hlcWall = o.getLong("hlcWall"),
            hlcCounter = o.getInt("hlcCounter"),
            deleted = o.getBoolean("deleted"),
            type = nodeType("type") ?: NodeType.TEXT,
            orderKey = o.optString("orderKey"),
            color = if (o.isNull("color")) null else o.optInt("color"),
            childDefault = nodeType("childDefault"),
            done = o.optBoolean("done"),
            blobHash = if (o.isNull("blobHash")) null else o.optString("blobHash").takeIf { it.isNotBlank() },
            fileName = if (o.isNull("fileName")) null else o.optString("fileName").takeIf { it.isNotBlank() },
            mime = if (o.isNull("mime")) null else o.optString("mime").takeIf { it.isNotBlank() },
            tags = strList("tags"),
            text = o.optString("text"),
            parents = strList("parents"),
            deviceName = o.optString("deviceName"),
        )
    }

    // -------------------------------------------------------------- FTP-Helfer

    fun connect(): FTPClient {
        val c: FTPClient = if (cfg.useFtps) FTPSClient(false) else FTPClient()
        c.connectTimeout = 8000
        c.connect(cfg.host, cfg.port)
        if (!FTPReply.isPositiveCompletion(c.replyCode)) {
            c.disconnect()
            error("FTP-Verbindung abgelehnt (Code ${c.replyCode})")
        }
        if (!c.login(cfg.user, cfg.password)) {
            c.disconnect()
            error("FTP-Login fehlgeschlagen – Benutzer/Passwort prüfen")
        }
        if (c is FTPSClient) {
            c.execPBSZ(0)
            c.execPROT("P")
        }
        c.enterLocalPassiveMode()
        c.setFileType(FTP.BINARY_FILE_TYPE)
        c.controlEncoding = "UTF-8"
        Log.i(TAG, "FTP verbunden mit ${cfg.host}:${cfg.port} (ftps=${cfg.useFtps})")
        return c
    }

    private fun retrieveBytes(c: FTPClient, path: String): ByteArray? {
        // Mit Retry gegen sporadische FTPS-Datenkanal-Abbrüche (z. B. Reply 426).
        repeat(MAX_TRIES) { attempt ->
            val result = runCatching {
                val stream = c.retrieveFileStream(path) ?: return@runCatching null
                val out = ByteArrayOutputStream()
                stream.copyTo(out)
                stream.close()
                if (c.completePendingCommand()) out.toByteArray() else null
            }.getOrNull()
            if (result != null) return result
            Log.w(TAG, "retrieve Versuch ${attempt + 1} fehlgeschlagen für $path (Reply ${c.replyCode})")
            runCatching { c.completePendingCommand() } // Steuerkanal nach Fehlversuch resynchronisieren
        }
        return null
    }

    private fun retrieveText(c: FTPClient, path: String): String? =
        retrieveBytes(c, path)?.let { String(it, Charsets.UTF_8) }

    private fun storeBytes(c: FTPClient, path: String, bytes: ByteArray): Boolean {
        // Mit Retry gegen sporadische FTPS-Datenkanal-Abbrüche (Reply 426).
        repeat(MAX_TRIES) { attempt ->
            val ok = runCatching { ByteArrayInputStream(bytes).use { c.storeFile(path, it) } }.getOrDefault(false)
            if (ok) return true
            Log.w(TAG, "storeFile Versuch ${attempt + 1} fehlgeschlagen für $path (Reply ${c.replyCode})")
        }
        return false
    }

    companion object {
        private const val TAG = "FritzReplica"
        private const val MAX_TRIES = 3
    }
}
