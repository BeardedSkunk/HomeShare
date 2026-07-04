package de.beardedskunk.homeshare.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
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
import de.beardedskunk.homeshare.data.childTaskCounts
import de.beardedskunk.homeshare.sync.SyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ListScreenData(
    val list: List<NodeState>,
    val imgs: Map<String, List<String>>,
    val badges: Map<String, Pair<Int, Int>>,
    val captions: Map<String, String>,
)

/** Einheitliche Höhe aller Item-Zeilen (Notiz/Liste/Bild/Datei/Aufgabe) — Referenz war das Notiz-Item. */
private val ROW_HEIGHT = 56.dp

/** Standard-Icon je Nutzer-Typ (echte Material-Icons, via material-icons-extended). */
fun NodeKind.uiIcon(): ImageVector = when (this) {
    NodeKind.LIST -> Icons.AutoMirrored.Filled.ListAlt
    NodeKind.NOTE -> Icons.AutoMirrored.Filled.Notes
    NodeKind.CALENDAR -> Icons.Filled.CalendarMonth
    NodeKind.TODO -> Icons.Filled.TaskAlt
    NodeKind.IMAGE -> Icons.Filled.Image
    NodeKind.FILE -> Icons.AutoMirrored.Filled.InsertDriveFile
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
    // Fortschritts-Badge (erledigt/gesamt) je Aufgabe bzw. Aufgaben-Liste (childDefault==TODO).
    var taskBadges by remember { mutableStateOf<Map<String, Pair<Int, Int>>>(emptyMap()) }
    // Caption-Titel für IMAGE/FILE-Direktkinder (aus Beschreibungs-Notiz des Anhangs).
    var captionTitles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // Hamburger-Überlaufmenü (Liste löschen + Kalender-Sync-Toggle).
    var overflowOpen by remember { mutableStateOf(false) }
    var deleteListConfirm by remember { mutableStateOf(false) }
    var matchedIds by remember { mutableStateOf<Set<String>?>(null) }
    val searching = searchQuery != null
    val query = searchQuery ?: ""

    // Editor-/Dialog-Zustände
    var noteEdit by remember { mutableStateOf<NodeState?>(null) }
    // Listen-Beschreibung (Titel+Markdown-Body der Liste) lesen/bearbeiten – ohne Anhänge.
    var descEdit by remember { mutableStateOf<NodeState?>(null) }
    var todoOpen by remember { mutableStateOf<NodeState?>(null) }
    var showCreateTodo by remember { mutableStateOf(false) }
    var attOpen by remember { mutableStateOf<NodeState?>(null) }
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

    // Render-Kopf der Liste (wie Notiz): Edit-/Ausklapp-Zustand hier gehalten, Toggle sitzt in der Top-Bar.
    var headerSource by remember(container?.nodeId) { mutableStateOf(false) }   // false = gerendert, true = Quelltext
    var headerExpanded by remember(container?.nodeId) { mutableStateOf(false) } // Body eingeklappt starten
    var headerText by remember(container?.nodeId, container?.text) { mutableStateOf(container?.text ?: "") }

    val revision by repo.revision.collectAsState()

    fun reload() {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val list = repo.children(parentId)
                // Bilder-Map: NOTE -> Bild-Kinder; IMAGE -> eigener Blob; Bilder-Liste -> Bild-Kinder.
                val imgs = list.associate { p ->
                    p.nodeId to when {
                        p.kind == NodeKind.NOTE -> repo.children(p.nodeId).mapNotNull { c -> if (c.type == NodeType.IMAGE) c.blobHash else null }
                        p.kind == NodeKind.IMAGE && p.blobHash != null -> listOf(p.blobHash)
                        p.kind == NodeKind.LIST && p.childDefault == NodeKind.IMAGE -> repo.children(p.nodeId).mapNotNull { c -> if (c.type == NodeType.IMAGE) c.blobHash else null }
                        else -> emptyList()
                    }
                }.filterValues { it.isNotEmpty() }
                // Badge nur für Aufgaben und Aufgaben-Listen; gezählt werden TODO/NOTE-Unterpunkte.
                val badges = list
                    .filter { it.kind == NodeKind.TODO || (it.kind == NodeKind.LIST && it.childDefault == NodeKind.TODO) }
                    .mapNotNull { p -> childTaskCounts(repo.children(p.nodeId))?.let { p.nodeId to it } }
                    .toMap()
                // Caption-Titel für IMAGE/FILE-Direktkinder (Beschreibungs-Notiz des Anhangs).
                val captions = loadAttachmentRows(repo, parentId).associate { it.node.nodeId to it.label() }
                ListScreenData(list, imgs, badges, captions)
            }
            children = result.list
            postImages = result.imgs
            taskBadges = result.badges
            captionTitles = result.captions
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

    // ---- Drag&Drop-Umsortierung (Handle am Zeilenende) ----
    val listState = rememberLazyListState()
    // Vorschau-Reihenfolge während des Zugs; committed wird erst beim Drop (1 Op).
    var dragPreview by remember { mutableStateOf<List<NodeState>?>(null) }
    val dragState = rememberDragDropState(
        listState,
        onMove = { from, to ->
            val cur = (dragPreview ?: shown).toMutableList()
            if (from in cur.indices && to in cur.indices) {
                cur.add(to, cur.removeAt(from))
                dragPreview = cur
            }
        },
        onDrop = { _, to ->
            dragPreview?.takeIf { to in it.indices }?.let { cur ->
                val moved = cur[to]
                val prev = cur.getOrNull(to - 1)
                val next = cur.getOrNull(to + 1)
                // Revision-Bump löst den Reload aus; die Vorschau bleibt bis dahin stehen.
                scope.launch { withContext(Dispatchers.IO) { repo.reorderNode(moved.nodeId, prev, next) } }
            }
        },
    )
    // Vorschau verwerfen, sobald die echte Liste nachgezogen ist (oder der Drag folgenlos endete).
    LaunchedEffect(children) { if (!dragState.isDragging) dragPreview = null }
    LaunchedEffect(dragState.isDragging) {
        if (!dragState.isDragging && dragPreview != null) {
            kotlinx.coroutines.delay(500)
            dragPreview = null
        }
    }
    val displayed = dragPreview ?: shown
    val canDrag = canWrite && !searching
    // Links-Swipe -> stehende Mülltonne (max. eine offen); vertikaler Drag schließt sie.
    var openTrash by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(dragState.isDragging) { if (dragState.isDragging) openTrash = null }
    LaunchedEffect(parentId) { openTrash = null }

    fun shareImage(sha: String) {
        val file = if (blobStore.hasFull(sha)) blobStore.fullFile(sha) else blobStore.thumbFile(sha)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(send, "Bild teilen")) }
    }

    // Bild-/Datei-Anlage: echte Picker statt Platzhalter-Knoten. Der Knoten entsteht erst,
    // wenn wirklich etwas ausgewählt wurde.
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            withContext(Dispatchers.IO) { uris.forEach { AttachmentPicker.addImage(context, repo, blobStore, parentId, it) } }
            reload()
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            withContext(Dispatchers.IO) { AttachmentPicker.addFile(context, repo, blobStore, parentId, uri) }
            reload()
        }
    }

    fun startCreate(kind: NodeKind) {
        if (!canWrite) return
        when (kind) {
            NodeKind.LIST -> showCreateList = true
            NodeKind.NOTE -> creatingNote = true
            NodeKind.CALENDAR -> creatingCal = true
            NodeKind.TODO -> showCreateTodo = true
            NodeKind.IMAGE -> pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            NodeKind.FILE -> pickFile.launch(arrayOf("*/*"))
        }
    }

    fun openChild(p: NodeState) {
        when (p.kind) {
            NodeKind.LIST -> onOpenList(p)
            NodeKind.NOTE -> if (p.conflicted && canMerge) resolving = p else noteEdit = p
            NodeKind.CALENDAR -> calEdit = p
            NodeKind.TODO -> todoOpen = p
            // Eigenständige Anhänge in Listen öffnen dieselbe Detailansicht wie Anhänge
            // an Notizen/Aufgaben (Beschreibung + Bild/Datei).
            NodeKind.IMAGE, NodeKind.FILE -> attOpen = p
        }
    }

    // Speichert den bearbeiteten Kopf-Text (Typ bleibt unangetastet) und kehrt in die Render-Ansicht zurück.
    fun saveHeader() {
        val t = headerText
        val id = container?.nodeId ?: return
        scope.launch { withContext(Dispatchers.IO) { repo.headContent(id)?.let { repo.editNode(id, it.copy(text = t)) } } }
        headerSource = false
        headerExpanded = false
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
    attOpen?.let { a ->
        BackHandler { attOpen = null; reload() }
        AttachmentDetailScreen(repo = repo, blobStore = blobStore, attachment = a, readOnly = !canWrite, onClose = { attOpen = null; reload() })
        return
    }
    todoOpen?.let { t ->
        BackHandler { todoOpen = null; reload() }
        TodoDetailScreen(repo = repo, blobStore = blobStore, todo = t, readOnly = !canWrite, onClose = { todoOpen = null; reload() })
        return
    }
    descEdit?.let { d ->
        BackHandler { descEdit = null; reload() }
        PostDetailEditor(
            repo = repo, blobStore = blobStore, parentId = parentId, post = d,
            readOnly = !canWrite, showAttachments = false,
            onClose = { descEdit = null; reload() },
        )
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
        CalendarEntryEditor(repo = repo, blobStore = blobStore, parentId = parentId, post = calEdit, onClose = { calEdit = null; creatingCal = false; reload() })
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
                    }
                    // Nicht-Root: kein Titeltext mehr — der Titel steht jetzt im Render-Kopf darunter.
                },
                navigationIcon = {
                    if (!isRoot) BackIconButton(onClick = onBack)
                },
                actions = {
                    // Lupe (Suche) — immer.
                    IconButton(onClick = { onSearchQueryChange(if (searching) null else "") }, modifier = Modifier.tag("topbar:search")) {
                        Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, contentDescription = if (searching) "Suche schließen" else "Suchen")
                    }
                    if (!searching) {
                        // QR: in einer Liste diese teilen; in der Wurzel einer geteilten Liste beitreten.
                        IconButton(onClick = { if (container != null) onOpenShare(container) else showAddShared = true }, modifier = Modifier.tag("topbar:share")) {
                            Icon(Icons.Filled.QrCode2, contentDescription = if (container != null) "Diese Liste teilen" else "Geteilte Liste beitreten")
                        }
                        if (container != null) {
                            // Hamburger-Menü: „Liste löschen“ (+ bei Kalender-Listen der Sync-Toggle).
                            Box {
                                IconButton(onClick = { overflowOpen = true }, modifier = Modifier.tag("topbar:overflow")) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "Weitere Aktionen")
                                }
                                DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                                    if (canWrite) {
                                        DropdownMenuItem(
                                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                            text = { Text("Liste löschen") },
                                            onClick = { overflowOpen = false; deleteListConfirm = true },
                                            modifier = Modifier.tag("menu:delete-list"),
                                        )
                                    }
                                    if (isCalendar) {
                                        DropdownMenuItem(
                                            text = { Text("Mit Android-Kalender synchronisieren") },
                                            trailingIcon = { Switch(checked = calEnabled, onCheckedChange = null) },
                                            onClick = {
                                                val newState = !calEnabled
                                                calEnabled = newState
                                                settings.setCalendarFeedEnabled(parentId, newState)
                                                onRequestCalendarSync()
                                                overflowOpen = false
                                            },
                                            modifier = Modifier.tag("menu:calendar-sync"),
                                        )
                                    }
                                }
                            }
                            // ✓/✎ — Kopf-Edit-Toggle (nur mit Schreibrecht): grüner Haken = bearbeiten, Stift = speichern.
                            if (canWrite) {
                                IconButton(
                                    onClick = { if (headerSource) saveHeader() else headerSource = true },
                                    modifier = Modifier.tag(if (headerSource) "topbar:save" else "topbar:edit"),
                                ) {
                                    if (headerSource) Icon(Icons.Filled.Edit, contentDescription = "Speichern & anzeigen")
                                    else Icon(Icons.Filled.Check, contentDescription = "Bearbeiten", tint = Color(0xFF2E7D32), modifier = Modifier.size(30.dp))
                                }
                            }
                        } else {
                            // Wurzel: allgemeine Einstellungen.
                            IconButton(onClick = onOpenSettings, modifier = Modifier.tag("topbar:settings")) { Icon(Icons.Filled.Settings, contentDescription = "Einstellungen") }
                        }
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
                        for (k in KindRules.allowedChildKinds(container?.kind)) {
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
            container?.takeIf { it.isForeign }?.let { f ->
                val rightLabel = when (f.foreignRight) {
                    de.beardedskunk.homeshare.data.FeedRight.READ -> "nur lesen"
                    de.beardedskunk.homeshare.data.FeedRight.WRITE -> "lesen & schreiben"
                    de.beardedskunk.homeshare.data.FeedRight.MERGE -> "lesen, schreiben, mergen"
                }
                Text("🔗 Geteilt von „${f.foreignOrigin}” · $rightLabel", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (container != null) {
                ListHeader(
                    container = container,
                    sourceMode = headerSource,
                    expanded = headerExpanded,
                    onExpandedChange = { headerExpanded = it },
                    editText = headerText,
                    onEditTextChange = { headerText = it },
                )
            }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (searching && query.isNotBlank()) "Keine Treffer." else if (isRoot) "Noch keine Feeds. Mit + einen anlegen." else "Noch keine Einträge.")
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    // Puffer unten, damit sich der letzte Eintrag über den FAB (56dp) schieben lässt.
                    contentPadding = PaddingValues(bottom = 88.dp),
                ) {
                    itemsIndexed(displayed, key = { _, n -> n.nodeId }) { index, node ->
                        val handle: (@Composable () -> Unit)? =
                            if (canDrag) ({ DragHandle(dragState, index, node.title) }) else null
                        Box(
                            Modifier.dragDropItem(dragState, index)
                                .then(if (dragState.isDragging(index)) Modifier else Modifier.animateItem()),
                        ) {
                            val rowContent: @Composable () -> Unit = {
                                when (node.kind) {
                                    NodeKind.NOTE -> PostRow(
                                        post = node, imageHashes = postImages[node.nodeId] ?: emptyList(), blobStore = blobStore,
                                        onClick = { openChild(node) }, onLongClick = { actionNode = node }, onOpenImage = { viewingImage = it },
                                        trailing = handle,
                                    )
                                    NodeKind.CALENDAR -> CalendarRow(post = node, onClick = { openChild(node) }, onLongClick = { actionNode = node }, trailing = handle)
                                    NodeKind.TODO -> TodoRow(
                                        node = node, enabled = canWrite, badge = taskBadges[node.nodeId],
                                        onClick = { openChild(node) }, onLongClick = { actionNode = node },
                                        onDone = { done -> scope.launch { withContext(Dispatchers.IO) { repo.headContent(node.nodeId)?.let { repo.editNode(node.nodeId, it.copy(done = done)) } } } },
                                        trailing = handle,
                                    )
                                    else -> NodeRow(
                                        node = node, blobStore = blobStore, badge = taskBadges[node.nodeId],
                                        imageHashes = postImages[node.nodeId] ?: emptyList(),
                                        titleOverride = captionTitles[node.nodeId],
                                        onOpenImage = { viewingImage = it },
                                        onClick = { openChild(node) }, onLongClick = { actionNode = node }, trailing = handle,
                                    )
                                }
                            }
                            if (canDrag) {
                                SwipeRevealRow(
                                    key = node.nodeId, openKey = openTrash, onOpenChange = { openTrash = it },
                                    onDelete = {
                                        val id = node.nodeId
                                        scope.launch { withContext(Dispatchers.IO) { repo.deleteNode(id) }; reload() }
                                    },
                                ) { rowContent() }
                            } else {
                                rowContent()
                            }
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

    if (showCreateTodo) {
        CreateTodoDialog(
            onConfirm = { title ->
                showCreateTodo = false
                if (title.isNotBlank()) scope.launch {
                    val opened = withContext(Dispatchers.IO) {
                        val v = repo.createNode(NodeContent(parentId = parentId, type = NodeType.TODO, text = title.trim()))
                        repo.getPostState(v.nodeId)
                    }
                    reload()
                    todoOpen = opened
                }
            },
            onDismiss = { showCreateTodo = false },
        )
    }

    actionNode?.let { node ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { actionNode = null },
            title = { Text((node.kind.uiLabel()) + ": " + node.title.ifBlank { "(ohne Namen)" }) },
            text = {
                Column {
                    if (node.kind == NodeKind.LIST) {
                        TextButton(onClick = { val n = node; actionNode = null; descEdit = n }, modifier = Modifier.tag("action:info")) { Text("Beschreibung anzeigen/bearbeiten") }
                    }
                    if (node.conflicted && canMerge && node.kind == NodeKind.NOTE) {
                        TextButton(onClick = { val n = node; actionNode = null; resolving = n }, modifier = Modifier.tag("action:resolve")) { Text("Konflikt: Ganze Fassung wählen") }
                        TextButton(onClick = { val n = node; actionNode = null; resolvingDetailed = n }, modifier = Modifier.tag("action:resolve-detail")) { Text("Konflikt: Im Detail zusammenführen") }
                    }
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

    if (deleteListConfirm) {
        AlertDialog(
            onDismissRequest = { deleteListConfirm = false },
            title = { Text("Liste löschen?") },
            text = { Text("Die Liste und ihr Inhalt werden entfernt.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteListConfirm = false
                    val id = container?.nodeId
                    if (id != null) scope.launch { withContext(Dispatchers.IO) { repo.deleteNode(id) }; onBack() }
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { deleteListConfirm = false }) { Text("Abbrechen") } },
        )
    }
}

/**
 * Generische Zeile für Listen + Einzel-Einträge (Aufgabe/Bild/Datei). **Listen** tragen das Icon
 * ihres Default-Kindtyps ([NodeState.childDefault]) — z. B. das Termin-Symbol für eine
 * Kalender-Liste. **Bilder** zeigen ein Mini-Thumbnail, **Dateien** ein Datei-Icon + Namen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NodeRow(
    node: NodeState,
    blobStore: BlobStore? = null,
    badge: Pair<Int, Int>? = null,
    imageHashes: List<String> = emptyList(),
    titleOverride: String? = null,
    onOpenImage: ((String) -> Unit)? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    // IMAGE-Knoten haben kein Leading-Icon (Bild erscheint als Strip rechts).
    val leading = when (node.kind) {
        NodeKind.LIST -> (node.childDefault ?: NodeKind.LIST).uiIcon()
        NodeKind.FILE -> NodeKind.FILE.uiIcon()
        else -> null
    }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .tag(rowTag(node.title)),
        colors = if (node.conflicted) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer) else CardDefaults.cardColors(),
    ) {
        // combinedClickable liegt NUR auf dem Inhalts-Wrapper (weight 1f), nicht auf der ganzen Card
        // -> Long-Press auf dem Ziehgriff (trailing, außerhalb) löst den Aktionsdialog nicht mehr aus.
        // Feste Zeilenhöhe (ROW_HEIGHT) für einheitliche Höhe mit dem Notiz-Item.
        Row(Modifier.fillMaxWidth().height(ROW_HEIGHT).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).fillMaxHeight().combinedClickable(onClick = onClick, onLongClick = onLongClick),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leading != null) {
                    Icon(leading, contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                }
                val extra = if (node.kind == NodeKind.LIST && FeedShareCodec.isShared(node.text)) "📤 " else ""
                val displayTitle = titleOverride ?: node.title.ifBlank { if (node.kind == NodeKind.IMAGE) "Bild" else "(ohne Namen)" }
                Text(
                    extra + displayTitle,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = if (leading != null) 12.dp else 0.dp),
                )
                if (badge != null) TaskBadge(badge.first, badge.second)
            }
            if (imageHashes.isNotEmpty() && blobStore != null && onOpenImage != null) {
                RowImageStrip(imageHashes, blobStore, ROW_HEIGHT, onOpenImage)
            }
            trailing?.invoke()
        }
    }
}

/** Fortschritts-Badge „✓ x/y" für Aufgaben und Aufgaben-Listen (gezählt: TODO/NOTE-Unterpunkte). */
@Composable
private fun TaskBadge(done: Int, total: Int) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.padding(end = 8.dp)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(" $done/$total", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

/** Aufgaben-Zeile: Haken direkt abhakbar (ohne Öffnen), Tap öffnet die Aufgaben-Ansicht. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TodoRow(node: NodeState, enabled: Boolean, badge: Pair<Int, Int>? = null, onClick: () -> Unit, onLongClick: () -> Unit, onDone: (Boolean) -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .tag(rowTag(node.title)),
        colors = if (node.conflicted) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer) else CardDefaults.cardColors(),
    ) {
        Row(Modifier.fillMaxWidth().height(ROW_HEIGHT).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            // Haken bleibt außerhalb des klickbaren Wrappers (direkt abhakbar, kein Öffnen).
            Checkbox(checked = node.done, onCheckedChange = onDone, enabled = enabled)
            Row(
                Modifier.weight(1f).fillMaxHeight().combinedClickable(onClick = onClick, onLongClick = onLongClick),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    node.title.ifBlank { "(ohne Titel)" },
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textDecoration = if (node.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                    modifier = Modifier.weight(1f),
                )
                if (badge != null) TaskBadge(badge.first, badge.second)
            }
            trailing?.invoke()
        }
    }
}

/** Kleiner Anlege-Dialog für Aufgaben: nur der Titel, danach öffnet sich die Aufgaben-Ansicht. */
@Composable
private fun CreateTodoDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Neue Aufgabe", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = title, onValueChange = { title = it }, label = { Text("Titel") },
                    singleLine = true, modifier = Modifier.tag("field:todo-title"),
                )
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("Abbrechen") }
                    androidx.compose.material3.Button(onClick = { onConfirm(title) }, modifier = Modifier.tag("dialog:create-todo")) { Text("Anlegen") }
                }
            }
        }
    }
}

/** Einzeiliger Notiz-Eintrag: erste Textzeile, rechts der responsive Bild-Streifen der Bild-Kindknoten. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PostRow(
    post: NodeState,
    imageHashes: List<String>,
    blobStore: BlobStore,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onOpenImage: (String) -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .tag(rowTag(if (post.deleted) "(gelöscht)" else post.text)),
        colors = if (post.conflicted) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer) else CardDefaults.cardColors(),
    ) {
        Row(Modifier.fillMaxWidth().height(ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
            // Einzel-Einträge (Notizen) tragen bewusst KEIN Typ-Icon (konsistent zu Terminen).
            val raw = if (post.deleted) "(gelöscht)" else post.text
            val firstLine = raw.lineSequence().firstOrNull().orEmpty().ifBlank { if (imageHashes.isNotEmpty()) "🖼 Bild" else "" }
            // combinedClickable nur auf dem Text-Wrapper -> Ziehgriff/Thumbnails bleiben außerhalb.
            Row(
                Modifier.weight(1f).fillMaxHeight().combinedClickable(onClick = onClick, onLongClick = onLongClick),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = (if (post.conflicted) "⚠ " else "") + firstLine, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 14.dp, end = 10.dp),
                )
            }
            // Notizen tragen bewusst KEINEN Markdown-Aufgaben-Zähler mehr (der Badge lebt jetzt an
            // Aufgaben/Aufgaben-Listen und zählt Unterpunkte statt Markdown-Checkboxen).
            RowImageStrip(imageHashes, blobStore, ROW_HEIGHT, onOpenImage)
            trailing?.invoke()
        }
    }
}

/**
 * Horizontaler Bildstreifen für Item-Zeilen: quadratische Thumbnails auf voller Zeilenhöhe
 * ([cellSize]). Breite ist auf die RECHTE Bildschirmhälfte begrenzt (nie in die linke Hälfte).
 * Ränder blenden weich aus, solange in die jeweilige Richtung weitergescrollt werden kann;
 * am ersten Bild ist der linke Rand scharf, am letzten der rechte.
 */
@Composable
private fun RowImageStrip(
    imageHashes: List<String>,
    blobStore: BlobStore,
    cellSize: androidx.compose.ui.unit.Dp,
    onOpenImage: (String) -> Unit,
) {
    if (imageHashes.isEmpty()) return
    val config = LocalConfiguration.current
    // Nie über die Bildschirmmitte hinaus (max. halbe Breite) und nochmal 10 % schlanker.
    val maxStripWidth = (config.screenWidthDp * 0.45f).dp
    val listState = rememberLazyListState()
    val fadeLeft by remember { derivedStateOf { listState.canScrollBackward } }
    val fadeRight by remember { derivedStateOf { listState.canScrollForward } }
    LazyRow(
        state = listState,
        modifier = Modifier
            .widthIn(max = maxStripWidth)
            .height(cellSize)
            .horizontalFadingEdges(fadeLeft, fadeRight),
    ) {
        items(imageHashes) { sha ->
            val bmp = rememberBlobBitmap(blobStore, sha, preferFull = false)
            Box(Modifier.size(cellSize).clickable { onOpenImage(sha) }, contentAlignment = Alignment.Center) {
                if (bmp != null) Image(bitmap = bmp, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Text("🖼")
            }
        }
    }
}

/** Weicher Rand-Ausblend links/rechts (nur wenn in die Richtung weiter gescrollt werden kann). */
private fun Modifier.horizontalFadingEdges(fadeLeft: Boolean, fadeRight: Boolean, fadeWidth: androidx.compose.ui.unit.Dp = 20.dp): Modifier =
    this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fw = fadeWidth.toPx()
            if (fadeLeft) {
                drawRect(
                    brush = Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = fw),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (fadeRight) {
                drawRect(
                    brush = Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - fw, endX = size.width),
                    blendMode = BlendMode.DstIn,
                )
            }
        }

/**
 * Render-Kopf der aktuellen Liste — sieht aus wie die Render-Ansicht einer Notiz (kein grauer Kasten):
 * Titel (headlineSmall) + optionales Ausklapp-Chevron; ausgeklappt der gerenderte Markdown-Body.
 * Im Quelltext-Modus (vom Top-Bar-Toggle gesteuert) eine gemeinsame Editbox (Titel + Body).
 */
@Composable
private fun ListHeader(
    container: NodeState,
    sourceMode: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    editText: String,
    onEditTextChange: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        if (sourceMode) {
            OutlinedTextField(
                value = editText,
                onValueChange = onEditTextChange,
                placeholder = { Text("Titel (1. Zeile), dann Markdown…") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth().tag("field:listbody"),
            )
        } else {
            val title = postTitle(container.text)
            val hasBody = postBody(container.text).isNotBlank()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title.ifBlank { "(ohne Namen)" },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f).tag("header:title"),
                )
                if (hasBody) {
                    IconButton(onClick = { onExpandedChange(!expanded) }, modifier = Modifier.tag("header:expand")) {
                        Icon(
                            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = if (expanded) "Einklappen" else "Ausklappen",
                        )
                    }
                }
            }
            if (expanded && hasBody) {
                MarkdownBody(text = container.text, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
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
                        for (k in KindRules.allowedChildKinds(NodeKind.LIST)) {
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
