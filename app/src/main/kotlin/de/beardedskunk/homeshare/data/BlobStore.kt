package de.beardedskunk.homeshare.data

import de.beardedskunk.homeshare.core.Hashing
import java.io.File

/**
 * Content-adressierter Speicher fuer Bild-Blobs (Dateiname = SHA-256).
 *
 * - Voll-Bilder liegen unter `blobs/<sha>` und unterliegen dem lokalen Budget.
 * - Thumbnails liegen unter `thumbs/<sha>` und werden **immer** behalten, damit
 *   der Feed auch nach Eviction eine Vorschau zeigt.
 *
 * Bewusst Context-frei (nur [File] + injizierbarer [thumbnailer]) -> per Unit-Test
 * mit einem Temp-Verzeichnis pruefbar; die Android-Bitmap-Logik wird von aussen
 * hineingereicht (siehe androidThumbnailer).
 */
class BlobStore(
    baseDir: File,
    private val thumbnailer: (ByteArray) -> ByteArray? = { null },
) {
    private val blobsDir = File(baseDir, "blobs").apply { mkdirs() }
    private val thumbsDir = File(baseDir, "thumbs").apply { mkdirs() }

    fun fullFile(sha: String): File = File(blobsDir, sha)
    fun thumbFile(sha: String): File = File(thumbsDir, sha)
    fun hasFull(sha: String): Boolean = fullFile(sha).exists()
    fun hasThumb(sha: String): Boolean = thumbFile(sha).exists()

    /** Speichert Bytes, liefert deren SHA-256. Erzeugt bei Bedarf ein Thumbnail. */
    fun put(bytes: ByteArray): String {
        val sha = Hashing.sha256Hex(bytes)
        putWithSha(sha, bytes)
        return sha
    }

    /** Fuer beim Sync/On-Demand empfangene Blobs (SHA bereits bekannt, wird geprueft). */
    fun putWithSha(sha: String, bytes: ByteArray) {
        require(Hashing.sha256Hex(bytes) == sha) { "SHA passt nicht zum Inhalt" }
        val f = fullFile(sha)
        if (!f.exists()) f.writeBytes(bytes)
        ensureThumb(sha, bytes)
    }

    private fun ensureThumb(sha: String, fullBytes: ByteArray) {
        if (hasThumb(sha)) return
        thumbnailer(fullBytes)?.let { thumbFile(sha).writeBytes(it) }
    }

    /** Entfernt nur das Voll-Bild lokal; das Thumbnail bleibt erhalten. */
    fun deleteFull(sha: String): Boolean = fullFile(sha).delete()

    fun readFull(sha: String): ByteArray? = fullFile(sha).takeIf { it.exists() }?.readBytes()

    fun totalFullBytes(): Long = blobsDir.listFiles()?.sumOf { it.length() } ?: 0L

    /** SHA -> Dateigroesse fuer alle lokal vorgehaltenen Voll-Bilder. */
    fun fullSizes(): Map<String, Long> =
        blobsDir.listFiles()?.associate { it.name to it.length() } ?: emptyMap()

    companion object {
        /**
         * Loescht ALLE gespeicherten Blobs + Thumbnails unter [baseDir]. Wird beim inkompatiblen
         * DB-Wipe aufgerufen, damit keine verwaisten Bild-/Datei-Blobs des alten Schemas zurueck-
         * bleiben (die Verzeichnisse werden bei der naechsten [BlobStore]-Konstruktion neu angelegt).
         */
        fun purgeAll(baseDir: File) {
            File(baseDir, "blobs").deleteRecursively()
            File(baseDir, "thumbs").deleteRecursively()
        }
    }
}
