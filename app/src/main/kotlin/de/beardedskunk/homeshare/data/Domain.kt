package de.beardedskunk.homeshare.data

import de.beardedskunk.homeshare.core.Hlc
import de.beardedskunk.homeshare.core.NodeType

/**
 * Materialisierter aktueller Stand EINES Knotens (aus dem Op-Log abgeleitet). Ersetzt die früheren
 * `Feed` und `PostState`: ein Feed ist ein Root-Knoten (parentId==ROOT, type==TEXT), ein „Eintrag"
 * ein Kindknoten usw. [conflicted] = mehrere inhaltlich verschiedene Heads (UI bietet Auflösung an).
 */
data class NodeState(
    val nodeId: String,
    val parentId: String,
    val rootId: String,
    val type: NodeType,
    val headVersionId: String,
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
    val conflicted: Boolean = false,
    val created: Hlc,
    val updated: Hlc,
    /** Fremdfeed-Kontext (nur bei abonnierten Cross-Group-Wurzeln gesetzt). */
    val foreignOrigin: String = "",
    val foreignRight: FeedRight = FeedRight.READ,
) {
    /** Erste Zeile = Titel (für TEXT/CALENDAR/TODO). */
    val title: String get() = text.lineSequence().firstOrNull().orEmpty()

    val isImage: Boolean get() = type == NodeType.IMAGE
    val isForeign: Boolean get() = foreignOrigin.isNotEmpty()

    /** UI-Modus eines „Feeds": welcher Kindtyp ist gemeint (z. B. CALENDAR für Kalender-Feed). */
    val isCalendarFeed: Boolean get() = childDefault == NodeType.CALENDAR
}

/** Lokaler Datensatz eines abonnierten Fremd-Knotens (Cross-Group, auf dem Fremdgerät). */
data class ForeignFeedRef(
    val nodeId: String,
    val originGroup: String,
    val capId: String,
    val capSecret: String,
    val right: FeedRight,
)
