package de.beardedskunk.homeshare.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Dünne Tag-Zeile: fixes Plus links, rechts horizontal scrollende Chips.
 * Rendert NICHTS, wenn [tags] leer und [onAdd] null (readOnly ohne Tags).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TagRow(
    tags: List<String>,
    onAdd: (() -> Unit)?,
    onRemove: ((String) -> Unit)?,
    onSearchTag: ((String) -> Unit)?,
) {
    if (tags.isEmpty() && onAdd == null) return

    var longPressTag by remember { mutableStateOf<String?>(null) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onAdd != null) {
            IconButton(
                onClick = onAdd,
                modifier = Modifier
                    .heightIn(max = 32.dp)
                    .tag("tags:add"),
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "Tag hinzufügen",
                    modifier = Modifier.padding(2.dp),
                )
            }
        } else {
            Spacer(modifier = Modifier.width(8.dp))
        }

        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(tags) { tag ->
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = { longPressTag = tag },
                    ),
                ) {
                    Text(
                        text = tag,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }

    longPressTag?.let { tag ->
        AlertDialog(
            onDismissRequest = { longPressTag = null },
            title = { Text(tag) },
            text = {
                Column {
                    if (onSearchTag != null) {
                        TextButton(
                            onClick = { longPressTag = null; onSearchTag(tag) },
                            modifier = Modifier.tag("action:tag-search"),
                        ) { Text("Nach diesem Tag suchen") }
                    }
                    if (onRemove != null) {
                        TextButton(
                            onClick = { longPressTag = null; onRemove(tag) },
                            modifier = Modifier.tag("action:tag-remove"),
                        ) { Text("Vom Knoten entfernen") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { longPressTag = null }) { Text("Abbrechen") }
            },
        )
    }
}

/**
 * BottomSheet zum Zuweisen: Textfeld filtert [available] (case-insensitiv) und legt
 * bei Nicht-Treffer neu an ([allowCreate]). [assigned] wird ausgeblendet.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TagPickerSheet(
    available: List<String>,
    assigned: List<String>,
    allowCreate: Boolean,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }

    val filtered = remember(query, available, assigned) {
        val q = query.trim()
        available
            .filter { !assigned.any { a -> a.equals(it, ignoreCase = true) } }
            .filter { q.isEmpty() || it.contains(q, ignoreCase = true) }
    }

    val showCreate = remember(query, available, assigned) {
        val q = query.trim()
        allowCreate && q.isNotEmpty()
            && !available.any { it.equals(q, ignoreCase = true) }
            && !assigned.any { it.equals(q, ignoreCase = true) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Tag eingeben…") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .tag("field:tag"),
        )

        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .imePadding(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (showCreate) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .tag("action:tag-create")
                        .combinedClickable(onClick = { onPick(query.trim()) }),
                ) {
                    Text(
                        text = "\"${query.trim()}\" neu anlegen",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }

            if (filtered.isEmpty() && !showCreate && !allowCreate) {
                Text(
                    text = "Noch keine Tags vergeben.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            for (tag in filtered) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.combinedClickable(onClick = { onPick(tag) }),
                ) {
                    Text(
                        text = tag,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
        Box(modifier = Modifier.imePadding())
    }
}
