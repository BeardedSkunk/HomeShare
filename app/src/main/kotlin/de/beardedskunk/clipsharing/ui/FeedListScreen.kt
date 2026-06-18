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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material.icons.filled.Share
import de.beardedskunk.clipsharing.data.Feed
import de.beardedskunk.clipsharing.data.FeedRepository
import de.beardedskunk.clipsharing.sync.SyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FeedListScreen(
    repo: FeedRepository,
    sync: SyncManager,
    statusText: String = "",
    webUrl: String? = null,
    onToggleWeb: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenShare: (Feed) -> Unit = {},
    /** Geteilter Suchzustand (siehe [onSearchQueryChange]): null = Suche zu, sonst offen. */
    searchQuery: String? = null,
    onSearchQueryChange: (String?) -> Unit = {},
    onOpenFeed: (Feed) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var feeds by remember { mutableStateOf<List<Feed>>(emptyList()) }
    var showCreate by remember { mutableStateOf(false) }
    var feedToDelete by remember { mutableStateOf<Feed?>(null) }
    var actionFeed by remember { mutableStateOf<Feed?>(null) }
    var showAddShared by remember { mutableStateOf(false) }
    val searching = searchQuery != null
    val query = searchQuery ?: ""
    var matchedIds by remember { mutableStateOf<Set<String>?>(null) }

    fun reload() {
        scope.launch { feeds = withContext(Dispatchers.IO) { repo.listFeeds() } }
    }
    // Bei JEDER Aenderung (auch per Sync empfangen) automatisch neu laden.
    val revision by repo.revision.collectAsState()
    LaunchedEffect(revision) { reload() }
    // Übersichts-Suche: schränkt die Feeds auf die ein, in denen (oder deren Namen) der Begriff vorkommt.
    LaunchedEffect(searching, query, revision) {
        matchedIds = if (searching && query.isNotBlank()) withContext(Dispatchers.IO) { repo.feedsMatching(query) } else null
    }
    val shownFeeds = matchedIds?.let { ids -> feeds.filter { it.id in ids } } ?: feeds

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        OutlinedTextField(
                            value = query, onValueChange = { onSearchQueryChange(it) },
                            placeholder = { Text("Feeds durchsuchen…") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Column {
                            Text("Feeds")
                            if (statusText.isNotBlank()) {
                                Text(statusText, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { onSearchQueryChange(if (searching) null else "") }) {
                        Icon(
                            if (searching) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = if (searching) "Suche schließen" else "Feeds durchsuchen",
                        )
                    }
                    if (!searching) {
                        IconButton(onClick = { showAddShared = true }) {
                            Icon(Icons.Filled.Share, contentDescription = "Geteilten Feed hinzufügen")
                        }
                        TextButton(onClick = onToggleWeb) {
                            Text(if (webUrl == null) "Web starten" else "Web stoppen")
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "Einstellungen")
                        }
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
            if (shownFeeds.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (searching && query.isNotBlank()) "Keine Feeds mit „$query“." else "Noch keine Feeds. Mit + einen anlegen.")
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                items(shownFeeds, key = { it.id }) { feed ->
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .combinedClickable(
                                onClick = { onOpenFeed(feed) },
                                onLongClick = { actionFeed = feed }, // lang drücken -> Aktionen
                            ),
                    ) {
                        val prefix = buildString {
                            if (feed.calendar) append("📅 ")
                            if (feed.shared) append("📤 ")       // eigener, geteilter Feed
                            if (feed.isForeign) append("🔗 ")    // fremder, abonnierter Feed
                        }
                        Text(
                            prefix + feed.name.ifBlank { "(ohne Namen)" },
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
            }
        }
    }

    actionFeed?.let { feed ->
        AlertDialog(
            onDismissRequest = { actionFeed = null },
            title = { Text(feed.name.ifBlank { "(ohne Namen)" }) },
            text = {
                Column {
                    if (feed.isForeign) {
                        TextButton(onClick = {
                            val id = feed.id; actionFeed = null
                            scope.launch { withContext(Dispatchers.IO) { repo.leaveForeignFeed(id) }; reload() }
                        }) { Text("Freigabe verlassen (lokal entfernen)") }
                    } else {
                        TextButton(onClick = { val f = feed; actionFeed = null; onOpenShare(f) }) { Text("Mit Gruppe teilen / Freigaben…") }
                        TextButton(onClick = { val f = feed; actionFeed = null; feedToDelete = f }) { Text("Feed löschen") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { actionFeed = null }) { Text("Abbrechen") } },
        )
    }

    if (showAddShared) {
        AddSharedFeedDialog(sync = sync, onDone = { showAddShared = false; reload() }, onDismiss = { showAddShared = false })
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
