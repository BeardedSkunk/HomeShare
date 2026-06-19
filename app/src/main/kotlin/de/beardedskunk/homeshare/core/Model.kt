package de.beardedskunk.homeshare.core

/**
 * Kern-Datenmodell des geteilten Feeds.
 *
 * Jeder Post hat eine git-artige Historie aus [PostVersion]-Knoten (ein DAG).
 * Eine neue Bearbeitung verweist als Eltern auf den/die aktuellen Head(s).
 * Nebenlaeufige Bearbeitungen erzeugen mehrere Heads -> Konflikt (siehe [Post]).
 *
 * Dieses Paket ist bewusst frei von Android-Abhaengigkeiten, damit die
 * Konflikt-Logik per JVM-Unit-Test verifiziert werden kann.
 */

/** Hybrid Logical Clock: stabile Reihenfolge trotz unzuverlaessiger Geraeteuhren. */
data class Hlc(val wallMillis: Long, val counter: Int) : Comparable<Hlc> {
    override fun compareTo(other: Hlc): Int {
        val w = wallMillis.compareTo(other.wallMillis)
        return if (w != 0) w else counter.compareTo(other.counter)
    }

    companion object {
        /** Naechste lokale Uhr aus der bisher hoechsten bekannten Uhr ableiten. */
        fun next(now: Long, last: Hlc?): Hlc = when {
            last == null -> Hlc(now, 0)
            now > last.wallMillis -> Hlc(now, 0)
            else -> Hlc(last.wallMillis, last.counter + 1)
        }
    }
}

/**
 * Inhalt einer Post-Version. [deleted] = Tombstone (geloeschte Version).
 * [imageTitles] ist (sofern gesetzt) parallel zu [imageHashes] – pro Bild ein Titel.
 */
data class PostContent(
    val text: String = "",
    val imageHashes: List<String> = emptyList(),
    val imageTitles: List<String> = emptyList(),
    val deleted: Boolean = false,
)

/**
 * Ein unveraenderlicher Versionsknoten. [versionId] ist der SHA-256 ueber den
 * kanonisch kodierten Inhalt inkl. Eltern -> inhaltsadressiert wie ein git-Commit,
 * dadurch geraeteuebergreifend identisch und Ingest idempotent.
 */
class PostVersion(
    val postId: String,
    val parents: Set<String>,
    val deviceId: String,
    val hlc: Hlc,
    val content: PostContent,
) {
    val versionId: String = Hashing.sha256Hex(canonical())

    private fun canonical(): String = buildString {
        append("post:").append(postId).append('\n')
        append("parents:")
        parents.sorted().forEach { append(it).append(',') }
        append('\n')
        append("device:").append(deviceId).append('\n')
        append("hlc:").append(hlc.wallMillis).append('-').append(hlc.counter).append('\n')
        append("deleted:").append(content.deleted).append('\n')
        append("images:")
        content.imageHashes.forEach { append(it).append(',') }
        append('\n')
        // Bild-Titel laengenpraefixiert (Teil des Inhalts -> fliessen in die versionId,
        // damit Titel-Aenderungen versioniert/gesynct werden).
        append("titles:").append(content.imageTitles.size).append('\n')
        content.imageTitles.forEach { append(it.length).append(':').append(it).append('\n') }
        // Text zuletzt und laengenpraefixiert, damit nichts ueber Trenner kollidiert.
        append("text:").append(content.text.length).append(':').append(content.text)
    }

    override fun equals(other: Any?): Boolean = other is PostVersion && other.versionId == versionId
    override fun hashCode(): Int = versionId.hashCode()
    override fun toString(): String = "PostVersion(${versionId.take(8)}, parents=${parents.map { it.take(8) }}, ${content})"
}
