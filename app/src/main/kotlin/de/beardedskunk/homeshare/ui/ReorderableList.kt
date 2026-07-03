package de.beardedskunk.homeshare.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Handgerollter Drag&Drop-Zustand für vertikale LazyColumns (keine Fremd-Dependency).
 * Der Drag startet NUR am Handle ([DragHandle], die „Punkte-Doppelreihe“), nicht per
 * Long-Press – der gehört dem Aktionsmenü. Während des Zugs pflegt der Aufrufer über
 * [onMove] eine lokale Vorschau-Reihenfolge; erst beim Loslassen schreibt [onDrop] die
 * neue Position (1 Op, siehe FeedRepository.reorderNode).
 */
class DragDropState internal constructor(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val onMove: (from: Int, to: Int) -> Unit,
    private val onDrop: (from: Int, to: Int) -> Unit,
) {
    /** Aktueller LazyColumn-Index des gezogenen Items, -1 = kein Drag. */
    var draggingIndex by mutableIntStateOf(-1)
        private set

    /** Visueller Versatz des gezogenen Items relativ zu seinem aktuellen Slot. */
    var draggingOffset by mutableFloatStateOf(0f)
        private set

    private var startIndex = -1

    fun isDragging(index: Int) = index == draggingIndex
    val isDragging: Boolean get() = draggingIndex >= 0

    fun onDragStart(index: Int) {
        draggingIndex = index
        startIndex = index
        draggingOffset = 0f
    }

    fun onDrag(deltaY: Float) {
        if (draggingIndex < 0) return
        draggingOffset += deltaY
        val items = listState.layoutInfo.visibleItemsInfo
        val dragged = items.firstOrNull { it.index == draggingIndex } ?: return
        val center = dragged.offset + draggingOffset + dragged.size / 2f
        val target = items.firstOrNull { it.index != draggingIndex && center >= it.offset && center < it.offset + it.size }
        if (target != null) {
            // Vorschau-Verschiebung: Item rückt in den Ziel-Slot; Offset korrigieren, damit es
            // optisch unter dem Finger bleibt.
            onMove(draggingIndex, target.index)
            draggingOffset += dragged.offset - target.offset
            draggingIndex = target.index
        }
        // Am Rand nachscrollen, damit man über den Sichtbereich hinaus ziehen kann.
        val nudge = when {
            center < listState.layoutInfo.viewportStartOffset + 140 -> -48f
            center > listState.layoutInfo.viewportEndOffset - 140 -> 48f
            else -> 0f
        }
        if (nudge != 0f) scope.launch { listState.scrollBy(nudge) }
    }

    fun onDragEnd() {
        if (draggingIndex >= 0 && draggingIndex != startIndex) onDrop(startIndex, draggingIndex)
        reset()
    }

    fun onDragCancel() = reset()

    private fun reset() {
        draggingIndex = -1
        startIndex = -1
        draggingOffset = 0f
    }
}

@Composable
fun rememberDragDropState(
    listState: LazyListState,
    onMove: (from: Int, to: Int) -> Unit,
    onDrop: (from: Int, to: Int) -> Unit,
): DragDropState {
    val scope = rememberCoroutineScope()
    // rememberUpdatedState: der gemerkte State darf nie veraltete Callbacks festhalten
    // (die Lambdas schließen über sich ändernden Screen-State wie die Vorschau-Liste).
    val moveCb = rememberUpdatedState(onMove)
    val dropCb = rememberUpdatedState(onDrop)
    return remember(listState) {
        DragDropState(listState, scope, { f, t -> moveCb.value(f, t) }, { f, t -> dropCb.value(f, t) })
    }
}

/** Auf das ITEM (die ganze Zeile) legen: hebt das gezogene Item an und verschiebt es visuell. */
fun Modifier.dragDropItem(state: DragDropState, index: Int): Modifier =
    if (state.isDragging(index)) {
        this.zIndex(1f).graphicsLayer { translationY = state.draggingOffset }
    } else {
        this
    }

/** Die „Punkte-Doppelreihe“ am Zeilenende: nur hier startet der Drag (sofortig, ohne Long-Press). */
@Composable
fun DragHandle(state: DragDropState, index: Int, title: String) {
    // Nur auf [state] keyen: verschiebt die Vorschau das Item (neuer Index), würde ein
    // Restart des pointerInput die laufende Geste abbrechen.
    val curIndex by rememberUpdatedState(index)
    Icon(
        Icons.Filled.DragIndicator,
        contentDescription = "Verschieben",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .tag(dragTag(title))
            .pointerInput(state) {
                detectDragGestures(
                    onDragStart = { state.onDragStart(curIndex) },
                    onDrag = { change, amount -> change.consume(); state.onDrag(amount.y) },
                    onDragEnd = { state.onDragEnd() },
                    onDragCancel = { state.onDragCancel() },
                )
            },
    )
}
