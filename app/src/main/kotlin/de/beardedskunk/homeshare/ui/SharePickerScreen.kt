package de.beardedskunk.homeshare.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.beardedskunk.homeshare.calendar.IcsParser
import de.beardedskunk.homeshare.calendar.importIcsToFeed
import de.beardedskunk.homeshare.data.BlobStore
import de.beardedskunk.homeshare.data.Feed
import de.beardedskunk.homeshare.data.FeedRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Per Share-Intent uebergebener Inhalt (Text und/oder ein Bild). */
data class SharedContent(val text: String?, val imageUri: Uri?)

/**
 * Auswahl des Ziel-Feeds beim Teilen an die App. Nach Auswahl wird der Beitrag
 * (Text und/oder Bild) im gewaehlten Feed angelegt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharePickerScreen(
    repo: FeedRepository,
    blobStore: BlobStore,
    shared: SharedContent,
    onShared: (Feed) -> Unit,
    onCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var feeds by remember { mutableStateOf<List<Feed>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { feeds = withContext(Dispatchers.IO) { repo.listFeeds() } }

    fun shareInto(feed: Feed) {
        if (busy) return
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) {
                val text = shared.text?.trim().orEmpty()
                // Geteilter Kalender-Inhalt (VEVENT/.ics als Text) -> als Termin importieren (Tbd a).
                if (shared.imageUri == null && text.isNotBlank() && IcsParser.parse(text) != null) {
                    importIcsToFeed(repo, feed.id, text)
                } else {
                    val images = shared.imageUri?.let { uri ->
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { listOf(blobStore.put(it)) }
                    } ?: emptyList()
                    repo.createPost(feed.id, text, images)
                }
            }
            onShared(feed)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Teilen in…") },
                navigationIcon = {
                    IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "Abbrechen") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val preview = buildString {
                if (!shared.text.isNullOrBlank()) append(shared.text.take(120))
                if (shared.imageUri != null) { if (isNotEmpty()) append("  "); append("🖼 Bild") }
            }
            if (preview.isNotBlank()) {
                Text(preview, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
            if (feeds.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Keine Feeds vorhanden – lege zuerst in der App einen an.")
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(feeds, key = { it.id }) { feed ->
                        Card(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .clickable(enabled = !busy) { shareInto(feed) },
                        ) {
                            Text(feed.name.ifBlank { "(ohne Namen)" }, fontWeight = FontWeight.Medium, modifier = Modifier.padding(16.dp))
                        }
                    }
                }
            }
        }
    }
}
