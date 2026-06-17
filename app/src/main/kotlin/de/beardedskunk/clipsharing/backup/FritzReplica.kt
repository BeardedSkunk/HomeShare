package de.beardedskunk.clipsharing.backup

import de.beardedskunk.clipsharing.data.BlobStore
import de.beardedskunk.clipsharing.sync.OpCodec
import de.beardedskunk.clipsharing.sync.OpSource
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

data class FritzConfig(
    val host: String,
    val port: Int,
    val user: String,
    val password: String,
    val baseDir: String,
    val group: String,
    /** true = FTPES (verschluesselt); false = Klartext-FTP (Standard im Heimnetz). */
    val useFtps: Boolean = false,
)

data class ReplicaResult(val pulledOps: Int, val pushedOps: Int, val pushedBlobs: Int)

/**
 * Vollwertige passive Replik auf der FRITZ!Box ueber **FTPES** (FTP explicit TLS).
 *
 * Ablage (portabel fuer spaeteren NAS-Wechsel):
 *   <baseDir>/<group>/log/<deviceId>/<seq>.op   (unveraenderliche Ops)
 *   <baseDir>/<group>/blobs/<sha>               (Voll-Bilder)
 *
 * Der Versions-Vektor der Box ergibt sich aus dem Verzeichnis-Listing -> keine
 * mutierten Indexdateien, daher kollisionsfrei bei parallelem Push mehrerer Geraete.
 *
 * Hinweis: Die Box nutzt aeltere TLS-Cipher (AES-256-CBC/SHA1, ggf. TLS 1.0);
 * daher werden hier aeltere Protokolle explizit erlaubt. Benoetigt echtes
 * Geraet/Netz zum Testen; ggf. Cipher-Feintuning noetig.
 */
class FritzReplica(
    private val cfg: FritzConfig,
    private val source: OpSource,
    private val blobStore: BlobStore,
) {
    private val root: String = "${cfg.baseDir.trimEnd('/')}/${cfg.group}"

    fun sync(): ReplicaResult {
        val ftps = connect()
        try {
            for (dir in listOf(cfg.baseDir.trimEnd('/'), root, "$root/log", "$root/blobs")) {
                ftps.makeDirectory(dir)
            }

            val pulled = pullOps(ftps)
            val pushedOps = pushOps(ftps)
            val pushedBlobs = pushBlobs(ftps)
            return ReplicaResult(pulled, pushedOps, pushedBlobs)
        } finally {
            runCatching { ftps.logout() }
            runCatching { ftps.disconnect() }
        }
    }

    /** On-Demand: ein Voll-Bild von der Box holen (falls lokal evictet). */
    fun fetchBlob(sha: String): ByteArray? {
        val ftps = connect()
        return try {
            retrieveBytes(ftps, "$root/blobs/$sha")
        } finally {
            runCatching { ftps.logout() }
            runCatching { ftps.disconnect() }
        }
    }

    // -------------------------------------------------------------- Schritte

    private fun pullOps(ftps: FTPClient): Int {
        val localVv = source.versionVector()
        var pulled = 0
        val deviceDirs = ftps.listFiles("$root/log").filter { it.isDirectory }.map { it.name }
        for (device in deviceDirs) {
            val known = localVv[device] ?: 0L
            val names = ftps.listNames("$root/log/$device") ?: continue
            for (path in names) {
                val name = path.substringAfterLast('/')
                val seq = name.removeSuffix(".op").toLongOrNull() ?: continue
                if (seq <= known) continue
                val content = retrieveText(ftps, "$root/log/$device/$name") ?: continue
                val op = runCatching { OpCodec.decodeOp(content) }.getOrNull() ?: continue
                if (source.ingestOp(op)) pulled++
            }
        }
        return pulled
    }

    private fun pushOps(ftps: FTPClient): Int {
        val remoteVv = remoteVersionVector(ftps)
        var pushed = 0
        for (op in source.missingFor(remoteVv)) {
            ftps.makeDirectory("$root/log/${op.deviceId}")
            val path = "$root/log/${op.deviceId}/${op.seq}.op"
            if (storeBytes(ftps, path, OpCodec.encodeOp(op).toByteArray(Charsets.UTF_8))) pushed++
        }
        return pushed
    }

    private fun pushBlobs(ftps: FTPClient): Int {
        val remote = (ftps.listNames("$root/blobs") ?: emptyArray())
            .map { it.substringAfterLast('/') }.toHashSet()
        var pushed = 0
        for ((sha, _) in blobStore.fullSizes()) {
            if (sha in remote) continue
            val bytes = blobStore.readFull(sha) ?: continue
            if (storeBytes(ftps, "$root/blobs/$sha", bytes)) pushed++
        }
        return pushed
    }

    private fun remoteVersionVector(ftps: FTPClient): Map<String, Long> {
        val vv = HashMap<String, Long>()
        val deviceDirs = ftps.listFiles("$root/log").filter { it.isDirectory }.map { it.name }
        for (device in deviceDirs) {
            val names = ftps.listNames("$root/log/$device") ?: continue
            val maxSeq = names.mapNotNull { it.substringAfterLast('/').removeSuffix(".op").toLongOrNull() }.maxOrNull()
            if (maxSeq != null) vv[device] = maxSeq
        }
        return vv
    }

    // -------------------------------------------------------------- FTP-Helfer

    /** Verbindet die Box (FTPES nur, wenn [FritzConfig.useFtps]); sonst Klartext-FTP. */
    fun connect(): FTPClient {
        val c: FTPClient = if (cfg.useFtps) {
            FTPSClient(false).also { runCatching { it.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.1", "TLSv1") } }
        } else {
            FTPClient()
        }
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
            c.execPROT("P") // Datenkanal verschluesseln
        }
        c.enterLocalPassiveMode()
        c.setFileType(FTP.BINARY_FILE_TYPE)
        c.controlEncoding = "UTF-8"
        return c
    }

    /**
     * Testet die Verbindung und legt die Ordnerstruktur an. Wirft mit aussagekraeftiger
     * Meldung, wenn etwas schiefgeht; liefert sonst eine Erfolgsmeldung.
     */
    fun testAndPrepare(): String {
        val c = connect()
        try {
            for (dir in listOf(cfg.baseDir.trimEnd('/'), root, "$root/log", "$root/blobs")) {
                c.makeDirectory(dir)
            }
            val names = c.listNames(root)
                ?: error("Ordner '$root' nicht lesbar – Schreibrecht/Pfad prüfen")
            return "Verbunden mit ${cfg.host}. Ordner '$root' bereit (${names.size} Einträge)."
        } finally {
            runCatching { c.logout() }
            runCatching { c.disconnect() }
        }
    }

    private fun retrieveBytes(ftps: FTPClient, path: String): ByteArray? {
        val stream = ftps.retrieveFileStream(path) ?: return null
        return try {
            val out = ByteArrayOutputStream()
            stream.copyTo(out)
            stream.close()
            if (ftps.completePendingCommand()) out.toByteArray() else null
        } catch (e: Exception) {
            null
        }
    }

    private fun retrieveText(ftps: FTPClient, path: String): String? =
        retrieveBytes(ftps, path)?.let { String(it, Charsets.UTF_8) }

    private fun storeBytes(ftps: FTPClient, path: String, bytes: ByteArray): Boolean =
        ByteArrayInputStream(bytes).use { ftps.storeFile(path, it) }
}
