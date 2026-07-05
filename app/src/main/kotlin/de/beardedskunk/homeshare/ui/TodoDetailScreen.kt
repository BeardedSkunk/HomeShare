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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCode2
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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.ui.text.input.TextFieldValue
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
    onOpenShare: ((NodeState) -> Unit)? = null,
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

    // ---- In-Place-Edit: Titel+Body in einem Quelltext-Feld ----
    var bodySource by remember { mutableStateOf(false) }
    var bodyExpanded by remember { mutableStateOf(true) }
    var editTfv by remember { mutableStateOf(TextFieldValue("")) }

    fun saveBody() {
        scope.launch {
            withContext(Dispatchers.IO) {
                repo.headContent(node.nodeId)?.let { repo.editNode(node.nodeId, it.copy(text = editTfv.text)) }
            }
            bodySource = false
        }
    }

    // ---- Suche im Body ----
    var findQuery by remember { mutableStateOf<String?>(null) }
    var matchIdx by remember { mutableStateOf(0) }
    val q = findQuery?.takeIf { it.isNotBlank() }
    val matches = remember(node.text, q) { if (q == null) emptyList() else findAllMatches(node.text, q) }
    LaunchedEffect(findQuery) { matchIdx = 0 }
    fun stepMatch(delta: Int) {
        if (matches.isEmpty()) return
        matchIdx = ((matchIdx + delta) % matches.size + matches.size) % matches.size
    }

    // ---- Modale Unteransichten ----
    var subTodo by remember { mutableStateOf<NodeState?>(null) }
    var subNote by remember { mutableStateOf<NodeState?>(null) }
    var calEdit by remember { mutableStateOf<NodeState?>(null) }
    var attOpen by remember { mutableStateOf<NodeState?>(null) }

    attOpen?.let { a ->
        BackHandler { attOpen = null }
        AttachmentDetailScreen(repo = repo, blobStore = blobStore, attachment = a, readOnly = readOnly, onOpenShare = onOpenShare, onClose = { attOpen = null })
        return
    }
    subTodo?.let { t ->
        BackHandler { subTodo = null }
        TodoDetailScreen(repo = repo, blobStore = blobStore, todo = t, settings = settings, onOpenShare = onOpenShare, onRequestCalendarSync = onRequestCalendarSync, readOnly = readOnly, onClose = { subTodo = null })
        return
    }
    subNote?.let { n ->
        BackHandler { subNote = null }
        PostDetailEditor(
            repo = repo, blobStore = blobStore, parentId = node.nodeId, post = n,
            readOnly = readOnly, onOpenShare = onOpenShare, onClose = { subNote = null },
        )
        return
    }
    calEdit?.let { c ->
        BackHandler { calEdit = null }
        CalendarEntryEditor(repo = repo, blobStore = blobStore, parentId = node.nodeId, post = c, settings = settings, onOpenShare = onOpenShare, onRequestCalendarSync = onRequestCalendarSync, onClose = { calEdit = null })
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

    // Links-Swipe -> stehende Mülltonne; ein Zustand für Markdown-Zeilen, Unterpunkte und Anhänge.
    var openTrash by remember { mutableStateOf<String?>(null) }
    val subDrag = rememberColumnDragState()

    fun deleteChild(id: String) {
        if (readOnly) return
        openTrash = null
        scope.launch { withContext(Dispatchers.IO) { repo.deleteNode(id) } }
    }

    val listState = rememberLazyListState()
    val mdDrag = rememberMdLineDragState()
    val hasBody = postBody(node.text).isNotBlank()
    val bodyStyle = MaterialTheme.typography.bodyLarge

    Scaffold(
        topBar = {
            DetailTopBar(
                onBack = onClose,
                searchOpen = findQuery != null,
                onToggleSearch = { findQuery = if (findQuery == null) "" else null },
                onShare = null,
                menuContent = if (!readOnly || onOpenShare != null) { dismiss ->
                    if (onOpenShare != null) {
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Filled.QrCode2, contentDescription = null) },
                            text = { Text("Mit Gruppe teilen / Freigaben…") },
                            onClick = { dismiss(); onOpenShare(node) },
                            modifier = Modifier.tag("menu:share"),
                        )
                    }
                    if (!readOnly) {
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            text = { Text("Aufgabe löschen") },
                            onClick = {
                                dismiss()
                                scope.launch { withContext(Dispatchers.IO) { repo.deleteNode(node.nodeId) }; onClose() }
                            },
                            modifier = Modifier.tag("menu:delete-todo"),
                        )
                    }
                } else null,
                sourceMode = bodySource,
                onEditToggle = if (!readOnly) {
                    { if (bodySource) saveBody() else { editTfv = TextFieldValue(node.text); bodySource = true } }
                } else null,
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
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            // Suchleiste fix oben (ausserhalb des Scrolls).
            if (findQuery != null) {
                FindBar(
                    query = findQuery ?: "",
                    onQuery = { findQuery = it; matchIdx = 0 },
                    label = if (matches.isEmpty()) "0/0" else "${matchIdx + 1}/${matches.size}",
                    hasMatches = matches.isNotEmpty(),
                    onPrev = { stepMatch(-1) },
                    onNext = { stepMatch(1) },
                )
            }
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 12.dp).imePadding(),
                state = listState,
                contentPadding = PaddingValues(bottom = ATTACHMENT_FAB_CLEARANCE),
            ) {
                if (bodySource) {
                    // Quelltext-Modus: Titel+Body in einem gemeinsamen Editierfeld.
                    // Unterpunkte/Anhänge/Termine darunter weiter sichtbar – das ist der Fix.
                    item(key = "edit") {
                        MarkdownEditField(
                            value = editTfv,
                            onValueChange = { editTfv = it },
                            fieldModifier = Modifier.tag("field:todobody"),
                            minLines = 3,
                        )
                    }
                } else {
                    // Render-Modus Kopf: Checkbox + Titel + optionaler Chevron
                    item(key = "head") {
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = node.done,
                                onCheckedChange = { setDone(node.nodeId, it) },
                                enabled = !readOnly,
                                modifier = Modifier.tag("todo:done"),
                            )
                            Text(
                                highlightedText(node.title.ifBlank { "(ohne Titel)" }, q, null),
                                style = MaterialTheme.typography.headlineSmall,
                                textDecoration = if (node.done) TextDecoration.LineThrough else null,
                                modifier = Modifier.weight(1f),
                            )
                            if (hasBody) {
                                ExpandChevron(expanded = bodyExpanded, onToggle = { bodyExpanded = !bodyExpanded })
                            }
                        }
                    }
                    // Render-Modus Body: Blöcke mit Drag/Swipe (falls ausgeklappt)
                    if (bodyExpanded && hasBody) {
                        markdownBlockItems(
                            blocks = parseMarkdownBody(node.text),
                            listState = listState,
                            drag = mdDrag,
                            bodyStyle = bodyStyle,
                            firstItemIndex = 1,
                            onToggleTask = ::toggleBodyTask,
                            onEditAt = if (!readOnly) { _ ->
                                editTfv = TextFieldValue(node.text); bodySource = true
                            } else { _ -> },
                            onMoveLine = if (!readOnly) { from, to ->
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        repo.headContent(node.nodeId)?.let { hc ->
                                            repo.editNode(node.nodeId, hc.copy(text = moveLineTo(hc.text, from, to)))
                                        }
                                    }
                                }
                            } else null,
                            onDeleteLine = if (!readOnly) { line ->
                                openTrash = null
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        repo.headContent(node.nodeId)?.let { hc ->
                                            repo.editNode(node.nodeId, hc.copy(text = deleteLineWithChildren(hc.text, line)))
                                        }
                                    }
                                }
                            } else null,
                            openTrashKey = openTrash,
                            onOpenTrash = if (!readOnly) ({ openTrash = it }) else null,
                            highlight = q,
                            currentRangeFor = { null },
                        )
                    }
                }
                item(key = "subitems") {
                    // Unterpunkte: visuell separiert vom Rest (eigener Kasten).
                    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp).tag("box:subitems")) {
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
                            horizontalPadding = 0.dp,
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
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                    for (e in events) {
                        item(key = e.nodeId) { CalendarRow(post = e, onClick = { calEdit = e }) }
                    }
                }
            }
        }
    }
}
