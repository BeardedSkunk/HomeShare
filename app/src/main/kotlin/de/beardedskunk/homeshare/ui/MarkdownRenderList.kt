package de.beardedskunk.homeshare.ui

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.zIndex

/** Drag-Zustand für Markdown-Listen-Zeilen (ein gezogenes Item + Y-Offset). */
class MdLineDragState {
    var index by mutableIntStateOf(-1)
    var offset by mutableFloatStateOf(0f)
}

@Composable
fun rememberMdLineDragState(): MdLineDragState = remember { MdLineDragState() }

/**
 * Gerenderte Markdown-Blöcke als einzelne LazyColumn-Items: Haken antippbar, Listen-Zeilen
 * per Handle verschiebbar (Mittellinien-Regel), Links-Swipe -> Tonne. Extrahiert aus der
 * Notiz-Render-Ansicht; auch von der Aufgaben-Ansicht genutzt.
 * [firstItemIndex] = absoluter Index des ersten Block-Items in der umgebenden LazyColumn
 * (die Blöcke müssen dort zusammenhängend liegen).
 * [currentRangeFor] erhält den absoluten LazyColumn-Item-Index und gibt ggf. den
 * aktuellen Such-Treffer-Bereich zurück (für Treffer-Highlight).
 */
fun LazyListScope.markdownBlockItems(
    blocks: List<MdBlock>,
    listState: LazyListState,
    drag: MdLineDragState,
    bodyStyle: TextStyle,
    firstItemIndex: Int,
    onToggleTask: (Int) -> Unit,
    onEditAt: (Int) -> Unit,
    onMoveLine: ((from: Int, to: Int) -> Unit)?,
    onDeleteLine: ((Int) -> Unit)?,
    openTrashKey: String?,
    onOpenTrash: ((String?) -> Unit)?,
    highlight: String?,
    currentRangeFor: (absItemIndex: Int) -> IntRange?,
) {
    fun dragLine(b: MdBlock): Int = when (b) {
        is MdBlock.Task -> b.sourceLine
        is MdBlock.Bullet -> b.sourceLine
        is MdBlock.Numbered -> b.sourceLine
        else -> -1
    }

    fun endLineDrag(idx: Int) {
        if (onMoveLine != null) {
            val itemIdx = firstItemIndex + idx
            val items = listState.layoutInfo.visibleItemsInfo
            val dragged = items.firstOrNull { it.index == itemIdx }
            if (dragged != null) {
                val center = dragged.offset + drag.offset + dragged.size / 2f
                // Mittellinien-Regel: umsortiert wird um so viele Positionen, wie das Zentrum
                // des gezogenen Items Nachbar-ZENTREN überquert hat.
                var target = idx
                for (it2 in items) {
                    if (it2.index == itemIdx) continue
                    val c2 = it2.offset + it2.size / 2f
                    if (it2.index > itemIdx && center > c2) target++
                    if (it2.index < itemIdx && center < c2) target--
                }
                var lo = idx
                while (lo - 1 >= 0 && dragLine(blocks[lo - 1]) > 0) lo--
                var hi = idx
                while (hi + 1 < blocks.size && dragLine(blocks[hi + 1]) > 0) hi++
                target = target.coerceIn(lo, hi)
                if (target != idx) onMoveLine(dragLine(blocks[idx]), dragLine(blocks[target]))
            }
        }
        drag.index = -1
        drag.offset = 0f
    }

    itemsIndexed(blocks) { idx, b ->
        val line = dragLine(b)
        if (onMoveLine != null && line > 0) {
            val row: @Composable () -> Unit = {
                Row(
                    Modifier.fillMaxWidth()
                        .then(if (idx == drag.index) Modifier.zIndex(1f).graphicsLayer { translationY = drag.offset } else Modifier),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        MdBlockView(b, bodyStyle, onToggleTask, onEditAt, highlight = highlight, currentRange = currentRangeFor(firstItemIndex + idx))
                    }
                    Icon(
                        Icons.Filled.DragIndicator,
                        contentDescription = "Zeile verschieben",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.tag("drag:line:$line").pointerInput(idx, blocks) {
                            detectVerticalDragGestures(
                                onDragStart = { onOpenTrash?.invoke(null); drag.index = idx; drag.offset = 0f },
                                onVerticalDrag = { change, amount -> change.consume(); drag.offset += amount },
                                onDragEnd = { endLineDrag(idx) },
                                onDragCancel = { drag.index = -1; drag.offset = 0f },
                            )
                        },
                    )
                }
            }
            if (onDeleteLine != null && onOpenTrash != null) {
                SwipeRevealRow(
                    key = "line:$line", openKey = openTrashKey, onOpenChange = onOpenTrash,
                    onDelete = { onDeleteLine(line) },
                ) { row() }
            } else {
                row()
            }
        } else {
            MdBlockView(b, bodyStyle, onToggleTask, onEditAt, highlight = highlight, currentRange = currentRangeFor(firstItemIndex + idx))
        }
    }
}
