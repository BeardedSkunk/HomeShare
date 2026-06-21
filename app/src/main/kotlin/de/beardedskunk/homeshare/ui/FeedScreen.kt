package de.beardedskunk.homeshare.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import de.beardedskunk.homeshare.core.NodeType
import de.beardedskunk.homeshare.data.BlobStore
import de.beardedskunk.homeshare.data.FeedRepository
import de.beardedskunk.homeshare.data.NodeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Inhalt eines Feeds = die Kindknoten des Feed-(Wurzel-)Knotens. UI wie gehabt. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    repo: FeedRepository,
    blobStore: BlobStore,
    feed: NodeState,
    settings: de.beardedskunk.homeshare.data.Settings,
    searchQuery: String? = null,
    onSearchQueryChange: (String?) -> Unit = {},
    onRequestCalendarSync: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val canWrite = !feed.isForeign || feed.foreignRight.canWrite()
    val canMerge = !feed.isForeign || feed.foreignRight.canMerge()
    val isCalendar = feed.isCalendarFeed
    var calEnabled by remember(feed.nodeId) { mutableStateOf(settings.isCalendarFeedEnabled(feed.nodeId)) }
    fun shareImage(sha: String) {
        val file = if (blobStore.hasFull(sha)) blobStore.fullFile(sha) else blobStore.thumbFile(sha)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(send, "Bild teilen")) }
    }
    var posts by remember { mutableStateOf<List<NodeState>>(emptyList()) }
    // Pro Eintrag die Blob-Hashes seiner Bild-Kindknoten (für die Mini-Thumbnails der Liste).
    var postImages by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    val searching = searchQuery != null
    val query = searchQuery ?: ""
    var editing by remember { mutableStateOf<NodeState?>(null) }
    var creatingNew by remember { mutableStateOf(false) }
    var resolving by remember { mutableStateOf<NodeState?>(null) }
    var resolvingDetailed by remember { mutableStateOf<NodeState?>(null) }
    var viewingImage by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val list = if (searching && query.isNotBlank()) repo.search(feed.nodeId, query) else repo.listPosts(feed.nodeId)
                val imgs = if (isCalendar) emptyMap() else list.associate { p ->
                    p.nodeId to repo.children(p.nodeId).filter { it.type == NodeType.IMAGE && it.blobHash != null }.map { it.blobHash!! }
                }
                list to imgs
            }
            posts = result.first
            postImages = result.second
        }
    }
    val revision by repo.revision.collectAsState()
    LaunchedEffect(feed.nodeId, searching, query, revision) { reload() }

    fun openPost(p: NodeState) {
        if (p.conflicted && canMerge) {
            resolving = p
        } else {
            if (p.conflicted && !canMerge) {
                Toast.makeText(context, "Konflikt offen – nur die Originalgruppe kann ihn lösen.", Toast.LENGTH_LONG).show()
            }
            editing = p
        }
    }

    val img = viewingImage
    if (img != null) {
        BackHandler { viewingImage = null }
        ImageViewerScreen(blobStore = blobStore, sha = img, onBack = { viewingImage = null }, onShare = { shareImage(img) })
        return
    }

    val conflict = resolving
    if (conflict != null) {
        BackHandler { resolving = null }
        ConflictScreen(
            repo = repo, blobStore = blobStore, feed = feed, post = conflict,
            onOpenImage = { viewingImage = it },
            onResolved = { resolving = null; reload() }, onCancel = { resolving = null },
        )
        return
    }

    val detailed = resolvingDetailed
    if (detailed != null) {
        BackHandler { resolvingDetailed = null }
        DetailMergeScreen(
            repo = repo, blobStore = blobStore, feed = feed, post = detailed,
            onOpenImage = { viewingImage = it },
            onResolved = { resolvingDetailed = null; reload() }, onCancel = { resolvingDetailed = null },
        )
        return
    }

    val current = editing
    if (current != null || creatingNew) {
        BackHandler { editing = null; creatingNew = false; reload() }
        if (isCalendar) {
            CalendarEntryEditor(
                repo = repo, feed = feed, post = current,
                onClose = { editing = null; creatingNew = false; reload() },
            )
        } else {
            PostDetailEditor(
                repo = repo, blobStore = blobStore, feed = feed, post = current,
                searchQuery = if (current != null) searchQuery else null,
                onSearchQueryChange = onSearchQueryChange,
                readOnly = !canWrite,
                onClose = { editing = null; creatingNew = false; reload() },
            )
        }
        return
    }

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        OutlinedTextField(
                            value = query, onValueChange = { onSearchQueryChange(it) },
                            placeholder = { Text("Im Feed suchen…") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(feed.title)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    IconButton(onClick = { onSearchQueryChange(if (searching) null else "") }) {
                        Icon(
                            if (searching) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = if (searching) "Suche schließen" else "Suchen",
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (!searching && canWrite) {
                ExtendedFloatingActionButton(
                    text = { Text(if (isCalendar) "Neuer Termin" else "Neuer Eintrag") },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    onClick = { creatingNew = true },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (isCalendar) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("In Android-Kalender übernehmen", modifier = Modifier.weight(1f))
                    Switch(
                        checked = calEnabled,
                        onCheckedChange = {
                            calEnabled = it
                            settings.setCalendarFeedEnabled(feed.nodeId, it)
                            onRequestCalendarSync()
                        },
                    )
                }
            }
            if (feed.isForeign) {
                val rightLabel = when (feed.foreignRight) {
                    de.beardedskunk.homeshare.data.FeedRight.READ -> "nur lesen"
                    de.beardedskunk.homeshare.data.FeedRight.WRITE -> "lesen & schreiben"
                    de.beardedskunk.homeshare.data.FeedRight.MERGE -> "lesen, schreiben, mergen"
                }
                Text(
                    "🔗 Geteilt von „${feed.foreignOrigin}“ · $rightLabel",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (posts.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (searching) "Keine Treffer." else "Noch keine Einträge.")
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(posts, key = { it.nodeId }) { post ->
                        if (isCalendar) {
                            CalendarRow(post = post, onClick = { openPost(post) })
                        } else {
                            PostRow(
                                post = post,
                                imageHashes = postImages[post.nodeId] ?: emptyList(),
                                blobStore = blobStore,
                                canMerge = canMerge,
                                onClick = { openPost(post) },
                                onResolveWhole = { resolving = post },
                                onResolveDetailed = { resolvingDetailed = post },
                                onOpenImage = { viewingImage = it },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Einzeiliger Listeneintrag: erste Textzeile, rechts bis zu drei Mini-Thumbnails der Bild-Kindknoten. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PostRow(
    post: NodeState,
    imageHashes: List<String>,
    blobStore: BlobStore,
    canMerge: Boolean = true,
    onClick: () -> Unit,
    onResolveWhole: () -> Unit,
    onResolveDetailed: () -> Unit,
    onOpenImage: (String) -> Unit,
) {
    val rowHeight = 56.dp
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { if (post.conflicted && canMerge) menuOpen = true },
            ),
        colors = if (post.conflicted) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text("Ganze Fassung wählen") }, onClick = { menuOpen = false; onResolveWhole() })
            DropdownMenuItem(text = { Text("Im Detail zusammenführen") }, onClick = { menuOpen = false; onResolveDetailed() })
        }
        Row(Modifier.fillMaxWidth().height(rowHeight), verticalAlignment = Alignment.CenterVertically) {
            val raw = if (post.deleted) "(gelöscht)" else post.text
            val firstLine = raw.lineSequence().firstOrNull().orEmpty().ifBlank {
                if (imageHashes.isNotEmpty()) "🖼 Bild" else ""
            }
            Text(
                text = (if (post.conflicted) "⚠ " else "") + firstLine,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 14.dp),
            )
            val tasks = if (post.deleted) null else taskCounts(post.text)
            if (tasks != null) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.padding(end = 8.dp),
                ) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(" ${tasks.first}/${tasks.second}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
            val thumbCount = if (tasks != null) 1 else 3
            for (sha in imageHashes.take(thumbCount)) {
                val bmp = rememberBlobBitmap(blobStore, sha, preferFull = false)
                Box(
                    Modifier.fillMaxHeight().aspectRatio(1f).clickable { onOpenImage(sha) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (bmp != null) {
                        Image(bitmap = bmp, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Text("🖼")
                    }
                }
            }
        }
    }
}
