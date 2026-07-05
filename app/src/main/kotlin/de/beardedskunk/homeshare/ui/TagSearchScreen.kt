package de.beardedskunk.homeshare.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.beardedskunk.homeshare.data.BlobStore
import de.beardedskunk.homeshare.data.FeedRepository
import de.beardedskunk.homeshare.data.NodeState
import de.beardedskunk.homeshare.data.Settings
import de.beardedskunk.homeshare.data.TagHit
import de.beardedskunk.homeshare.sync.SyncManager
import de.beardedskunk.homeshare.core.NodeKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tag-Suche: zeigt alle Knoten (alle Feeds, beliebige Tiefe), die ALLE gewählten Tags
 * tragen. Eigener Screen oberhalb der normalen Navigation; X/Back schließt.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TagSearchScreen(
    repo: FeedRepository,
    blobStore: BlobStore,
    sync: SyncManager,
    settings: Settings,
    initialTags: List<String>,
    onOpenShare: (NodeState) -> Unit,
    onRequestCalendarSync: () -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val revision by repo.revision.collectAsState()

    val selectedTags = remember { mutableStateListOf<String>().also { it.addAll(initialTags) } }
    var hits by remember { mutableStateOf<List<TagHit>>(emptyList()) }
    var allTagsCache by remember { mutableStateOf<List<String>>(emptyList()) }
    var pickerOpen by remember { mutableStateOf(false) }

    // Stapel für Navigation in Listen-Treffern.
    val localNav = remember { mutableStateListOf<String>() }

    // Modale Detail-Screens für Nicht-Listen-Treffer.
    var noteEdit by remember { mutableStateOf<NodeState?>(null) }
    var todoOpen by remember { mutableStateOf<NodeState?>(null) }
    var calEdit by remember { mutableStateOf<NodeState?>(null) }
    var attOpen by remember { mutableStateOf<NodeState?>(null) }

    LaunchedEffect(selectedTags.toList(), revision) {
        if (selectedTags.isEmpty()) { onClose(); return@LaunchedEffect }
        hits = withContext(Dispatchers.IO) { repo.tagSearch(selectedTags.toList()) }
    }

    fun loadAllTags() = scope.launch {
        allTagsCache = withContext(Dispatchers.IO) { repo.allTags() }
    }

    fun canWriteNode(node: NodeState): Boolean {
        if (!node.isForeign) return true
        val root = repo.getNode(node.rootId) ?: return false
        return root.foreignRight.canWrite()
    }

    fun onSearchTagFromChild(tag: String) {
        selectedTags.clear()
        selectedTags.add(tag)
        localNav.clear()
        noteEdit = null; todoOpen = null; calEdit = null; attOpen = null
    }

    // ---- Modale Detail-Screens ----
    attOpen?.let { a ->
        BackHandler { attOpen = null }
        AttachmentDetailScreen(
            repo = repo, blobStore = blobStore, attachment = a,
            readOnly = !canWriteNode(a), onOpenShare = onOpenShare,
            onSearchTag = ::onSearchTagFromChild, onClose = { attOpen = null },
        )
        return
    }
    todoOpen?.let { t ->
        BackHandler { todoOpen = null }
        TodoDetailScreen(
            repo = repo, blobStore = blobStore, todo = t, settings = settings,
            readOnly = !canWriteNode(t), onOpenShare = onOpenShare,
            onRequestCalendarSync = onRequestCalendarSync,
            onSearchTag = ::onSearchTagFromChild, onClose = { todoOpen = null },
        )
        return
    }
    noteEdit?.let { n ->
        BackHandler { noteEdit = null }
        PostDetailEditor(
            repo = repo, blobStore = blobStore, parentId = n.parentId, post = n,
            readOnly = !canWriteNode(n), onOpenShare = onOpenShare,
            onSearchTag = ::onSearchTagFromChild, onClose = { noteEdit = null },
        )
        return
    }
    calEdit?.let { c ->
        BackHandler { calEdit = null }
        CalendarEntryEditor(
            repo = repo, blobStore = blobStore, parentId = c.parentId, post = c,
            settings = settings, onOpenShare = onOpenShare,
            onRequestCalendarSync = onRequestCalendarSync,
            onSearchTag = ::onSearchTagFromChild, onClose = { calEdit = null },
        )
        return
    }

    // ---- Liste aus localNav ----
    if (localNav.isNotEmpty()) {
        val nodeId = localNav.last()
        BackHandler { localNav.removeAt(localNav.lastIndex) }
        val container = remember(nodeId) { repo.getNode(nodeId) }
        ListScreen(
            repo = repo, blobStore = blobStore, sync = sync, settings = settings,
            container = container,
            onOpenShare = onOpenShare,
            onOpenList = { localNav.add(it.nodeId) },
            onRequestCalendarSync = onRequestCalendarSync,
            onSearchTag = ::onSearchTagFromChild,
            onBack = { localNav.removeAt(localNav.lastIndex) },
        )
        return
    }

    BackHandler { onClose() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                title = { Text("Tag-Suche") },
                actions = {
                    IconButton(onClick = onClose, modifier = Modifier.tag("topbar:close")) {
                        Icon(Icons.Filled.Close, contentDescription = "Suche schließen")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Auswahl-Zeile: gewählte Tags als Chips mit X + Plus für weitere Tags.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { loadAllTags(); pickerOpen = true },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Tag hinzufügen")
                }
                Spacer(Modifier.width(4.dp))
                androidx.compose.foundation.lazy.LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(selectedTags.toList()) { tag ->
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.tag("tagsel:$tag"),
                        ) {
                            Row(
                                Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(tag, style = MaterialTheme.typography.labelSmall)
                                IconButton(
                                    onClick = { selectedTags.remove(tag) },
                                    modifier = Modifier.size(20.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Tag entfernen",
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Trefferliste.
            if (hits.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Keine Treffer.")
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    items(hits, key = { it.node.nodeId }) { hit ->
                        TagHitRow(hit = hit, onClick = {
                            when (hit.node.kind) {
                                NodeKind.LIST -> localNav.add(hit.node.nodeId)
                                NodeKind.NOTE -> noteEdit = hit.node
                                NodeKind.TODO -> todoOpen = hit.node
                                NodeKind.CALENDAR -> calEdit = hit.node
                                NodeKind.IMAGE, NodeKind.FILE -> attOpen = hit.node
                            }
                        })
                    }
                }
            }
        }
    }

    if (pickerOpen) {
        TagPickerSheet(
            available = allTagsCache,
            assigned = selectedTags.toList(),
            allowCreate = false,
            onPick = { tag -> pickerOpen = false; if (!selectedTags.contains(tag)) selectedTags.add(tag) },
            onDismiss = { pickerOpen = false },
        )
    }
}

@Composable
private fun TagHitRow(hit: TagHit, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .tag(rowTag(hit.node.title)),
        onClick = onClick,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                hit.node.kind.uiIcon(),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    hit.node.title.ifBlank { "(ohne Titel)" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (hit.parentTitles.isNotEmpty()) {
                    val breadcrumb = (if (hit.more) "… / " else "") + hit.parentTitles.joinToString(" / ")
                    Text(
                        breadcrumb,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
