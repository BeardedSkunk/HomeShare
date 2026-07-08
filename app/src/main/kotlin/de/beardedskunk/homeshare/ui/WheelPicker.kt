package de.beardedskunk.homeshare.ui

import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.flow.distinctUntilChanged

enum class WheelOrientation { VERTICAL, HORIZONTAL }

/**
 * Ermittelt den zentrierten Index (Item dessen Mitte der Viewport-Mitte am nächsten liegt) — die
 * gemeinsame Definition von "aktuell ausgewählt" für Styling UND Settle-Erkennung, damit beide nie
 * auseinanderlaufen (das war Kern des Flacker-Bugs: Settle-Erkennung nutzte stattdessen
 * `firstVisibleItemIndex`, was bei Wisch-Momentum kurzzeitig einen anderen Index als das optisch
 * zentrierte Item lieferte -> Korrektur-Scroll -> erneutes Settle-Event -> Endlos-Ping-Pong).
 */
@Composable
private fun rememberCenterIndex(listState: androidx.compose.foundation.lazy.LazyListState) = remember {
    derivedStateOf {
        val info = listState.layoutInfo
        if (info.visibleItemsInfo.isEmpty()) return@derivedStateOf 0
        val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
        info.visibleItemsInfo.minByOrNull { abs((it.offset + it.size / 2) - viewportCenter) }?.index ?: 0
    }
}

/**
 * Scrollbarer Auswahl-Picker wie Androids klassischer NumberPicker: 3 gleich breite Slots, der
 * mittlere hervorgehoben, Nachbarn abgedunkelt; Wischen/Fling snapped auf den nächsten Wert.
 * Kein Popup — direkt inline einsetzbar (Container-Größe = 3 * [itemExtent] in Scrollrichtung).
 * [itemContent] bekommt den Abstand zum zentrierten Slot (0 = Mitte) für eigenes Styling.
 */
@Composable
fun <T> WheelPicker(
    items: List<T>,
    selected: T,
    onChange: (T) -> Unit,
    orientation: WheelOrientation = WheelOrientation.VERTICAL,
    itemExtent: Dp = 32.dp,
    modifier: Modifier = Modifier,
    itemContent: @Composable (T, Int) -> Unit,
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = remember { items.indexOf(selected).coerceAtLeast(0) },
    )
    val flingBehavior = rememberSnapFlingBehavior(remember(listState) { SnapLayoutInfoProvider(listState) })
    val centerIndex = rememberCenterIndex(listState)
    var lastEmitted by remember { mutableStateOf(selected) }

    LaunchedEffect(selected) {
        if (selected == lastEmitted) return@LaunchedEffect
        lastEmitted = selected
        val idx = items.indexOf(selected).coerceAtLeast(0)
        if (listState.firstVisibleItemIndex != idx || listState.firstVisibleItemScrollOffset != 0) {
            listState.scrollToItem(idx)
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (!scrolling) {
                    val settled = items.getOrNull(centerIndex.value) ?: return@collect
                    if (settled != selected) {
                        lastEmitted = settled
                        onChange(settled)
                    }
                }
            }
    }

    val divColor = MaterialTheme.colorScheme.outlineVariant
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        // Slots dürfen nie breiter/höher sein als 1/3 des tatsächlich verfügbaren Platzes — sonst
        // sprengt ein langes Label (z. B. "nach Erledigung") den Container über den Bildschirmrand
        // hinaus und Trennlinien/Zentrierung stimmen nicht mehr mit dem sichtbaren Ausschnitt überein.
        val effectiveExtent = if (orientation == WheelOrientation.HORIZONTAL) {
            minOf(itemExtent, maxWidth / 3)
        } else {
            minOf(itemExtent, maxHeight / 3)
        }
        val containerSize = effectiveExtent * 3
        Box(
            if (orientation == WheelOrientation.VERTICAL) {
                Modifier.height(containerSize).width(56.dp)
            } else {
                Modifier.width(containerSize).height(40.dp)
            },
            contentAlignment = Alignment.Center,
        ) {
            when (orientation) {
                WheelOrientation.VERTICAL -> LazyColumn(
                    state = listState,
                    flingBehavior = flingBehavior,
                    contentPadding = PaddingValues(vertical = effectiveExtent),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    items(items) { it2 ->
                        Box(Modifier.height(effectiveExtent).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            itemContent(it2, abs(items.indexOf(it2) - centerIndex.value))
                        }
                    }
                }
                WheelOrientation.HORIZONTAL -> LazyRow(
                    state = listState,
                    flingBehavior = flingBehavior,
                    contentPadding = PaddingValues(horizontal = effectiveExtent),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(items) { it2 ->
                        Box(Modifier.width(effectiveExtent).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            itemContent(it2, abs(items.indexOf(it2) - centerIndex.value))
                        }
                    }
                }
            }
            if (orientation == WheelOrientation.VERTICAL) {
                HorizontalDivider(Modifier.align(Alignment.TopCenter).offset(y = effectiveExtent), color = divColor)
                HorizontalDivider(Modifier.align(Alignment.BottomCenter).offset(y = -effectiveExtent), color = divColor)
            } else {
                VerticalDivider(Modifier.align(Alignment.CenterStart).offset(x = effectiveExtent), color = divColor)
                VerticalDivider(Modifier.align(Alignment.CenterEnd).offset(x = -effectiveExtent), color = divColor)
            }
        }
    }
}

/**
 * Zahlen-Variante von [WheelPicker] (3-Slot-Optik mit Trennlinien) für einen begrenzten
 * Ganzzahl-Bereich (Standard 1..99).
 */
@Composable
fun WheelNumberPicker(
    value: Int,
    onChange: (Int) -> Unit,
    range: IntRange = 1..99,
    orientation: WheelOrientation = WheelOrientation.VERTICAL,
    itemExtent: Dp = 32.dp,
    modifier: Modifier = Modifier,
) {
    val values = remember(range) { range.toList() }
    WheelPicker(values, value, onChange, orientation, itemExtent, modifier) { n, distance ->
        val alpha = when (distance) { 0 -> 1f; 1 -> 0.5f; else -> 0.25f }
        val scale = if (distance == 0) 1f else 0.85f
        Text(
            "$n",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.graphicsLayer { this.alpha = alpha; scaleX = scale; scaleY = scale },
        )
    }
}

/**
 * Horizontaler Text-Picker auf Pager-Basis (seitenweises Snapping): der gewählte Text sitzt
 * zentriert zwischen zwei festen Trennstrichen, die Nachbarn lugen abgedunkelt an den Rändern
 * herein — der linke rechtsbündig (sein Text-ENDE liegt am linken Strich), der rechte
 * linksbündig. Die Ausrichtung wechselt erst nach dem Settle, damit beim Wischen nichts springt.
 */
@Composable
fun <T> PagerTextPicker(
    items: List<T>,
    selected: T,
    onChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: (T) -> String,
) {
    val pagerState = rememberPagerState(
        initialPage = remember { items.indexOf(selected).coerceAtLeast(0) },
    ) { items.size }
    var lastEmitted by remember { mutableStateOf(selected) }

    LaunchedEffect(selected) {
        if (selected == lastEmitted) return@LaunchedEffect
        lastEmitted = selected
        pagerState.scrollToPage(items.indexOf(selected).coerceAtLeast(0))
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val settled = items.getOrNull(page) ?: return@collect
            if (settled != lastEmitted) {
                lastEmitted = settled
                onChange(settled)
            }
        }
    }

    val divColor = MaterialTheme.colorScheme.outlineVariant
    BoxWithConstraints(modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
        val sidePad = maxWidth / 5
        HorizontalPager(
            pagerState,
            contentPadding = PaddingValues(horizontal = sidePad),
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val distance = abs(page - pagerState.currentPage)
            val align = when {
                page < pagerState.settledPage -> Alignment.CenterEnd
                page > pagerState.settledPage -> Alignment.CenterStart
                else -> Alignment.Center
            }
            Box(Modifier.fillMaxSize().padding(horizontal = 6.dp), contentAlignment = align) {
                Text(
                    label(items[page]),
                    maxLines = 1,
                    softWrap = false,
                    style = if (distance == 0) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (distance == 0) 1f else 0.45f),
                )
            }
        }
        VerticalDivider(Modifier.align(Alignment.CenterStart).offset(x = sidePad), color = divColor)
        VerticalDivider(Modifier.align(Alignment.CenterEnd).offset(x = -sidePad), color = divColor)
    }
}
