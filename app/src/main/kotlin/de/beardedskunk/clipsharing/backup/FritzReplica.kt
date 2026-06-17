package de.beardedskunk.clipsharing.backup

import android.util.Log
import de.beardedskunk.clipsharing.data.BlobStore
import de.beardedskunk.clipsharing.sync.OpDto
import de.beardedskunk.clipsharing.sync.OpSource
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

data class ReplicaResult(val pulledOps: Int, val pushedOps: Int, val pushedBlobs: Int, val pulledBlobs: Int)

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
            val pulled = pullOps(c)
            val pushedOps = pushOps(c)
            val pushedBlobs = pushBlobs(c)
            val pulledBlobs = pullBlobs(c)
            Log.i(TAG, "Sync fertig: pulledOps=$pulled pushedOps=$pushedOps pushedBlobs=$pushedBlobs pulledBlobs=$pulledBlobs")
            return ReplicaResult(pulled, pushedOps, pushedBlobs, pulledBlobs)
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

    private fun pullOps(c: FTPClient): Int {
        val localVv = source.versionVector()
        val names = (c.listNames(logDir) ?: emptyArray()).map { it.substringAfterLast('/') }
            .filter { it.endsWith(".json") }
        Log.i(TAG, "pullOps: ${names.size} Dateien im log, lokalVv=$localVv")
        var pulled = 0
        for (name in names) {
            val (device, seq) = parseLogName(name) ?: continue
            if (seq <= (localVv[device] ?: 0L)) continue
            val text = retrieveText(c, "$logDir/$name") ?: continue
            val op = runCatching { opFromJson(text) }.getOrNull() ?: continue
            if (source.ingestOp(op)) pulled++
        }
        return pulled
    }

    private fun pushOps(c: FTPClient): Int {
        val remoteVv = remoteVersionVector(c)
        val toPush = source.missingFor(remoteVv)
        Log.i(TAG, "pushOps: ${toPush.size} zu senden, remoteVv=$remoteVv")
        var pushed = 0
        for (op in toPush) {
            val path = "$logDir/${op.deviceId}__${op.seq}.json"
            if (storeBytes(c, path, opToJson(op).toByteArray(Charsets.UTF_8))) pushed++
        }
        return pushed
    }

    private fun pushBlobs(c: FTPClient): Int {
        val remote = (c.listNames(blobsDir) ?: emptyArray()).map { it.substringAfterLast('/') }.toHashSet()
        var pushed = 0
        for ((sha, _) in blobStore.fullSizes()) {
            if (sha in remote) continue
            val bytes = blobStore.readFull(sha) ?: continue
            if (storeBytes(c, "$blobsDir/$sha", bytes)) pushed++
        }
        Log.i(TAG, "pushBlobs: $pushed neue Bilder")
        return pushed
    }

    /** Laedt fehlende, aktuell angezeigte Bilder von der Box (erzeugt dabei Thumbnails). */
    private fun pullBlobs(c: FTPClient): Int {
        var pulled = 0
        for (sha in source.displayedImageHashes()) {
            if (blobStore.hasFull(sha)) continue
            val bytes = retrieveBytes(c, "$blobsDir/$sha") ?: continue
            runCatching { blobStore.putWithSha(sha, bytes) }.onSuccess { pulled++ }
        }
        Log.i(TAG, "pullBlobs: $pulled neue Bilder geladen")
        return pulled
    }

    private fun remoteVersionVector(c: FTPClient): Map<String, Long> {
        val vv = HashMap<String, Long>()
        val names = (c.listNames(logDir) ?: emptyArray()).map { it.substringAfterLast('/') }
        for (name in names) {
            val (device, seq) = parseLogName(name) ?: continue
            if (seq > (vv[device] ?: 0L)) vv[device] = seq
        }
        return vv
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

    private fun opToJson(op: OpDto): String {
        val images = JSONArray()
        op.imageHashes.forEachIndexed { i, sha ->
            images.put(JSONObject().put("sha", sha).put("title", op.imageTitles.getOrElse(i) { "" }))
        }
        return JSONObject()
            .put("versionId", op.versionId)
            .put("feedId", op.feedId)
            .put("postId", op.postId)
            .put("deviceId", op.deviceId)
            .put("seq", op.seq)
            .put("hlcWall", op.hlcWall)
            .put("hlcCounter", op.hlcCounter)
            .put("deleted", op.deleted)
            .put("parents", JSONArray(op.parents))
            .put("text", op.text)
            .put("images", images)
            .toString(2)
    }

    private fun opFromJson(s: String): OpDto {
        val o = JSONObject(s)
        val parents = o.optJSONArray("parents")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()
        val imgs = o.optJSONArray("images")
        val hashes = ArrayList<String>()
        val titles = ArrayList<String>()
        if (imgs != null) {
            for (i in 0 until imgs.length()) {
                val io = imgs.getJSONObject(i)
                hashes.add(io.getString("sha"))
                titles.add(io.optString("title"))
            }
        }
        return OpDto(
            versionId = o.getString("versionId"),
            feedId = o.getString("feedId"),
            postId = o.getString("postId"),
            deviceId = o.getString("deviceId"),
            seq = o.getLong("seq"),
            hlcWall = o.getLong("hlcWall"),
            hlcCounter = o.getInt("hlcCounter"),
            deleted = o.getBoolean("deleted"),
            text = o.getString("text"),
            parents = parents,
            imageHashes = hashes,
            imageTitles = titles,
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
                val stream = c.retrieveFileStream(path) ?: return null // Datei existiert nicht
                val out = ByteArrayOutputStream()
                stream.copyTo(out)
                stream.close()
                if (c.completePendingCommand()) out.toByteArray() else null
            }.getOrNull()
            if (result != null) return result
            Log.w(TAG, "retrieve Versuch ${attempt + 1} fehlgeschlagen für $path (Reply ${c.replyCode})")
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
