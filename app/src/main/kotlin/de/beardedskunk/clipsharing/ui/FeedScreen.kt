package de.beardedskunk.clipsharing.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.beardedskunk.clipsharing.data.Feed
import de.beardedskunk.clipsharing.data.FeedRepository
import de.beardedskunk.clipsharing.data.PostState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(repo: FeedRepository, feed: Feed, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var posts by remember { mutableStateOf<List<PostState>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<PostState?>(null) }
    val listState = rememberLazyListState()

    fun reload() {
        scope.launch {
            posts = withContext(Dispatchers.IO) {
                if (searching && query.isNotBlank()) repo.search(feed.id, query) else repo.listPosts(feed.id)
            }
        }
    }
    LaunchedEffect(feed.id, searching, query) { reload() }

    val current = editing
    if (current != null) {
        PostEditor(
            initialText = current.text,
            onSave = { newText ->
                scope.launch {
                    withContext(Dispatchers.IO) { repo.editPost(feed.id, current.postId, newText) }
                    editing = null
                    reload()
                }
            },
            onDelete = {
                scope.launch {
                    withContext(Dispatchers.IO) { repo.deletePost(feed.id, current.postId) }
                    editing = null
                    reload()
                }
            },
            onCancel = { editing = null },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Im Feed suchen…") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(feed.name)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        searching = !searching
                        if (!searching) query = ""
                    }) {
                        Icon(
                            if (searching) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = if (searching) "Suche schließen" else "Suchen",
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (!searching) {
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = { Text("Nachricht…") },
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        enabled = draft.isNotBlank(),
                        onClick = {
                            val text = draft.trim()
                            draft = ""
                            scope.launch {
                                withContext(Dispatchers.IO) { repo.createPost(feed.id, text) }
                                reload()
                            }
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Senden")
                    }
                }
            }
        },
    ) { padding ->
        if (posts.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(if (searching) "Keine Treffer." else "Noch keine Nachrichten.")
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                items(posts, key = { it.postId }) { post ->
                    PostBubble(post = post, onClick = { editing = post })
                }
            }
            LaunchedEffect(posts.size) {
                if (posts.isNotEmpty()) listState.scrollToItem(posts.size - 1)
            }
        }
    }
}

@Composable
private fun PostBubble(post: PostState, onClick: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = if (post.conflicted) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(12.dp)) {
            if (post.conflicted) {
                Text(
                    "⚠ Konflikt – zum Auflösen tippen",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Text(if (post.deleted) "(gelöscht)" else post.text)
        }
    }
}
