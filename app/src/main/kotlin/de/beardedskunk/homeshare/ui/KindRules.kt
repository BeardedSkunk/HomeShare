package de.beardedskunk.homeshare.ui

import de.beardedskunk.homeshare.core.NodeKind

/**
 * Welche Knotentypen die UI unter welchem Elternknoten anlegen lässt. Das Backend erlaubt
 * mehr Kombinationen (z. B. beliebige Ketten unter Anhängen) – die UI bietet bewusst nur
 * Sinnvolles an und zeigt anderes auch nicht zum Anlegen an.
 */
object KindRules {
    /** Anlegbare Kind-Typen; parentKind = null = Wurzelebene (Feeds): dort nur Listen. */
    fun allowedChildKinds(parentKind: NodeKind?): List<NodeKind> = when (parentKind) {
        null -> listOf(NodeKind.LIST)
        NodeKind.LIST, NodeKind.NOTE ->
            listOf(NodeKind.LIST, NodeKind.NOTE, NodeKind.CALENDAR, NodeKind.TODO, NodeKind.IMAGE, NodeKind.FILE)
        // Todos bleiben flach strukturiert: Subtasks/Notizen/Termine/Anhänge, aber keine Listen.
        NodeKind.TODO -> listOf(NodeKind.TODO, NodeKind.NOTE, NodeKind.CALENDAR, NodeKind.IMAGE, NodeKind.FILE)
        // Unter Anhängen und Terminen legt die UI nichts an (Anhang-Beschreibung macht der Editor).
        NodeKind.CALENDAR, NodeKind.IMAGE, NodeKind.FILE -> emptyList()
    }
}
