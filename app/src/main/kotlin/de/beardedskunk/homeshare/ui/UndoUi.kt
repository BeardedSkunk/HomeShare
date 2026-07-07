package de.beardedskunk.homeshare.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import de.beardedskunk.homeshare.data.UndoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Registriert [anchorId] als Undo-Anker, solange der Screen komponiert ist. GANZ OBEN im
 * Composable platzieren — VOR den modalen `if (…) { Editor(...); return }`-Zweigen, damit der
 * Listen-Anker aktiv bleibt und ein geöffneter Editor seinen eigenen oben drauflegt
 * (Stack: [Liste, Editor]; beim Schließen poppt sich der Editor über onDispose selbst).
 */
@Composable
fun RegisterUndoAnchor(undo: UndoManager, anchorId: String) {
    DisposableEffect(anchorId) {
        undo.pushAnchor(anchorId)
        onDispose { undo.popAnchor(anchorId) }
    }
}

/**
 * Undo/Redo-Paar unten links (Gegenstück zum Plus-FAB rechts): immer sichtbar, einzeln
 * ausgegraut wenn die Richtung leer ist. In die Wurzel-`Box` des Screens hängen
 * (Alignment.BottomStart übernimmt der interne Modifier des Aufrufers).
 */
@Composable
fun UndoRedoButtons(undo: UndoManager, anchorId: String, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val rev by undo.revision.collectAsState()
    // rev erzwingt die Neuauswertung nach jeder Ketten-Änderung (auch durch Sync-Invalidierung).
    val canUndo = remember(rev, anchorId) { undo.canUndo(anchorId) }
    val canRedo = remember(rev, anchorId) { undo.canRedo(anchorId) }
    Row(
        modifier.navigationBarsPadding().imePadding().padding(start = 16.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        UndoFab(
            icon = Icons.AutoMirrored.Filled.Undo, description = "Rückgängig", enabled = canUndo,
            tagName = "fab:undo",
            onClick = { scope.launch { withContext(Dispatchers.IO) { undo.undo(anchorId) } } },
        )
        UndoFab(
            icon = Icons.AutoMirrored.Filled.Redo, description = "Wiederherstellen", enabled = canRedo,
            tagName = "fab:redo",
            onClick = { scope.launch { withContext(Dispatchers.IO) { undo.redo(anchorId) } } },
        )
    }
}

/** Gleiche Bauart wie der Plus-FAB in [ListScreen] (eigene Surface statt FloatingActionButton). */
@Composable
private fun UndoFab(icon: ImageVector, description: String, enabled: Boolean, tagName: String, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.35f
    Surface(
        modifier = Modifier.size(56.dp).tag(tagName).clickable(enabled = enabled, onClick = onClick),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = alpha),
        shadowElevation = if (enabled) 6.dp else 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon, contentDescription = description,
                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = alpha),
            )
        }
    }
}
