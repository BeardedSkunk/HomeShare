package de.beardedskunk.homeshare.data

import de.beardedskunk.homeshare.core.Hlc
import de.beardedskunk.homeshare.core.NodeKind
import de.beardedskunk.homeshare.core.NodeType
import de.beardedskunk.homeshare.core.OrderKeys

/**
 * Geschwister-Sortierung: manueller orderKey (bzw. virtueller HLC-Seed für ungesetzte Keys,
 * siehe [OrderKeys]), dann Erzeugungs-HLC, dann nodeId als deterministischer Tie-Breaker.
 */
val siblingOrder: Comparator<NodeState> =
    compareBy({ OrderKeys.effective(it.orderKey, it.created) }, { it.created }, { it.nodeId })

/**
 * Fortschritt (erledigt, gesamt) über die **Unterpunkte** eines Knotens (TODO/NOTE-Kinder), sonst
 * null. Deckt sich mit dem `subItems`-Filter der Aufgaben-Ansicht (TodoDetailScreen): Bild/Datei-
 * Anhänge und Markdown-Checkboxen im Body zählen NICHT. Basis für den „x/y"-Badge auf Aufgaben und
 * Aufgaben-Listen in der Listen-Ansicht.
 */
fun childTaskCounts(children: List<NodeState>): Pair<Int, Int>? {
    val subs = children.filter { it.kind == NodeKind.TODO || it.kind == NodeKind.NOTE }
    return if (subs.isEmpty()) null else subs.count { it.done } to subs.size
}

/**
 * Materialisierter aktueller Stand EINES Knotens (aus dem Op-Log abgeleitet, Lese-Modell für die UI).
 * Eine „Liste" ist ein TEXT-Knoten **mit** [childDefault] (navigierbar), eine „Notiz" ein TEXT-Knoten
 * **ohne** childDefault (Text-Editor). [conflicted] = mehrere inhaltlich verschiedene Heads.
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
    val childDefault: NodeKind? = null,
    val tags: List<String> = emptyList(),
    val deleted: Boolean = false,
    val conflicted: Boolean = false,
    val created: Hlc,
    val updated: Hlc,
    /** Fremdfeed-Kontext (nur bei abonnierten Cross-Group-Wurzeln gesetzt). */
    val foreignOrigin: String = "",
    val foreignRight: FeedRight = FeedRight.READ,
    val ext: Map<String, String> = emptyMap(),
) {
    /** Erste Zeile = Titel (für TEXT/CALENDAR/TODO). */
    val title: String get() = text.lineSequence().firstOrNull().orEmpty()

    val isImage: Boolean get() = type == NodeType.IMAGE
    val isForeign: Boolean get() = foreignOrigin.isNotEmpty()

    /** Eine Liste = navigierbarer Container (TEXT mit Default-Kindtyp). */
    val isList: Boolean get() = type == NodeType.TEXT && childDefault != null

    /** Nutzerseitiger Typ (für Icon/Verhalten). LIST/NOTE sind beide TEXT, unterschieden über [childDefault]. */
    val kind: NodeKind
        get() = when (type) {
            NodeType.CALENDAR -> NodeKind.CALENDAR
            NodeType.TODO -> NodeKind.TODO
            NodeType.IMAGE -> NodeKind.IMAGE
            NodeType.FILE -> NodeKind.FILE
            NodeType.TEXT -> if (childDefault != null) NodeKind.LIST else NodeKind.NOTE
        }

    /** Eine Liste, deren neue Kinder per Default Kalender-Einträge sind. */
    val isCalendarFeed: Boolean get() = childDefault == NodeKind.CALENDAR
}

/** Lokaler Datensatz eines abonnierten Fremd-Knotens (Cross-Group, auf dem Fremdgerät). */
data class ForeignFeedRef(
    val nodeId: String,
    val originGroup: String,
    val capId: String,
    val capSecret: String,
    val right: FeedRight,
)
