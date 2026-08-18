package de.beardedskunk.homeshare.core

/**
 * Kern-Datenmodell: ein **Knoten-Baum**. Alles (Liste, Notiz, Bild, Datei, Termin, Aufgabe) ist ein
 * [NodeVersion]-Knoten im selben versionierten DAG. Eine „Liste" = TEXT-Knoten **mit** [NodeContent.childDefault]
 * (navigierbar); eine „Notiz" = TEXT-Knoten **ohne** childDefault (Text-Editor). Bilder/Dateien =
 * Kindknoten, deren Beschreibung wiederum ein TEXT-Kindknoten ist.
 *
 * Jeder Knoten hat eine git-artige Historie aus Versionen (DAG). Nebenläufige Bearbeitungen erzeugen
 * mehrere Heads -> Konflikt (siehe [Node]). Bewusst Android-frei für JVM-Unit-Tests.
 *
 * **Erweiterbares Meta-System:** Optionale Felder reisen als sortierte, nur-gesetzte Klartext-Map
 * ([NodeContent.metaMap]) in der Kanonik. Unbekannte/zukünftige Keys landen in [NodeContent.ext] und
 * werden wortwörtlich erhalten (Hash, Wire, Edit) -> neue Meta-Keys brechen alte Apps NICHT.
 * [FORMAT_VERSION] im Kanon ist der bewusste Hebel für echte (Kern-)Brüche: Ops mit höherem `fmt`
 * werden von älteren Apps gespeichert+weitergereicht, aber nicht interpretiert.
 */

/** Sentinel-Eltern-ID für Knoten auf oberster Ebene. */
const val ROOT = "ROOT"

/**
 * Kanonik-/Kompatibilitäts-Version (fließt in jede versionId). Erhöhen NUR bei echten Brüchen der
 * Kern-Semantik; additive Meta-Keys brauchen das NICHT. Transport (Kanal, VV, Op-Umschlag, HSHELLO)
 * bleibt eingefroren.
 */
const val FORMAT_VERSION = 1

/** Struktureller Knotentyp (Speicher-/Payload-Form). Die UI-Sicht ist [NodeKind]. */
enum class NodeType { TEXT, CALENDAR, IMAGE, FILE, TODO }

/** Nutzerseitiger Typ. LIST und NOTE sind beide TEXT; unterschieden über [NodeContent.childDefault]. */
enum class NodeKind { LIST, NOTE, CALENDAR, TODO, IMAGE, FILE }

/** Stabile Klartext-Schlüssel der bekannten Meta-Felder. */
object MetaKey {
    const val CHILD_DEFAULT = "childDefault"
    const val COLOR = "color"
    const val TAGS = "tags"
    const val DONE = "done"
    const val BLOB = "blob"
    const val FILE = "file"
    const val MIME = "mime"

    /** Spawn-Sperre wiederholender Aufgaben (siehe data/TaskRepeat.KEY_SPAWNED). Bewusst NICHT
     *  in [KNOWN] (kein typisiertes Feld, bleibt in ext) — hier nur, damit der Automerge
     *  (Node.autoMergeContent) den Key per Last-Writer-Wins auflösen kann. */
    const val REPEAT_SPAWNED = "repeatSpawned"

    val KNOWN = setOf(CHILD_DEFAULT, COLOR, TAGS, DONE, BLOB, FILE, MIME)
}

/** Hybrid Logical Clock: stabile Reihenfolge trotz unzuverlaessiger Geraeteuhren. */
data class Hlc(val wallMillis: Long, val counter: Int) : Comparable<Hlc> {
    override fun compareTo(other: Hlc): Int {
        val w = wallMillis.compareTo(other.wallMillis)
        return if (w != 0) w else counter.compareTo(other.counter)
    }

    companion object {
        fun next(now: Long, last: Hlc?): Hlc = when {
            last == null -> Hlc(now, 0)
            now > last.wallMillis -> Hlc(now, 0)
            else -> Hlc(last.wallMillis, last.counter + 1)
        }
    }
}

/**
 * Meta-Map <-> Einzelstring für DB-Spalte und Wire (reversibel, längen-präfixiert). Unabhängig von der
 * Kanonik: für die versionId zählt nur die Meta-*Map* (siehe [NodeVersion.canonical]), nicht dieses Format.
 */
object MetaCodec {
    fun encode(meta: Map<String, String>): String = buildString {
        val sorted = meta.toSortedMap()
        append(sorted.size).append('\n')
        for ((k, v) in sorted) {
            append(k.length).append(':').append(k).append('=').append(v.length).append(':').append(v).append('\n')
        }
    }

    fun decode(s: String): Map<String, String> {
        if (s.isBlank()) return emptyMap()
        val nl = s.indexOf('\n')
        val count = (if (nl < 0) s else s.substring(0, nl)).trim().toIntOrNull() ?: return emptyMap()
        if (count == 0) return emptyMap()
        val out = LinkedHashMap<String, String>(count)
        var i = nl + 1
        repeat(count) {
            val kColon = s.indexOf(':', i); if (kColon < 0) return out
            val kLen = s.substring(i, kColon).toIntOrNull() ?: return out
            val kStart = kColon + 1; val kEnd = kStart + kLen
            if (kEnd + 1 > s.length || s[kEnd] != '=') return out
            val key = s.substring(kStart, kEnd)
            var j = kEnd + 1
            val vColon = s.indexOf(':', j); if (vColon < 0) return out
            val vLen = s.substring(j, vColon).toIntOrNull() ?: return out
            val vStart = vColon + 1; val vEnd = vStart + vLen
            if (vEnd > s.length) return out
            out[key] = s.substring(vStart, vEnd)
            i = vEnd + 1 // ueber das '\n'
        }
        return out
    }
}

/** Listen <-> Einzelstring (count + längen-präfixierte Elemente) für einen Meta-Wert (z. B. Tags). */
object MetaListCodec {
    fun encode(list: List<String>): String = buildString {
        append(list.size).append(';')
        for (s in list) append(s.length).append(':').append(s)
    }

    fun decode(s: String): List<String> {
        if (s.isBlank()) return emptyList()
        val sep = s.indexOf(';')
        if (sep < 0) return emptyList()
        val count = s.substring(0, sep).toIntOrNull() ?: return emptyList()
        val out = ArrayList<String>(count)
        var i = sep + 1
        repeat(count) {
            val colon = s.indexOf(':', i)
            if (colon < 0) return out
            val len = s.substring(i, colon).toIntOrNull() ?: return out
            val start = colon + 1
            if (start + len > s.length) return out
            out.add(s.substring(start, start + len))
            i = start + len
        }
        return out
    }
}

/**
 * Inhalt einer Knoten-Version (fließt vollständig in die [NodeVersion.versionId]).
 *
 * - Kern: [parentId]/[orderKey] (Baumposition), [type], [text], [deleted].
 * - Meta (optional, erweiterbar): [childDefault] (UI-Typ neuer Kinder einer Liste), [color], [tags],
 *   [done] (nur sinnvoll an TODO), [blobHash]/[fileName]/[mime] (IMAGE/FILE-Payload).
 * - [ext]: unbekannte/zukünftige Meta-Keys, wortwörtlich erhalten.
 */
data class NodeContent(
    val parentId: String = ROOT,
    val type: NodeType = NodeType.TEXT,
    val orderKey: String = "",
    val text: String = "",
    val deleted: Boolean = false,
    val childDefault: NodeKind? = null,
    val color: Int? = null,
    val tags: List<String> = emptyList(),
    val done: Boolean = false,
    val blobHash: String? = null,
    val fileName: String? = null,
    val mime: String? = null,
    val ext: Map<String, String> = emptyMap(),
) {
    /** Sortierte Klartext-Meta-Map (nur gesetzte Werte) — Grundlage für Kanonik, Wire und DB. */
    fun metaMap(): Map<String, String> {
        val m = sortedMapOf<String, String>()
        childDefault?.let { m[MetaKey.CHILD_DEFAULT] = it.name }
        color?.let { m[MetaKey.COLOR] = it.toString() }
        if (tags.isNotEmpty()) m[MetaKey.TAGS] = MetaListCodec.encode(tags)
        if (done) m[MetaKey.DONE] = "1"
        blobHash?.let { m[MetaKey.BLOB] = it }
        fileName?.let { m[MetaKey.FILE] = it }
        mime?.let { m[MetaKey.MIME] = it }
        for ((k, v) in ext) if (k !in MetaKey.KNOWN) m[k] = v
        return m
    }

    companion object {
        /** Baut den Inhalt aus Kernfeldern + offener Meta-Map: bekannte Keys typisiert, Rest -> [ext]. */
        fun fromMeta(
            parentId: String,
            type: NodeType,
            orderKey: String,
            text: String,
            deleted: Boolean,
            meta: Map<String, String>,
        ): NodeContent {
            val ext = meta.filterKeys { it !in MetaKey.KNOWN }
            return NodeContent(
                parentId = parentId,
                type = type,
                orderKey = orderKey,
                text = text,
                deleted = deleted,
                childDefault = meta[MetaKey.CHILD_DEFAULT]?.let { runCatching { NodeKind.valueOf(it) }.getOrNull() },
                color = meta[MetaKey.COLOR]?.toIntOrNull(),
                tags = meta[MetaKey.TAGS]?.let { MetaListCodec.decode(it) } ?: emptyList(),
                done = meta[MetaKey.DONE] == "1",
                blobHash = meta[MetaKey.BLOB],
                fileName = meta[MetaKey.FILE],
                mime = meta[MetaKey.MIME],
                ext = ext,
            )
        }
    }
}

/**
 * Ein unveraenderlicher Versionsknoten. [versionId] ist der SHA-256 ueber den kanonisch kodierten
 * Inhalt inkl. DAG-Eltern und [FORMAT_VERSION] -> inhaltsadressiert wie ein git-Commit. Der Meta-Teil
 * ist eine sortierte, nur-gesetzte Klartext-Map: neue Keys verändern nur Knoten, die sie setzen.
 */
class NodeVersion(
    val nodeId: String,
    val parents: Set<String>,
    val deviceId: String,
    val hlc: Hlc,
    val content: NodeContent,
    val formatVersion: Int = FORMAT_VERSION,
) {
    val versionId: String = Hashing.sha256Hex(canonical())

    private fun canonical(): String = buildString {
        append("fmt:").append(formatVersion).append('\n')
        append("node:").append(nodeId).append('\n')
        append("parents:")
        parents.sorted().forEach { append(it).append(',') }
        append('\n')
        append("device:").append(deviceId).append('\n')
        append("hlc:").append(hlc.wallMillis).append('-').append(hlc.counter).append('\n')
        append("type:").append(content.type.name).append('\n')
        append("parent:").append(content.parentId).append('\n')
        append("order:").append(content.orderKey.length).append(':').append(content.orderKey).append('\n')
        append("deleted:").append(content.deleted).append('\n')
        // Offene Meta-Map: sortierte, längen-präfixierte key=value-Paare; nur gesetzte Werte.
        val meta = content.metaMap()
        append("meta:").append(meta.size).append('\n')
        for ((k, v) in meta) {
            append(k.length).append(':').append(k).append('=').append(v.length).append(':').append(v).append('\n')
        }
        // Text zuletzt, laengenpraefixiert.
        append("text:").append(content.text.length).append(':').append(content.text)
    }

    override fun equals(other: Any?): Boolean = other is NodeVersion && other.versionId == versionId
    override fun hashCode(): Int = versionId.hashCode()
    override fun toString(): String =
        "NodeVersion(${versionId.take(8)}, fmt=$formatVersion, ${content.type}, parent=${content.parentId.take(8)})"
}
