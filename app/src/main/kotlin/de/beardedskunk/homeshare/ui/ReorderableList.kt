package de.beardedskunk.homeshare.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
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

// ---------------------------------------------------------------------------------------------
// Spalten-Drag: einfacher Drag&Drop für kurze, ~gleichhohe Zeilenlisten in Karten-Kästen
// (Todo-Unterpunkte, Anhänge). Landeplatz nach der Mittellinien-Regel (Truncation).
// ---------------------------------------------------------------------------------------------

class ColumnDragState {
    var index by mutableIntStateOf(-1)
    var offset by mutableFloatStateOf(0f)
    var rowHeight by mutableIntStateOf(0)
    fun reset() { index = -1; offset = 0f }
}

@Composable
fun rememberColumnDragState(): ColumnDragState = remember { ColumnDragState() }

/** Auf die ZEILE legen: misst die Zeilenhöhe und hebt die gezogene Zeile an. */
fun Modifier.columnDragItem(state: ColumnDragState, index: Int): Modifier =
    onGloballyPositioned { if (state.rowHeight == 0) state.rowHeight = it.size.height }
        .then(if (index == state.index) Modifier.zIndex(1f).graphicsLayer { translationY = state.offset } else Modifier)

/** Drag-Handle (Punkte-Doppelreihe) für den Spalten-Drag. [onDrop] liefert (from, to). */
@Composable
fun ColumnDragHandle(
    state: ColumnDragState,
    index: Int,
    title: String,
    itemCount: Int,
    onDragStart: () -> Unit = {},
    onDrop: (from: Int, to: Int) -> Unit,
) {
    val curIndex by rememberUpdatedState(index)
    val curCount by rememberUpdatedState(itemCount)
    val startCb by rememberUpdatedState(onDragStart)
    val dropCb by rememberUpdatedState(onDrop)
    Icon(
        Icons.Filled.DragIndicator,
        contentDescription = "Verschieben",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.tag(dragTag(title)).pointerInput(state) {
            detectDragGestures(
                onDragStart = { startCb(); state.index = curIndex; state.offset = 0f },
                onDrag = { change, amount -> change.consume(); state.offset += amount.y },
                onDragEnd = {
                    val h = state.rowHeight.takeIf { it > 0 } ?: 1
                    // Mittellinien-Regel: erst eine volle Zeilenhöhe = Nachbar-Mitte passiert -> umsortieren.
                    val target = (state.index + (state.offset / h).toInt()).coerceIn(0, curCount - 1)
                    val from = state.index
                    state.reset()
                    if (target != from && from >= 0) dropCb(from, target)
                },
                onDragCancel = { state.reset() },
            )
        },
    )
}

// ---------------------------------------------------------------------------------------------
// Links-Swipe -> stehende Mülltonne. Kein direktes Swipe-to-delete: Loslassen lässt die Tonne
// stehen, erst der Tap auf die Tonne löscht (sofort, ohne Rückfrage). Höchstens EINE Tonne
// offen ([openKey], vom Screen verwaltet); jeder Swipe/Drag an anderer Stelle schließt sie.
// ---------------------------------------------------------------------------------------------

@Composable
fun SwipeRevealRow(
    key: String,
    openKey: String?,
    onOpenChange: (String?) -> Unit,
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    val revealPx = with(LocalDensity.current) { 64.dp.toPx() }
    val open = openKey == key
    var dragX by remember { mutableFloatStateOf(0f) }
    val settled by animateFloatAsState(if (open) -revealPx else 0f, label = "swipeReveal")
    val shift = (settled + dragX).coerceIn(-revealPx, 0f)
    val openCb by rememberUpdatedState(onOpenChange)
    val deleteCb by rememberUpdatedState(onDelete)
    val curOpen by rememberUpdatedState(open)
    Box {
        if (shift < -1f) {
            Box(Modifier.matchParentSize().padding(end = 16.dp), contentAlignment = Alignment.CenterEnd) {
                IconButton(onClick = { openCb(null); deleteCb() }, modifier = Modifier.tag("trash:$key")) {
                    Icon(Icons.Filled.Delete, contentDescription = "Löschen", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        Box(
            Modifier
                .graphicsLayer { translationX = shift }
                .pointerInput(key) {
                    detectHorizontalDragGestures(
                        onDragStart = { if (!curOpen) openCb(null) }, // fremde Tonne schließen
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            dragX = (dragX + amount).coerceIn(-revealPx * 1.2f, revealPx)
                        },
                        onDragEnd = {
                            val pos = (if (curOpen) -revealPx else 0f) + dragX
                            openCb(if (pos < -revealPx / 2) key else null)
                            dragX = 0f
                        },
                        onDragCancel = { dragX = 0f },
                    )
                },
        ) { content() }
    }
}
