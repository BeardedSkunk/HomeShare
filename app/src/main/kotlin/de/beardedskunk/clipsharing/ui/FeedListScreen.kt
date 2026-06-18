package de.beardedskunk.clipsharing.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import de.beardedskunk.clipsharing.data.Feed
import de.beardedskunk.clipsharing.data.FeedRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FeedListScreen(
    repo: FeedRepository,
    statusText: String = "",
    webUrl: String? = null,
    onToggleWeb: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenFeed: (Feed) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var feeds by remember { mutableStateOf<List<Feed>>(emptyList()) }
    var showCreate by remember { mutableStateOf(false) }
    var feedToDelete by remember { mutableStateOf<Feed?>(null) }

    fun reload() {
        scope.launch { feeds = withContext(Dispatchers.IO) { repo.listFeeds() } }
    }
    // Bei JEDER Aenderung (auch per Sync empfangen) automatisch neu laden.
    val revision by repo.revision.collectAsState()
    LaunchedEffect(revision) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Feeds")
                        if (statusText.isNotBlank()) {
                            Text(statusText, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                actions = {
                    TextButton(onClick = onToggleWeb) {
                        Text(if (webUrl == null) "Web starten" else "Web stoppen")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Einstellungen")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Feed anlegen")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (webUrl != null) {
                Text(
                    "Webserver läuft: $webUrl",
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (feeds.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Noch keine Feeds. Mit + einen anlegen.")
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                items(feeds, key = { it.id }) { feed ->
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .combinedClickable(
                                onClick = { onOpenFeed(feed) },
                                onLongClick = { feedToDelete = feed }, // lang drücken -> löschen
                            ),
                    ) {
                        Text(
                            (if (feed.calendar) "📅 " else "") + feed.name.ifBlank { "(ohne Namen)" },
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
            }
        }
    }

    feedToDelete?.let { feed ->
        AlertDialog(
            onDismissRequest = { feedToDelete = null },
            title = { Text("Feed löschen?") },
            text = {
                Text(
                    "„${feed.name.ifBlank { "(ohne Namen)" }}" + "“ wird für alle Geräte deiner Gruppe gelöscht " +
                        "(inkl. aller Einträge). Das lässt sich nicht rückgängig machen.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = feed.id
                    feedToDelete = null
                    scope.launch {
                        withContext(Dispatchers.IO) { repo.deleteFeed(id) }
                        reload()
                    }
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { feedToDelete = null }) { Text("Abbrechen") } },
        )
    }

    if (showCreate) {
        CreateFeedDialog(
            onConfirm = { name, calendar ->
                showCreate = false
                if (name.isNotBlank()) {
                    scope.launch {
                        withContext(Dispatchers.IO) { repo.createFeed(name, calendar) }
                        reload()
                    }
                }
            },
            onDismiss = { showCreate = false },
        )
    }
}

/** Anlege-Dialog für einen Feed: Name + Option „nur Kalender-Einträge". */
@Composable
fun CreateFeedDialog(
    onConfirm: (name: String, calendar: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var calendar by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Neuer Feed", fontWeight = FontWeight.Bold)
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(checked = calendar, onCheckedChange = { calendar = it })
                    Text("Nur Kalender-Einträge (Sync in den Android-Kalender)")
                }
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("Abbrechen") }
                    Button(onClick = { onConfirm(name, calendar) }) { Text("Anlegen") }
                }
            }
        }
    }
}

/** Kleiner Eingabe-Dialog fuer einen einzelnen Textwert. */
@Composable
fun TextPromptDialog(
    title: String,
    label: String,
    confirmText: String,
    initial: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, fontWeight = FontWeight.Bold)
                OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text(label) })
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("Abbrechen") }
                    Button(onClick = { onConfirm(value) }) { Text(confirmText) }
                }
            }
        }
    }
}
