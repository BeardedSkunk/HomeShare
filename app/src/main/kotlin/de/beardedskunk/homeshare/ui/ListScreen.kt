package de.beardedskunk.homeshare.ui

import android.content.Intent
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import de.beardedskunk.homeshare.core.NodeContent
import de.beardedskunk.homeshare.core.NodeKind
import de.beardedskunk.homeshare.core.NodeType
import de.beardedskunk.homeshare.core.ROOT
import de.beardedskunk.homeshare.data.BlobStore
import de.beardedskunk.homeshare.data.FeedRepository
import de.beardedskunk.homeshare.data.FeedShareCodec
import de.beardedskunk.homeshare.data.NodeState
import de.beardedskunk.homeshare.data.Settings
import de.beardedskunk.homeshare.sync.SyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Standard-Icon je Nutzer-Typ (echte Material-Icons, via material-icons-extended). */
fun NodeKind.uiIcon(): ImageVector = when (this) {
    NodeKind.LIST -> Icons.AutoMirrored.Filled.ListAlt
    NodeKind.NOTE -> Icons.AutoMirrored.Filled.Notes
    NodeKind.CALENDAR -> Icons.Filled.CalendarMonth
    NodeKind.TODO -> Icons.Filled.TaskAlt
    NodeKind.IMAGE -> Icons.Filled.Image
    NodeKind.FILE -> Icons.Filled.InsertDriveFile
}

fun NodeKind.uiLabel(): String = when (this) {
    NodeKind.LIST -> "Liste"
    NodeKind.NOTE -> "Notiz"
    NodeKind.CALENDAR -> "Termin"
    NodeKind.TODO -> "Aufgabe"
    NodeKind.IMAGE -> "Bild"
    NodeKind.FILE -> "Datei"
}

/** Reihenfolge im FAB-Long-Press-Menü. */
private val CREATE_KINDS = listOf(NodeKind.LIST, NodeKind.NOTE, NodeKind.CALENDAR, NodeKind.TODO, NodeKind.IMAGE, NodeKind.FILE)

/**
 * Vereinheitlichte Listen-Ansicht für die Kinder EINES Knotens – oder der Wurzel ([container] == null
 * = „Feeds"). Zeigt gemischte Einträge (Liste/Notiz/Termin/Aufgabe/Bild/Datei) mit Typ-Icon. Der FAB
 * legt kurz den Default-Kindtyp an, lang gedrückt erscheint die Typ-Auswahl. Listen sind navigierbar
 * (Stack in [MainActivity]); Notizen/Termine öffnen ihren bestehenden Editor.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ListScreen(
    repo: FeedRepository,
    blobStore: BlobStore,
    sync: SyncManager,
    settings: Settings,
    container: NodeState?,
    onOpenSettings: () -> Unit = {},
    onOpenShare: (NodeState) -> Unit = {},
    onOpenList: (NodeState) -> Unit = {},
    onRequestCalendarSync: () -> Unit = {},
    searchQuery: String? = null,
    onSearchQueryChange: (String?) -> Unit = {},
    onBack: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val isRoot = container == null
    val parentId = container?.nodeId ?: ROOT
    val defaultKind = container?.childDefault ?: NodeKind.LIST
    val canWrite = container?.let { !it.isForeign || it.foreignRight.canWrite() } ?: true
    val canMerge = container?.let { !it.isForeign || it.foreignRight.canMerge() } ?: true
    val isCalendar = container?.isCalendarFeed == true

    var children by remember { mutableStateOf<List<NodeState>>(emptyList()) }
    var postImages by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var matchedIds by remember { mutableStateOf<Set<String>?>(null) }
    val searching = searchQuery != null
    val query = searchQuery ?: ""

    // Editor-/Dialog-Zustände
    var noteEdit by remember { mutableStateOf<NodeState?>(null) }
    var creatingNote by remember { mutableStateOf(false) }
    var calEdit by remember { mutableStateOf<NodeState?>(null) }
    var creatingCal by remember { mutableStateOf(false) }
    var resolving by remember { mutableStateOf<NodeState?>(null) }
    var resolvingDetailed by remember { mutableStateOf<NodeState?>(null) }
    var viewingImage by remember { mutableStateOf<String?>(null) }
    var showCreateList by remember { mutableStateOf(false) }
    var actionNode by remember { mutableStateOf<NodeState?>(null) }
    var showAddShared by remember { mutableStateOf(false) }
    var fabMenu by remember { mutableStateOf(false) }
    var calEnabled by remember(parentId) { mutableStateOf(if (isCalendar) settings.isCalendarFeedEnabled(parentId) else false) }

    val revision by repo.revision.collectAsState()

    fun reload() {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val list = repo.children(parentId)
                val imgs = list.filter { it.kind == NodeKind.NOTE }.associate { p ->
                    p.nodeId to repo.children(p.nodeId).mapNotNull { c -> if (c.type == NodeType.IMAGE) c.blobHash else null }
                }
                list to imgs
            }
            children = result.first
            postImages = result.second
        }
    }
    LaunchedEffect(parentId, revision) { reload() }
    LaunchedEffect(searching, query, revision) {
        matchedIds = if (searching && query.isNotBlank()) {
            withContext(Dispatchers.IO) {
                if (isRoot) repo.feedsMatching(query)
                else repo.search(container.rootId, query).map { it.nodeId }.toSet()
            }
        } else {
            null
        }
    }
    val shown = matchedIds?.let { ids -> children.filter { it.nodeId in ids } } ?: children

    fun shareImage(sha: String) {
        val file = if (blobStore.hasFull(sha)) blobStore.fullFile(sha) else blobStore.thumbFile(sha)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(send, "Bild teilen")) }
    }

    fun startCreate(kind: NodeKind) {
        if (!canWrite) return
        when (kind) {
            NodeKind.LIST -> showCreateList = true
            NodeKind.NOTE -> creatingNote = true
            NodeKind.CALENDAR -> creatingCal = true
            NodeKind.TODO -> scope.launch { withContext(Dispatchers.IO) { repo.createNode(NodeContent(parentId = parentId, type = NodeType.TODO, text = "Neue Aufgabe")) } }
            NodeKind.IMAGE -> scope.launch { withContext(Dispatchers.IO) { repo.createNode(NodeContent(parentId = parentId, type = NodeType.IMAGE, text = "Neues Bild")) } }
            NodeKind.FILE -> scope.launch { withContext(Dispatchers.IO) { repo.createNode(NodeContent(parentId = parentId, type = NodeType.FILE, text = "Neue Datei")) } }
        }
    }

    fun openChild(p: NodeState) {
        when (p.kind) {
            NodeKind.LIST -> onOpenList(p)
            NodeKind.NOTE -> if (p.conflicted && canMerge) resolving = p else noteEdit = p
            NodeKind.CALENDAR -> calEdit = p
            NodeKind.TODO, NodeKind.IMAGE, NodeKind.FILE ->
                toast(context, "${p.kind.uiLabel()} – Anzeige folgt später")
        }
    }

    // ---- Vollbild-Unteransichten (Editor/Konflikt/Bild) ----
    val img = viewingImage
    if (img != null) {
        BackHandler { viewingImage = null }
        ImageViewerScreen(blobStore = blobStore, sha = img, onBack = { viewingImage = null }, onShare = { shareImage(img) })
        return
    }
    resolving?.let { p ->
        BackHandler { resolving = null }
        ConflictScreen(repo = repo, blobStore = blobStore, feed = container ?: p, post = p, onOpenImage = { viewingImage = it }, onResolved = { resolving = null; reload() }, onCancel = { resolving = null })
        return
    }
    resolvingDetailed?.let { p ->
        BackHandler { resolvingDetailed = null }
        DetailMergeScreen(repo = repo, blobStore = blobStore, feed = container ?: p, post = p, onOpenImage = { viewingImage = it }, onResolved = { resolvingDetailed = null; reload() }, onCancel = { resolvingDetailed = null })
        return
    }
    if (noteEdit != null || creatingNote) {
        BackHandler { noteEdit = null; creatingNote = false; reload() }
        PostDetailEditor(
            repo = repo, blobStore = blobStore, parentId = parentId, post = noteEdit,
            searchQuery = if (noteEdit != null) searchQuery else null,
            onSearchQueryChange = onSearchQueryChange, readOnly = !canWrite,
            onClose = { noteEdit = null; creatingNote = false; reload() },
        )
        return
    }
    if (calEdit != null || creatingCal) {
        BackHandler { calEdit = null; creatingCal = false; reload() }
        CalendarEntryEditor(repo = repo, parentId = parentId, post = calEdit, onClose = { calEdit = null; creatingCal = false; reload() })
        return
    }

    if (!isRoot) BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        OutlinedTextField(
                            value = query, onValueChange = { onSearchQueryChange(it) },
                            placeholder = { Text(if (isRoot) "Feeds durchsuchen…" else "Durchsuchen…") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().tag("field:search"),
                        )
                    } else if (isRoot) {
                        Text("Feeds")
                    } else {
                        Text(container.title.ifBlank { "(ohne Namen)" })
                    }
                },
                navigationIcon = {
                    if (!isRoot) BackIconButton(onClick = onBack)
                },
                actions = {
                    IconButton(onClick = { onSearchQueryChange(if (searching) null else "") }, modifier = Modifier.tag("topbar:search")) {
                        Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, contentDescription = if (searching) "Suche schließen" else "Suchen")
                    }
                    if (!searching) {
                        // QR-Icon auf JEDER Listen-Ansicht: in einer Liste -> diese teilen; in der Wurzel -> einer geteilten Liste beitreten.
                        IconButton(onClick = { if (container != null) onOpenShare(container) else showAddShared = true }, modifier = Modifier.tag("topbar:share")) {
                            Icon(Icons.Filled.QrCode2, contentDescription = if (container != null) "Diese Liste teilen" else "Geteilte Liste beitreten")
                        }
                        IconButton(onClick = onOpenSettings, modifier = Modifier.tag("topbar:settings")) { Icon(Icons.Filled.Settings, contentDescription = "Einstellungen") }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!searching && canWrite) {
                Box {
                    // Eigene FAB-Fläche statt FloatingActionButton: dessen interne onClick-Clickable
                    // würde sonst den Long-Press schlucken.
                    Surface(
                        modifier = Modifier.size(56.dp).tag("fab:add").combinedClickable(
                            onClick = { startCreate(defaultKind) },
                            onLongClick = { fabMenu = true },
                        ),
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shadowElevation = 6.dp,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Add, contentDescription = "Hinzufügen (lang drücken für Typ)", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                    DropdownMenu(expanded = fabMenu, onDismissRequest = { fabMenu = false }) {
                        for (k in CREATE_KINDS) {
                            DropdownMenuItem(
                                leadingIcon = { Icon(k.uiIcon(), contentDescription = null) },
                                text = { Text(k.uiLabel() + if (k == defaultKind) "  (Standard)" else "") },
                                onClick = { fabMenu = false; startCreate(k) },
                                modifier = Modifier.tag("menu:create:" + k.name.lowercase()),
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (isCalendar) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("In Android-Kalender übernehmen", Modifier.weight(1f))
                    Switch(checked = calEnabled, onCheckedChange = { calEnabled = it; settings.setCalendarFeedEnabled(parentId, it); onRequestCalendarSync() })
                }
            }
            container?.takeIf { it.isForeign }?.let { f ->
                val rightLabel = when (f.foreignRight) {
                    de.beardedskunk.homeshare.data.FeedRight.READ -> "nur lesen"
                    de.beardedskunk.homeshare.data.FeedRight.WRITE -> "lesen & schreiben"
                    de.beardedskunk.homeshare.data.FeedRight.MERGE -> "lesen, schreiben, mergen"
                }
                Text("🔗 Geteilt von „${f.foreignOrigin}“ · $rightLabel", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (searching && query.isNotBlank()) "Keine Treffer." else if (isRoot) "Noch keine Feeds. Mit + einen anlegen." else "Noch keine Einträge.")
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.nodeId }) { node ->
                        when (node.kind) {
                            NodeKind.NOTE -> PostRow(
                                post = node, imageHashes = postImages[node.nodeId] ?: emptyList(), blobStore = blobStore, canMerge = canMerge,
                                onClick = { openChild(node) }, onResolveWhole = { resolving = node }, onResolveDetailed = { resolvingDetailed = node }, onOpenImage = { viewingImage = it },
                            )
                            NodeKind.CALENDAR -> CalendarRow(post = node, onClick = { openChild(node) })
                            else -> NodeRow(node = node, onClick = { openChild(node) }, onLongClick = { actionNode = node })
                        }
                    }
                }
            }
        }
    }

    // ---- Dialoge ----
    if (showCreateList) {
        CreateListDialog(
            defaultChild = if (isRoot) NodeKind.LIST else defaultKind,
            onConfirm = { name, childDefault ->
                showCreateList = false
                if (name.isNotBlank()) scope.launch { withContext(Dispatchers.IO) { repo.createList(name, parentId, childDefault) } }
            },
            onDismiss = { showCreateList = false },
        )
    }

    if (showAddShared) {
        AddSharedFeedDialog(sync = sync, onDone = { showAddShared = false; reload() }, onDismiss = { showAddShared = false })
    }

    actionNode?.let { node ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { actionNode = null },
            title = { Text((node.kind.uiLabel()) + ": " + node.title.ifBlank { "(ohne Namen)" }) },
            text = {
                Column {
                    if (node.isForeign) {
                        TextButton(onClick = { val id = node.nodeId; actionNode = null; scope.launch { withContext(Dispatchers.IO) { repo.leaveForeignFeed(id) }; reload() } }, modifier = Modifier.tag("action:leave")) { Text("Freigabe verlassen (lokal entfernen)") }
                    } else {
                        if (isRoot && node.kind == NodeKind.LIST) {
                            TextButton(onClick = { val n = node; actionNode = null; onOpenShare(n) }, modifier = Modifier.tag("action:share")) { Text("Mit Gruppe teilen / Freigaben…") }
                        }
                        TextButton(onClick = { val id = node.nodeId; actionNode = null; scope.launch { withContext(Dispatchers.IO) { repo.deleteNode(id) }; reload() } }, modifier = Modifier.tag("action:delete")) { Text("Löschen") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { actionNode = null }, modifier = Modifier.tag("action:cancel")) { Text("Abbrechen") } },
        )
    }
}

/**
 * Generische Zeile für Listen + Platzhalter-Einträge (Aufgabe/Bild/Datei). Nur **Listen** tragen ein
 * Icon, und zwar das ihres Default-Kindtyps ([NodeState.childDefault]) — also z. B. das Termin-Symbol
 * für eine Kalender-Liste. Einzel-Einträge bekommen (wie Notizen/Termine) kein Icon.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NodeRow(node: NodeState, onClick: () -> Unit, onLongClick: () -> Unit) {
    val leading = if (node.kind == NodeKind.LIST) (node.childDefault ?: NodeKind.LIST).uiIcon() else null
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .tag(rowTag(node.title))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = if (node.conflicted) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer) else CardDefaults.cardColors(),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) Icon(leading, contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            val extra = if (node.kind == NodeKind.LIST && FeedShareCodec.isShared(node.text)) "📤 " else ""
            Text(
                extra + node.title.ifBlank { if (node.kind == NodeKind.IMAGE) "Bild" else "(ohne Namen)" },
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = if (leading != null) 12.dp else 0.dp),
            )
        }
    }
}

/** Einzeiliger Notiz-Eintrag: erste Textzeile, rechts bis zu drei Mini-Thumbnails der Bild-Kindknoten. */
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
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .tag(rowTag(if (post.deleted) "(gelöscht)" else post.text))
            .combinedClickable(onClick = onClick, onLongClick = { if (post.conflicted && canMerge) menuOpen = true }),
        colors = if (post.conflicted) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer) else CardDefaults.cardColors(),
    ) {
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text("Ganze Fassung wählen") }, onClick = { menuOpen = false; onResolveWhole() })
            DropdownMenuItem(text = { Text("Im Detail zusammenführen") }, onClick = { menuOpen = false; onResolveDetailed() })
        }
        Row(Modifier.fillMaxWidth().height(rowHeight), verticalAlignment = Alignment.CenterVertically) {
            // Einzel-Einträge (Notizen) tragen bewusst KEIN Typ-Icon (konsistent zu Terminen).
            val raw = if (post.deleted) "(gelöscht)" else post.text
            val firstLine = raw.lineSequence().firstOrNull().orEmpty().ifBlank { if (imageHashes.isNotEmpty()) "🖼 Bild" else "" }
            Text(
                text = (if (post.conflicted) "⚠ " else "") + firstLine, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 14.dp, end = 10.dp),
            )
            val tasks = if (post.deleted) null else taskCounts(post.text)
            if (tasks != null) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.padding(end = 8.dp)) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(" ${tasks.first}/${tasks.second}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
            val thumbCount = if (tasks != null) 1 else 3
            for (sha in imageHashes.take(thumbCount)) {
                val bmp = rememberBlobBitmap(blobStore, sha, preferFull = false)
                Box(Modifier.fillMaxHeight().aspectRatio(1f).clickable { onOpenImage(sha) }, contentAlignment = Alignment.Center) {
                    if (bmp != null) Image(bitmap = bmp, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Text("🖼")
                }
            }
        }
    }
}

/** Anlege-Dialog für eine Liste: Name + Default-Kindtyp (ersetzt den alten Kalender-Toggle). */
@Composable
fun CreateListDialog(
    defaultChild: NodeKind = NodeKind.LIST,
    onConfirm: (name: String, childDefault: NodeKind) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var childDefault by remember { mutableStateOf(defaultChild) }
    var menuOpen by remember { mutableStateOf(false) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Neue Liste", fontWeight = FontWeight.Bold)
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                Text("Standard-Eintrag", style = MaterialTheme.typography.labelLarge)
                Box {
                    androidx.compose.material3.OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(childDefault.uiIcon(), contentDescription = null, modifier = Modifier.size(20.dp))
                        Text("  " + childDefault.uiLabel(), modifier = Modifier.weight(1f).padding(start = 8.dp))
                        Text("▾")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        for (k in CREATE_KINDS) {
                            DropdownMenuItem(leadingIcon = { Icon(k.uiIcon(), contentDescription = null) }, text = { Text(k.uiLabel()) }, onClick = { childDefault = k; menuOpen = false })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("Abbrechen") }
                    androidx.compose.material3.Button(onClick = { onConfirm(name, childDefault) }) { Text("Anlegen") }
                }
            }
        }
    }
}
