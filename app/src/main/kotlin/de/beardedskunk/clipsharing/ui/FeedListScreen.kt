package de.beardedskunk.clipsharing.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedListScreen(repo: FeedRepository, statusText: String = "", onOpenFeed: (Feed) -> Unit) {
    val scope = rememberCoroutineScope()
    var feeds by remember { mutableStateOf<List<Feed>>(emptyList()) }
    var showCreate by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch { feeds = withContext(Dispatchers.IO) { repo.listFeeds() } }
    }
    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("Feeds")
                    if (statusText.isNotBlank()) {
                        Text(statusText, style = MaterialTheme.typography.labelSmall)
                    }
                }
            })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Feed anlegen")
            }
        },
    ) { padding ->
        if (feeds.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Noch keine Feeds. Mit + einen anlegen.")
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(feeds, key = { it.id }) { feed ->
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .clickable { onOpenFeed(feed) },
                    ) {
                        Text(
                            feed.name.ifBlank { "(ohne Namen)" },
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }

    if (showCreate) {
        TextPromptDialog(
            title = "Neuer Feed",
            label = "Name",
            confirmText = "Anlegen",
            onConfirm = { name ->
                showCreate = false
                if (name.isNotBlank()) {
                    scope.launch {
                        withContext(Dispatchers.IO) { repo.createFeed(name) }
                        reload()
                    }
                }
            },
            onDismiss = { showCreate = false },
        )
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
