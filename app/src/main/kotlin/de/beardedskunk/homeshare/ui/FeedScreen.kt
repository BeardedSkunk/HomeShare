package de.beardedskunk.homeshare.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import de.beardedskunk.homeshare.data.BlobStore
import de.beardedskunk.homeshare.data.Feed
import de.beardedskunk.homeshare.data.FeedRepository
import de.beardedskunk.homeshare.data.PostState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    repo: FeedRepository,
    blobStore: BlobStore,
    feed: Feed,
    settings: de.beardedskunk.homeshare.data.Settings,
    /** Geteilter Suchzustand (siehe [onSearchQueryChange]): null = Suche zu, sonst offen. */
    searchQuery: String? = null,
    onSearchQueryChange: (String?) -> Unit = {},
    onRequestCalendarSync: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // #10: Rechte bei Fremdfeeds. Eigene Feeds = volle Rechte.
    val canWrite = !feed.isForeign || feed.foreignRight.canWrite()
    val canMerge = !feed.isForeign || feed.foreignRight.canMerge()
    var calEnabled by remember(feed.id) { mutableStateOf(settings.isCalendarFeedEnabled(feed.id)) }
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
    var posts by remember { mutableStateOf<List<PostState>>(emptyList()) }
    // Suche ist geteilter Zustand: null = zu, sonst offen (ggf. leer). Bleibt beim Navigieren erhalten.
    val searching = searchQuery != null
    val query = searchQuery ?: ""
    var editing by remember { mutableStateOf<PostState?>(null) }
    var creatingNew by remember { mutableStateOf(false) }
    var resolving by remember { mutableStateOf<PostState?>(null) }
    var resolvingDetailed by remember { mutableStateOf<PostState?>(null) }
    var viewingImage by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch {
            posts = withContext(Dispatchers.IO) {
                if (searching && query.isNotBlank()) repo.search(feed.id, query) else repo.listPosts(feed.id)
            }
        }
    }
    // revision: auch per Sync empfangene Aenderungen aktualisieren die Liste sofort.
    val revision by repo.revision.collectAsState()
    LaunchedEffect(feed.id, searching, query, revision) { reload() }

    // Eintrag oeffnen: Konflikt nur loesen, wenn merge-Recht (sonst nur ansehen + Hinweis).
    fun openPost(p: PostState) {
        if (p.conflicted && canMerge) {
            resolving = p
        } else {
            if (p.conflicted && !canMerge) {
                Toast.makeText(context, "Konflikt offen – nur die Originalgruppe kann ihn lösen.", Toast.LENGTH_LONG).show()
            }
            editing = p
        }
    }

    // --- Vollbild-Ansicht eines Bildes ---
    val img = viewingImage
    if (img != null) {
        BackHandler { viewingImage = null }
        // Merge-/Listen-Kontext: nur Teilen, kein Bearbeiten/Löschen, kein (uneindeutiger) Titel.
        ImageViewerScreen(
            blobStore = blobStore,
            sha = img,
            onBack = { viewingImage = null },
            onShare = { shareImage(img) },
        )
        return
    }

    // --- Konfliktauflösung: ganze Fassung wählen (Kombi-Ansicht) ---
    val conflict = resolving
    if (conflict != null) {
        BackHandler { resolving = null }
        ConflictScreen(
            repo = repo,
            blobStore = blobStore,
            feed = feed,
            post = conflict,
            onOpenImage = { viewingImage = it },
            onResolved = { resolving = null; reload() },
            onCancel = { resolving = null },
        )
        return
    }

    // --- Konfliktauflösung: Teil für Teil zusammenführen (Detail-Ansicht) ---
    val detailed = resolvingDetailed
    if (detailed != null) {
        BackHandler { resolvingDetailed = null }
        DetailMergeScreen(
            repo = repo,
            blobStore = blobStore,
            feed = feed,
            post = detailed,
            onOpenImage = { viewingImage = it },
            onResolved = { resolvingDetailed = null; reload() },
            onCancel = { resolvingDetailed = null },
        )
        return
    }

    // --- Detail-/Editier-Ansicht (bestehend oder neu) ---
    val current = editing
    if (current != null || creatingNew) {
        BackHandler { editing = null; creatingNew = false; reload() }
        if (feed.calendar) {
            CalendarEntryEditor(
                repo = repo,
                feed = feed,
                post = current,
                onClose = { editing = null; creatingNew = false; reload() },
            )
        } else {
            PostDetailEditor(
                repo = repo,
                blobStore = blobStore,
                feed = feed,
                post = current,
                // Bestehender Eintrag: geteilten Suchzustand durchreichen (gleiches Suchwort, durchsteppbar).
                // Neuer Eintrag: keine Suche.
                searchQuery = if (current != null) searchQuery else null,
                onSearchQueryChange = onSearchQueryChange,
                readOnly = !canWrite, // Fremdfeed ohne Schreibrecht -> nur ansehen
                onClose = { editing = null; creatingNew = false; reload() },
            )
        }
        return
    }

    // Liste: System-Zurück geht zur Feed-Übersicht (wie der Pfeil oben links).
    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { onSearchQueryChange(it) },
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
                    // Schließen leert den Begriff (null) -> propagiert nach oben, andere Ebenen sind dann auch zu.
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
                    text = { Text("Neuer Eintrag") },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    onClick = { creatingNew = true },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (feed.calendar) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("In Android-Kalender übernehmen", modifier = Modifier.weight(1f))
                    Switch(
                        checked = calEnabled,
                        onCheckedChange = {
                            calEnabled = it
                            settings.setCalendarFeedEnabled(feed.id, it)
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
                    items(posts, key = { it.postId }) { post ->
                    if (feed.calendar) {
                        CalendarRow(post = post, onClick = { openPost(post) })
                    } else {
                        PostRow(
                            post = post,
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

/**
 * Einzeiliger Listeneintrag: erste (gekürzte) Textzeile, rechts bis zu drei
 * quadratische Mini-Thumbnails so hoch wie die Box. Antippen eines Thumbnails
 * öffnet das Bild groß; Antippen der Box öffnet den Eintrag.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PostRow(
    post: PostState,
    blobStore: BlobStore,
    canMerge: Boolean = true,
    onClick: () -> Unit,
    onResolveWhole: () -> Unit,
    onResolveDetailed: () -> Unit,
    onOpenImage: (String) -> Unit,
) {
    val rowHeight = 56.dp
    // Long-Press auf einen Konflikt-Eintrag (rot) bietet die Wahl zwischen
    // Kombi-Auflösung (ganze Fassung) und Detail-Merge (Teil für Teil) – nur mit merge-Recht.
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
            DropdownMenuItem(
                text = { Text("Ganze Fassung wählen") },
                onClick = { menuOpen = false; onResolveWhole() },
            )
            DropdownMenuItem(
                text = { Text("Im Detail zusammenführen") },
                onClick = { menuOpen = false; onResolveDetailed() },
            )
        }
        Row(
            Modifier.fillMaxWidth().height(rowHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val raw = if (post.deleted) "(gelöscht)" else post.text
            val firstLine = raw.lineSequence().firstOrNull().orEmpty().ifBlank {
                if (post.imageHashes.isNotEmpty()) "🖼 Bild" else ""
            }
            Text(
                text = (if (post.conflicted) "⚠ " else "") + firstLine,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 14.dp),
            )
            // Aufgaben-Badge (erledigt/gesamt); wenn vorhanden, nur EIN Thumbnail rechts.
            val tasks = if (post.deleted) null else taskCounts(post.text)
            if (tasks != null) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.padding(end = 8.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            " ${tasks.first}/${tasks.second}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
            val thumbCount = if (tasks != null) 1 else 3
            for (sha in post.imageHashes.take(thumbCount)) {
                val bmp = rememberBlobBitmap(blobStore, sha, preferFull = false)
                Box(
                    Modifier
                        .fillMaxHeight()
                        .aspectRatio(1f)
                        .clickable { onOpenImage(sha) },
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
