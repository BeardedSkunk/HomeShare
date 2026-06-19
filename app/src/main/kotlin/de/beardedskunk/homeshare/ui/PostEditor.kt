package de.beardedskunk.homeshare.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/**
 * Editor fuer (auch sehr lange) Post-Texte mit In-Post-Suche.
 *
 * Die Suche springt per Auswahl (Selektion) zur jeweiligen Fundstelle -> das
 * Textfeld scrollt automatisch dorthin. Weiter/Zurueck wandert durch alle Treffer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostEditor(
    initialText: String,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    var tfv by remember { mutableStateOf(TextFieldValue(initialText)) }
    var findOpen by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var matchIdx by remember { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }

    val matches: List<Int> = remember(tfv.text, findQuery) {
        if (findQuery.isBlank()) emptyList() else findAll(tfv.text, findQuery)
    }

    fun jumpTo(index: Int) {
        if (matches.isEmpty()) return
        val i = ((index % matches.size) + matches.size) % matches.size
        matchIdx = i
        val start = matches[i]
        tfv = tfv.copy(selection = TextRange(start, start + findQuery.length))
        focusRequester.requestFocus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bearbeiten") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Abbrechen")
                    }
                },
                actions = {
                    IconButton(onClick = { findOpen = !findOpen }) {
                        Icon(Icons.Filled.Search, contentDescription = "Im Text suchen")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = "Löschen")
                    }
                    IconButton(onClick = { onSave(tfv.text) }) {
                        Icon(Icons.Filled.Check, contentDescription = "Speichern")
                    }
                },
            )
        },
    ) { padding ->
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (findOpen) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OutlinedTextField(
                        value = findQuery,
                        onValueChange = { findQuery = it; matchIdx = 0 },
                        placeholder = { Text("Suchen…") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Text(if (matches.isEmpty()) "0/0" else "${matchIdx + 1}/${matches.size}")
                    IconButton(enabled = matches.isNotEmpty(), onClick = { jumpTo(matchIdx - 1) }) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Vorheriger Treffer")
                    }
                    IconButton(enabled = matches.isNotEmpty(), onClick = { jumpTo(matchIdx + 1) }) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Nächster Treffer")
                    }
                }
            }
            OutlinedTextField(
                value = tfv,
                onValueChange = { tfv = it },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
                    .focusRequester(focusRequester),
            )
        }
    }
}

/** Alle Start-Indizes von [needle] in [haystack] (case-insensitive, ueberlappungsfrei). */
private fun findAll(haystack: String, needle: String): List<Int> {
    if (needle.isEmpty()) return emptyList()
    val out = ArrayList<Int>()
    var from = 0
    while (true) {
        val idx = haystack.indexOf(needle, from, ignoreCase = true)
        if (idx < 0) break
        out += idx
        from = idx + needle.length
    }
    return out
}
