package de.beardedskunk.homeshare.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.beardedskunk.homeshare.core.NodeContent
import de.beardedskunk.homeshare.core.NodeKind
import de.beardedskunk.homeshare.core.NodeType
import de.beardedskunk.homeshare.data.BlobStore
import de.beardedskunk.homeshare.data.FeedRepository
import de.beardedskunk.homeshare.data.NodeState
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
    readOnly: Boolean = false,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val revision by repo.revision.collectAsState()

    var node by remember { mutableStateOf(todo) }
    var kids by remember { mutableStateOf<List<NodeState>>(emptyList()) }
    LaunchedEffect(revision) {
        val fresh = withContext(Dispatchers.IO) {
            (repo.getPostState(todo.nodeId) ?: todo) to repo.children(todo.nodeId)
        }
        node = fresh.first
        kids = fresh.second
    }

    val subItems = kids.filter { it.kind == NodeKind.TODO || it.kind == NodeKind.NOTE }
    val attachments = kids.filter { it.kind == NodeKind.IMAGE || it.kind == NodeKind.FILE }
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
    var creatingNote by remember { mutableStateOf(false) }
    var calEdit by remember { mutableStateOf<NodeState?>(null) }
    var creatingCal by remember { mutableStateOf(false) }
    var viewingImage by remember { mutableStateOf<String?>(null) }

    viewingImage?.let { sha ->
        BackHandler { viewingImage = null }
        ImageViewerScreen(blobStore = blobStore, sha = sha, onBack = { viewingImage = null })
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
        TodoDetailScreen(repo = repo, blobStore = blobStore, todo = t, readOnly = readOnly, onClose = { subTodo = null })
        return
    }
    if (subNote != null || creatingNote) {
        BackHandler { subNote = null; creatingNote = false }
        PostDetailEditor(
            repo = repo, blobStore = blobStore, parentId = node.nodeId, post = subNote,
            readOnly = readOnly, onClose = { subNote = null; creatingNote = false },
        )
        return
    }
    if (calEdit != null || creatingCal) {
        BackHandler { calEdit = null; creatingCal = false }
        CalendarEntryEditor(repo = repo, parentId = node.nodeId, post = calEdit, onClose = { calEdit = null; creatingCal = false })
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

    var fabMenu by remember { mutableStateOf(false) }

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
            if (!readOnly) Box {
                FloatingActionButton(onClick = { fabMenu = true }, modifier = Modifier.tag("fab:add")) {
                    Icon(Icons.Filled.Add, contentDescription = "Hinzufügen")
                }
                DropdownMenu(expanded = fabMenu, onDismissRequest = { fabMenu = false }) {
                    DropdownMenuItem(
                        leadingIcon = { Icon(NodeKind.NOTE.uiIcon(), contentDescription = null) },
                        text = { Text(NodeKind.NOTE.uiLabel()) },
                        onClick = { fabMenu = false; creatingNote = true },
                        modifier = Modifier.tag("menu:create:note"),
                    )
                    DropdownMenuItem(
                        leadingIcon = { Icon(NodeKind.CALENDAR.uiIcon(), contentDescription = null) },
                        text = { Text(NodeKind.CALENDAR.uiLabel()) },
                        onClick = { fabMenu = false; creatingCal = true },
                        modifier = Modifier.tag("menu:create:calendar"),
                    )
                    DropdownMenuItem(
                        leadingIcon = { Icon(NodeKind.IMAGE.uiIcon(), contentDescription = null) },
                        text = { Text(NodeKind.IMAGE.uiLabel()) },
                        onClick = { fabMenu = false; pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        modifier = Modifier.tag("menu:create:image"),
                    )
                    DropdownMenuItem(
                        leadingIcon = { Icon(NodeKind.FILE.uiIcon(), contentDescription = null) },
                        text = { Text(NodeKind.FILE.uiLabel()) },
                        onClick = { fabMenu = false; pickFile.launch(arrayOf("*/*")) },
                        modifier = Modifier.tag("menu:create:file"),
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(bottom = 88.dp),
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
                        for (s in subItems) {
                            Row(
                                Modifier.fillMaxWidth()
                                    .tag(rowTag(s.title))
                                    .clickable { if (s.kind == NodeKind.TODO) subTodo = s else subNote = s }
                                    .padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // Auch Text-/Notiz-Unterpunkte tragen einen Haken (done gibt es auf jedem Knoten).
                                Checkbox(checked = s.done, onCheckedChange = { setDone(s.nodeId, it) }, enabled = !readOnly)
                                Text(
                                    s.title.ifBlank { "(ohne Titel)" },
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    textDecoration = if (s.done) TextDecoration.LineThrough else null,
                                    modifier = Modifier.weight(1f),
                                )
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
                    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).tag("box:attachments")) {
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Text(
                                "Anhänge",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                            for (a in attachments) {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .tag(rowTag(a.title.ifBlank { "Anhang" }))
                                        .clickable {
                                            if (a.kind == NodeKind.IMAGE) a.blobHash?.let { viewingImage = it }
                                            else AttachmentPicker.openExternally(context, blobStore, a)
                                        }
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (a.kind == NodeKind.IMAGE && a.blobHash != null) {
                                        val bmp = rememberBlobBitmap(blobStore, a.blobHash, preferFull = false)
                                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                                            if (bmp != null) Image(bitmap = bmp, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Text("🖼")
                                        }
                                    } else {
                                        Icon(NodeKind.FILE.uiIcon(), contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                                    }
                                    Text(
                                        a.title.ifBlank { if (a.kind == NodeKind.IMAGE) "Bild" else "Datei" },
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f).padding(start = 12.dp),
                                    )
                                }
                            }
                        }
                    }
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
