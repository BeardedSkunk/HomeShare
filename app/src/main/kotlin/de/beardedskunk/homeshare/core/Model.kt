package de.beardedskunk.homeshare.core

/**
 * Kern-Datenmodell: ein **Knoten-Baum**. Alles (Feed, Eintrag, Bild, Datei, Termin, Todo) ist ein
 * [NodeVersion]-Knoten im selben versionierten DAG. Ein Feed = Wurzelknoten (Typ TEXT, parent=[ROOT]);
 * ein „Post" = TEXT-Knoten darunter; Bilder/Dateien = Kindknoten, deren Beschreibung wiederum ein
 * TEXT-Kindknoten ist.
 *
 * Jeder Knoten hat eine git-artige Historie aus Versionen (DAG). Nebenläufige Bearbeitungen erzeugen
 * mehrere Heads -> Konflikt (siehe [Node]). Bewusst Android-frei für JVM-Unit-Tests.
 */

/** Sentinel-Eltern-ID für Knoten auf oberster Ebene (die „Feeds"). */
const val ROOT = "ROOT"

/** Knotentyp. Technisch ist jeder Typ überall erlaubt; die UI schränkt ein. */
enum class NodeType { TEXT, CALENDAR, IMAGE, FILE, TODO }

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
 * Inhalt einer Knoten-Version (fließt vollständig in die [NodeVersion.versionId]).
 *
 * - [parentId]/[orderKey] bilden den Baum (Position unter dem Eltern­knoten).
 * - [type] bestimmt die Bedeutung des Payloads:
 *   - TEXT/CALENDAR/TODO -> [text] (TEXT: 1. Zeile Titel + Markdown; CALENDAR: EventCodec; TODO: Titel).
 *   - TODO zusätzlich [done].
 *   - IMAGE/FILE -> [blobHash] + [fileName] + [mime].
 * - [color] (ARGB) und [tags] sind optional an jedem Knoten; [childDefault] ist ein UI-Hinweis,
 *   welcher Kindtyp gemeint ist (z. B. CALENDAR für einen Kalender-Feed).
 * - [deleted] = Tombstone (löscht den Knoten + blendet seinen Teilbaum aus).
 */
data class NodeContent(
    val parentId: String = ROOT,
    val type: NodeType = NodeType.TEXT,
    val orderKey: String = "",
    val text: String = "",
    val done: Boolean = false,
    val blobHash: String? = null,
    val fileName: String? = null,
    val mime: String? = null,
    val color: Int? = null,
    val childDefault: NodeType? = null,
    val tags: List<String> = emptyList(),
    val deleted: Boolean = false,
)

/**
 * Ein unveraenderlicher Versionsknoten. [versionId] ist der SHA-256 ueber den kanonisch kodierten
 * Inhalt inkl. DAG-Eltern -> inhaltsadressiert wie ein git-Commit, geraeteuebergreifend identisch,
 * Ingest idempotent. Einheitliches Format für alle [NodeType]s (keine Sonderfälle).
 */
class NodeVersion(
    val nodeId: String,
    val parents: Set<String>,
    val deviceId: String,
    val hlc: Hlc,
    val content: NodeContent,
) {
    val versionId: String = Hashing.sha256Hex(canonical())

    private fun canonical(): String = buildString {
        append("node:").append(nodeId).append('\n')
        append("parents:")
        parents.sorted().forEach { append(it).append(',') }
        append('\n')
        append("device:").append(deviceId).append('\n')
        append("hlc:").append(hlc.wallMillis).append('-').append(hlc.counter).append('\n')
        append("deleted:").append(content.deleted).append('\n')
        append("type:").append(content.type.name).append('\n')
        append("parent:").append(content.parentId).append('\n')
        // Freitext-Felder laengenpraefixiert, damit nichts ueber Trenner kollidiert.
        append("order:").append(content.orderKey.length).append(':').append(content.orderKey).append('\n')
        append("color:").append(content.color?.toString() ?: "").append('\n')
        append("childDefault:").append(content.childDefault?.name ?: "").append('\n')
        append("done:").append(content.done).append('\n')
        append("blob:").append(content.blobHash ?: "").append('\n')
        append("mime:").append(content.mime ?: "").append('\n')
        val fn = content.fileName ?: ""
        append("file:").append(fn.length).append(':').append(fn).append('\n')
        append("tags:").append(content.tags.size).append('\n')
        content.tags.forEach { append(it.length).append(':').append(it).append('\n') }
        // Text zuletzt und laengenpraefixiert.
        append("text:").append(content.text.length).append(':').append(content.text)
    }

    override fun equals(other: Any?): Boolean = other is NodeVersion && other.versionId == versionId
    override fun hashCode(): Int = versionId.hashCode()
    override fun toString(): String = "NodeVersion(${versionId.take(8)}, ${content.type}, parent=${content.parentId.take(8)}, parents=${parents.map { it.take(8) }})"
}
