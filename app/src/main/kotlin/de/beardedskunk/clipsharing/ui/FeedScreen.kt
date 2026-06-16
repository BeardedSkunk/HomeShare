package de.beardedskunk.clipsharing.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.beardedskunk.clipsharing.data.BlobStore
import de.beardedskunk.clipsharing.data.Feed
import de.beardedskunk.clipsharing.data.FeedRepository
import de.beardedskunk.clipsharing.data.PostState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(repo: FeedRepository, blobStore: BlobStore, feed: Feed, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var posts by remember { mutableStateOf<List<PostState>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    var pendingImages by remember { mutableStateOf<List<String>>(emptyList()) }
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

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val sha = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { blobStore.put(it) }
                }
                if (sha != null) pendingImages = pendingImages + sha
            }
        }
    }

    fun send() {
        val text = draft.trim()
        val images = pendingImages
        if (text.isEmpty() && images.isEmpty()) return
        draft = ""
        pendingImages = emptyList()
        scope.launch {
            withContext(Dispatchers.IO) { repo.createPost(feed.id, text, images) }
            reload()
        }
    }

    val current = editing
    if (current != null) {
        PostEditor(
            initialText = current.text,
            onSave = { newText ->
                scope.launch {
                    withContext(Dispatchers.IO) { repo.editPost(feed.id, current.postId, newText, current.imageHashes) }
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
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(onClick = {
                        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = "Bild anhängen")
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = {
                            Text(if (pendingImages.isEmpty()) "Nachricht…" else "${pendingImages.size} Bild(er) angehängt")
                        },
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(enabled = draft.isNotBlank() || pendingImages.isNotEmpty(), onClick = { send() }) {
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
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding)) {
                items(posts, key = { it.postId }) { post ->
                    PostBubble(post = post, blobStore = blobStore, onClick = { editing = post })
                }
            }
            LaunchedEffect(posts.size) {
                if (posts.isNotEmpty()) listState.scrollToItem(posts.size - 1)
            }
        }
    }
}

@Composable
private fun PostBubble(post: PostState, blobStore: BlobStore, onClick: () -> Unit) {
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
            if (!post.deleted && post.imageHashes.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (sha in post.imageHashes) {
                        val bmp = rememberBlobImage(blobStore, sha)
                        if (bmp != null) {
                            Image(
                                bitmap = bmp,
                                contentDescription = null,
                                modifier = Modifier.size(120.dp),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
                                Text("🖼")
                            }
                        }
                    }
                }
            }
            val text = if (post.deleted) "(gelöscht)" else post.text
            if (text.isNotEmpty()) Text(text)
        }
    }
}

/** Laedt – falls vorhanden – das Voll-Bild, sonst das Thumbnail eines Blobs. */
@Composable
private fun rememberBlobImage(blobStore: BlobStore, sha: String): ImageBitmap? {
    var img by remember(sha) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(sha) {
        img = withContext(Dispatchers.IO) {
            val file = if (blobStore.hasFull(sha)) blobStore.fullFile(sha) else blobStore.thumbFile(sha)
            if (file.exists()) BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() else null
        }
    }
    return img
}
