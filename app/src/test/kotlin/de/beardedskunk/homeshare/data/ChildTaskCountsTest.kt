package de.beardedskunk.homeshare.data

import de.beardedskunk.homeshare.core.Hlc
import de.beardedskunk.homeshare.core.NodeKind
import de.beardedskunk.homeshare.core.NodeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Zählt der Fortschritts-Badge genau die Unterpunkte (TODO/NOTE-Kinder)? Bilder/Dateien sowie
 * Listen/Termine dürfen NICHT zählen; Markdown-Checkboxen spielen hier ohnehin keine Rolle.
 */
class ChildTaskCountsTest {
    private var seq = 0
    private fun node(kind: NodeKind, done: Boolean = false): NodeState {
        val id = "n${seq++}"
        val type = when (kind) {
            NodeKind.TODO -> NodeType.TODO
            NodeKind.CALENDAR -> NodeType.CALENDAR
            NodeKind.IMAGE -> NodeType.IMAGE
            NodeKind.FILE -> NodeType.FILE
            NodeKind.LIST, NodeKind.NOTE -> NodeType.TEXT
        }
        return NodeState(
            nodeId = id, parentId = "p", rootId = "r", type = type,
            headVersionId = "v-$id", done = done,
            childDefault = if (kind == NodeKind.LIST) NodeKind.LIST else null,
            created = Hlc(seq.toLong(), 0), updated = Hlc(seq.toLong(), 0),
        )
    }

    @Test fun empty_isNull() {
        assertNull(childTaskCounts(emptyList()))
    }

    @Test fun onlyAttachments_isNull() {
        assertNull(childTaskCounts(listOf(node(NodeKind.IMAGE), node(NodeKind.FILE))))
    }

    @Test fun listsAndCalendarsDoNotCount() {
        assertNull(childTaskCounts(listOf(node(NodeKind.LIST), node(NodeKind.CALENDAR))))
    }

    @Test fun singleOpenTodo_isZeroOfOne() {
        assertEquals(0 to 1, childTaskCounts(listOf(node(NodeKind.TODO, done = false))))
    }

    @Test fun sixSubitemsNoneDone_isZeroOfSix() {
        // „Küche putzen": irgend eine notiz (NOTE), 4x TODO, eine Testnotiz (NOTE), + ein Bild (zählt nicht).
        val kids = listOf(
            node(NodeKind.NOTE),
            node(NodeKind.TODO),
            node(NodeKind.IMAGE),
            node(NodeKind.TODO),
            node(NodeKind.TODO),
            node(NodeKind.TODO),
            node(NodeKind.NOTE),
        )
        assertEquals(0 to 6, childTaskCounts(kids))
    }

    @Test fun partiallyDone_countsDone() {
        val kids = listOf(
            node(NodeKind.TODO, done = true),
            node(NodeKind.TODO, done = true),
            node(NodeKind.NOTE, done = false),
            node(NodeKind.TODO, done = false),
        )
        assertEquals(2 to 4, childTaskCounts(kids))
    }
}
