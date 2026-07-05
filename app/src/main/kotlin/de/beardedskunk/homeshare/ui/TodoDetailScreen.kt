package de.beardedskunk.homeshare.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import de.beardedskunk.homeshare.core.NodeContent
import de.beardedskunk.homeshare.core.NodeKind
import de.beardedskunk.homeshare.core.NodeType
import de.beardedskunk.homeshare.data.BlobStore
import de.beardedskunk.homeshare.data.FeedRepository
import de.beardedskunk.homeshare.data.NodeState
import de.beardedskunk.homeshare.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Einzelansicht einer Aufgabe: Haken + Titel, gerenderter Markdown-Body, darunter visuell
 * getrennt der **Unterpunkte**-Kasten (alle Sub-Items mit Haken, Quick-Add-Zeile), dann
 * **Anhänge** (Bilder/Dateien) und **Termine**. Im Knotenbaum sind Unterpunkte/Anhänge/
 * Termine schlicht Geschwister-Kinder der Aufgabe – die Gruppierung ist rein visuell.
 * Ohne Reminder/Due-Date (kommt später).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoDetailScreen(
    repo: FeedRepository,
    blobStore: BlobStore,
    todo: NodeState,
    settings: Settings,
    onRequestCalendarSync: () -> Unit = {},
    readOnly: Boolean = false,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val revision by repo.revision.collectAsState()

    var node by remember { mutableStateOf(todo) }
    var kids by remember { mutableStateOf<List<NodeState>>(emptyList()) }
    var attachments by remember { mutableStateOf<List<AttachmentRow>>(emptyList()) }
    LaunchedEffect(revision) {
        val fresh = withContext(Dispatchers.IO) {
            Triple(repo.getPostState(todo.nodeId) ?: todo, repo.children(todo.nodeId), loadAttachmentRows(repo, todo.nodeId))
        }
        node = fresh.first
        kids = fresh.second
        attachments = fresh.third
    }

    val subItems = kids.filter { it.kind == NodeKind.TODO || it.kind == NodeKind.NOTE }
    val events = kids.filter { it.kind == NodeKind.CALENDAR }

    fun setDone(id: String, done: Boolean) {
        if (readOnly) return
        scope.launch {
            withContext(Dispatchers.IO) { repo.headContent(id)?.let { repo.editNode(id, it.copy(done = done)) } }
        }
    }

    // ---- Modale Unteransichten ----
    var bodyEdit by remember { mutableStateOf(false) }
    var subTodo by remember { mutableStateOf<NodeState?>(null) }
    var subNote by remember { mutableStateOf<NodeState?>(null) }
    var calEdit by remember { mutableStateOf<NodeState?>(null) }
    var attOpen by remember { mutableStateOf<NodeState?>(null) }

    attOpen?.let { a ->
        BackHandler { attOpen = null }
        AttachmentDetailScreen(repo = repo, blobStore = blobStore, attachment = a, readOnly = readOnly, onClose = { attOpen = null })
        return
    }
    if (bodyEdit) {
        BackHandler { bodyEdit = false }
        PostDetailEditor(
            repo = repo, blobStore = blobStore, parentId = node.parentId, post = node,
            readOnly = readOnly, showAttachments = false, onClose = { bodyEdit = false },
        )
        return
    }
    subTodo?.let { t ->
        BackHandler { subTodo = null }
        TodoDetailScreen(repo = repo, blobStore = blobStore, todo = t, settings = settings, onRequestCalendarSync = onRequestCalendarSync, readOnly = readOnly, onClose = { subTodo = null })
        return
    }
    subNote?.let { n ->
        BackHandler { subNote = null }
        PostDetailEditor(
            repo = repo, blobStore = blobStore, parentId = node.nodeId, post = n,
            readOnly = readOnly, onClose = { subNote = null },
        )
        return
    }
    calEdit?.let { c ->
        BackHandler { calEdit = null }
        CalendarEntryEditor(repo = repo, blobStore = blobStore, parentId = node.nodeId, post = c, settings = settings, onShare = null, onRequestCalendarSync = onRequestCalendarSync, onClose = { calEdit = null })
        return
    }
    BackHandler { onClose() }

    // Anhänge direkt unter der Aufgabe anlegen.
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            withContext(Dispatchers.IO) { uris.forEach { AttachmentPicker.addImage(context, repo, blobStore, node.nodeId, it) } }
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            withContext(Dispatchers.IO) { AttachmentPicker.addFile(context, repo, blobStore, node.nodeId, uri) }
        }
    }

    var quickAdd by remember { mutableStateOf("") }
    fun addSubTask() {
        val t = quickAdd.trim()
        if (t.isEmpty() || readOnly) return
        quickAdd = ""
        scope.launch { withContext(Dispatchers.IO) { repo.createNode(NodeContent(parentId = node.nodeId, type = NodeType.TODO, text = t)) } }
    }

    fun toggleBodyTask(line: Int) {
        if (readOnly) return
        scope.launch {
            withContext(Dispatchers.IO) {
                repo.headContent(node.nodeId)?.let { hc ->
                    val lines = hc.text.split("\n").toMutableList()
                    if (line in lines.indices) {
                        lines[line] = flipTaskLine(lines[line])
                        repo.editNode(node.nodeId, hc.copy(text = lines.joinToString("\n")))
                    }
                }
            }
        }
    }

    // Links-Swipe -> stehende Mülltonne; ein Zustand für Unterpunkte UND Anhänge (max. eine offen).
    var openTrash by remember { mutableStateOf<String?>(null) }
    val subDrag = rememberColumnDragState()

    fun deleteChild(id: String) {
        if (readOnly) return
        openTrash = null
        scope.launch { withContext(Dispatchers.IO) { repo.deleteNode(id) } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { BackIconButton(onClick = onClose) },
                actions = {
                    if (!readOnly) {
                        IconButton(
                            onClick = { scope.launch { withContext(Dispatchers.IO) { repo.deleteNode(node.nodeId) }; onClose() } },
                            modifier = Modifier.tag("topbar:delete"),
                        ) { Icon(Icons.Filled.Delete, contentDescription = "Löschen") }
                        IconButton(onClick = { bodyEdit = true }, modifier = Modifier.tag("topbar:edit")) {
                            Icon(Icons.Filled.Edit, contentDescription = "Titel & Beschreibung bearbeiten")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            // Über den FAB gibt es bewusst NUR Anhänge (Bild + Datei); Unterpunkte laufen
            // über die Quick-Add-Zeile, Termine über die Liste selbst.
            if (!readOnly) AttachmentAddFab(
                onPickImages = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onPickFile = { pickFile.launch(arrayOf("*/*")) },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(bottom = ATTACHMENT_FAB_CLEARANCE),
        ) {
            item(key = "head") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = node.done,
                        onCheckedChange = { setDone(node.nodeId, it) },
                        enabled = !readOnly,
                        modifier = Modifier.tag("todo:done"),
                    )
                    Text(
                        node.title.ifBlank { "(ohne Titel)" },
                        style = MaterialTheme.typography.headlineSmall,
                        textDecoration = if (node.done) TextDecoration.LineThrough else null,
                    )
                }
            }
            if (postBody(node.text).isNotBlank()) {
                item(key = "body") {
                    // Gerenderter Markdown-Body; Haken antippbar, Tipp auf Text öffnet den Editor.
                    MarkdownBody(
                        text = node.text,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        onToggleTask = if (readOnly) null else ::toggleBodyTask,
                        onEditAt = if (readOnly) null else { _ -> bodyEdit = true },
                    )
                }
            }
            item(key = "subitems") {
                // Unterpunkte: visuell separiert vom Rest (eigener Kasten).
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).tag("box:subitems")) {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(
                            "Unterpunkte",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        for ((i, s) in subItems.withIndex()) {
                            val row: @Composable () -> Unit = {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .tag(rowTag(s.title))
                                        .columnDragItem(subDrag, i)
                                        .padding(start = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // Auch Text-/Notiz-Unterpunkte tragen einen Haken (done gibt es auf jedem Knoten).
                                    Checkbox(checked = s.done, onCheckedChange = { setDone(s.nodeId, it) }, enabled = !readOnly)
                                    // clickable nur auf dem Text -> Tap/Long-Press auf dem Ziehgriff navigiert nicht.
                                    Text(
                                        s.title.ifBlank { "(ohne Titel)" },
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        textDecoration = if (s.done) TextDecoration.LineThrough else null,
                                        modifier = Modifier.weight(1f).clickable { if (s.kind == NodeKind.TODO) subTodo = s else subNote = s },
                                    )
                                    if (!readOnly) {
                                        ColumnDragHandle(
                                            subDrag, i, s.title, subItems.size,
                                            onDragStart = { openTrash = null },
                                            onDrop = { from, to ->
                                                val cur = subItems.toMutableList()
                                                val moved = cur.removeAt(from)
                                                cur.add(to, moved)
                                                scope.launch { withContext(Dispatchers.IO) { repo.reorderNode(moved.nodeId, cur.getOrNull(to - 1), cur.getOrNull(to + 1)) } }
                                            },
                                        )
                                    }
                                }
                            }
                            if (!readOnly) {
                                SwipeRevealRow(
                                    key = s.nodeId, openKey = openTrash, onOpenChange = { openTrash = it },
                                    onDelete = { deleteChild(s.nodeId) },
                                ) { row() }
                            } else {
                                row()
                            }
                        }
                        if (!readOnly) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = quickAdd, onValueChange = { quickAdd = it },
                                    placeholder = { Text("Neuer Unterpunkt…") }, singleLine = true,
                                    modifier = Modifier.weight(1f).tag("field:subitem-add"),
                                )
                                IconButton(onClick = ::addSubTask, modifier = Modifier.tag("subitem:add")) {
                                    Icon(Icons.Filled.Add, contentDescription = "Unterpunkt hinzufügen")
                                }
                            }
                        }
                    }
                }
            }
            if (attachments.isNotEmpty()) {
                item(key = "attachments") {
                    AttachmentBox(
                        attachments, blobStore,
                        openTrashKey = openTrash,
                        onOpenTrash = if (readOnly) null else ({ openTrash = it }),
                        onDelete = if (readOnly) null else ({ a -> deleteChild(a.node.nodeId) }),
                        onReorder = if (readOnly) null else ({ moved, prev, next ->
                            scope.launch { withContext(Dispatchers.IO) { repo.reorderNode(moved.nodeId, prev, next) } }
                        }),
                        onOpen = { attOpen = it.node },
                    )
                }
            }
            if (events.isNotEmpty()) {
                item(key = "events-head") {
                    Text(
                        "Termine",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                for (e in events) {
                    item(key = e.nodeId) { CalendarRow(post = e, onClick = { calEdit = e }) }
                }
            }
        }
    }
}
