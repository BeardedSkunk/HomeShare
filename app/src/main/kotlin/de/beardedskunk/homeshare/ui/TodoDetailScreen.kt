package de.beardedskunk.homeshare.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Sell
import de.beardedskunk.homeshare.core.Tags
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.ui.graphics.compositeOver
import de.beardedskunk.homeshare.core.NodeContent
import de.beardedskunk.homeshare.core.NodeKind
import de.beardedskunk.homeshare.core.NodeType
import de.beardedskunk.homeshare.core.PrioBand
import de.beardedskunk.homeshare.core.Priority
import de.beardedskunk.homeshare.core.rekeyPlan
import de.beardedskunk.homeshare.core.resolveDrop
import de.beardedskunk.homeshare.data.BlobStore
import de.beardedskunk.homeshare.data.DueMoment
import de.beardedskunk.homeshare.data.FeedRepository
import de.beardedskunk.homeshare.data.NodeState
import de.beardedskunk.homeshare.data.PrioritySort
import de.beardedskunk.homeshare.data.Settings
import de.beardedskunk.homeshare.data.TaskRepeat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime

/** Ergebnis des revisions-gebundenen Ladens (Knoten, Kinder, Anhänge, Due-Zeitpunkte der Unterpunkte). */
private data class TodoLoad(
    val node: NodeState,
    val kids: List<NodeState>,
    val attachments: List<AttachmentRow>,
    val subDues: Map<String, DueMoment>,
)

/**
 * Einzelansicht einer Aufgabe: Haken + Titel, gerenderter Markdown-Body, darunter visuell
 * getrennt der **Unterpunkte**-Kasten (alle Sub-Items mit Haken, Quick-Add-Zeile), dann
 * **Anhänge** (Bilder/Dateien) und **Termine**. Im Knotenbaum sind Unterpunkte/Anhänge/
 * Termine schlicht Geschwister-Kinder der Aufgabe – die Gruppierung ist rein visuell.
 * **Due Date** = erster CALENDAR-Kindknoten (Fällig-Zeile, [DueRow]); die **Wiederholung**
 * lebt als ext-Meta direkt am Aufgabenknoten ([TaskRepeat], [RepeatDialog]) — beim Abhaken
 * bzw. verstrichenem Due Date legt das Repository eine Kopie an ([FeedRepository.setTaskDone]).
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
    onSearchTag: ((String) -> Unit)? = null,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val revision by repo.revision.collectAsState()

    // Undo-Anker = Aufgaben-Knoten (deckt Body, Unterpunkte, Anhänge und Termine ab).
    RegisterUndoAnchor(repo.undo, todo.nodeId)

    var tagPicker by remember { mutableStateOf(false) }
    var allTagsCache by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(tagPicker) {
        if (tagPicker) allTagsCache = withContext(Dispatchers.IO) { repo.allTags() }
    }

    var node by remember { mutableStateOf(todo) }
    var kids by remember { mutableStateOf<List<NodeState>>(emptyList()) }
    var attachments by remember { mutableStateOf<List<AttachmentRow>>(emptyList()) }
    // Due-Zeitpunkt je unerledigtem TODO-Unterpunkt (für Band-Sortierung/Farbe der Unterpunkte).
    var subDues by remember { mutableStateOf<Map<String, DueMoment>>(emptyMap()) }
    LaunchedEffect(revision) {
        val fresh = withContext(Dispatchers.IO) {
            val n = repo.getPostState(todo.nodeId) ?: todo
            val k = repo.children(todo.nodeId)
            val a = loadAttachmentRows(repo, todo.nodeId)
            val sd = k.filter { it.kind == NodeKind.TODO && !it.done }
                .mapNotNull { p ->
                    TaskRepeat.dueChild(repo.children(p.nodeId))?.let { PrioritySort.dueMoment(it) }?.let { p.nodeId to it }
                }.toMap()
            TodoLoad(n, k, a, sd)
        }
        node = fresh.node
        kids = fresh.kids
        attachments = fresh.attachments
        subDues = fresh.subDues
    }

    val subItems = kids.filter { it.kind == NodeKind.TODO || it.kind == NodeKind.NOTE }
    val now = remember(revision) { LocalDateTime.now() }
    val prioSort = PrioritySort.enabled(node)
    val subShown = if (prioSort) PrioritySort.displaySort(subItems, subDues, now) else subItems
    // Eigenes Band der Aufgabe (für die Tönung von Kopf/Kästen); erledigt → automatisch KEINE.
    val ownBand = PrioritySort.bandOf(node, TaskRepeat.dueChild(kids)?.let { PrioritySort.dueMoment(it) }, now)
    var prioPick by remember { mutableStateOf(false) }
    val events = kids.filter { it.kind == NodeKind.CALENDAR }
    // Erster Datumsknoten = Due Date (Fällig-Zeile); weitere sind Altbestand und bleiben gelistet.
    val dueEvent = TaskRepeat.dueChild(kids)
    val otherEvents = events.filterNot { it.nodeId == dueEvent?.nodeId }
    val repeatRule = TaskRepeat.rule(node.ext)

    fun setDone(id: String, done: Boolean) {
        if (readOnly) return
        // Über setTaskDone, damit der Repeater greift (Kopie beim Abhaken, Rücknahme beim Enthaken).
        scope.launch { withContext(Dispatchers.IO) { repo.setTaskDone(id, done) } }
    }

    // ---- In-Place-Edit: Titel+Body in einem Quelltext-Feld ----
    var bodySource by remember { mutableStateOf(false) }
    var bodyExpanded by remember { mutableStateOf(true) }
    var editTfv by remember { mutableStateOf(TextFieldValue("")) }

    // Persistiert Titel+Body (dank editNode-Guard gratis, wenn nichts geändert).
    fun persistBody() {
        val t = editTfv.text
        scope.launch {
            withContext(Dispatchers.IO) {
                repo.headContent(node.nodeId)?.let { repo.editNode(node.nodeId, it.copy(text = t)) }
            }
        }
    }

    fun saveBody() {
        persistBody()
        bodySource = false
    }

    // Auto-Save: 3 s Tipp-Pause im Quelltext committet.
    LaunchedEffect(bodySource, editTfv.text) {
        if (!bodySource) return@LaunchedEffect
        kotlinx.coroutines.delay(3000)
        persistBody()
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
    var dueEdit by remember { mutableStateOf(false) }
    var repeatDialog by remember { mutableStateOf(false) }

    if (dueEdit) {
        BackHandler { dueEdit = false }
        // Due Date = normaler Datumsknoten, aber ohne eigene Wiederholung (die lebt an der Aufgabe).
        CalendarEntryEditor(
            repo = repo, blobStore = blobStore, parentId = node.nodeId, post = dueEvent, settings = settings,
            onOpenShare = onOpenShare, onRequestCalendarSync = onRequestCalendarSync,
            showRecurrence = false, defaultAllDay = true, initialTitle = TaskRepeat.DUE_TITLE,
            onClose = { dueEdit = false },
        )
        return
    }

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
    // Back speichert einen offenen Quelltext statt ihn zu verwerfen (Auto-Save-Modell).
    BackHandler { if (bodySource) saveBody() else onClose() }

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

    fun addTag(raw: String) = scope.launch { withContext(Dispatchers.IO) {
        val vocab = repo.allTags()
        repo.headContent(node.nodeId)?.let { repo.editNode(node.nodeId, it.copy(tags = Tags.add(it.tags, raw, vocab))) }
    } }
    fun removeTag(tag: String) = scope.launch { withContext(Dispatchers.IO) {
        repo.headContent(node.nodeId)?.let { repo.editNode(node.nodeId, it.copy(tags = Tags.remove(it.tags, tag))) }
    } }

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

    Box {
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
                            leadingIcon = { Icon(Icons.Filled.Sell, contentDescription = null) },
                            text = { Text("Tag hinzufügen…") },
                            onClick = { dismiss(); tagPicker = true },
                            modifier = Modifier.tag("menu:add-tag"),
                        )
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            text = { Text("Aufgabe löschen") },
                            onClick = {
                                dismiss()
                                scope.launch { withContext(Dispatchers.IO) { repo.deleteNode(node.nodeId) }; onClose() }
                            },
                            modifier = Modifier.tag("menu:delete-todo"),
                        )
                        DropdownMenuItem(
                            text = { Text("Unterpunkte nach Priorität sortieren") },
                            trailingIcon = { Switch(checked = prioSort, onCheckedChange = null) },
                            onClick = {
                                dismiss()
                                val enable = !prioSort
                                // EINschalten: Unterpunkte (TODO/NOTE) einmalig materialisieren
                                // (Flag + Rekeys = EIN Undo-Schritt). Anhänge/Termine bleiben unberührt.
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        val plan = if (enable) {
                                            val subs = repo.children(node.nodeId)
                                                .filter { it.kind == NodeKind.TODO || it.kind == NodeKind.NOTE }
                                            val dm = subs
                                                .filter { it.kind == NodeKind.TODO && !it.done }
                                                .mapNotNull { p ->
                                                    TaskRepeat.dueChild(repo.children(p.nodeId))
                                                        ?.let { PrioritySort.dueMoment(it) }
                                                        ?.let { p.nodeId to it }
                                                }.toMap()
                                            rekeyPlan(PrioritySort.materializeOrder(subs, dm, LocalDateTime.now()))
                                        } else {
                                            emptyList()
                                        }
                                        repo.setPrioritySort(node.nodeId, enable, plan)
                                    }
                                }
                            },
                            modifier = Modifier.tag("menu:prio-sort"),
                        )
                        if (dueEvent == null && !node.done) {
                            DropdownMenuItem(
                                text = { Text("Priorität…") },
                                onClick = { dismiss(); prioPick = true },
                                modifier = Modifier.tag("menu:prio"),
                            )
                        }
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
            TagRow(
                tags = node.tags,
                onAdd = if (!readOnly) { { tagPicker = true } } else null,
                onRemove = if (!readOnly) { { removeTag(it) } } else null,
                onSearchTag = onSearchTag,
            )
            DueRow(
                due = dueEvent,
                ruleSummary = repeatRule?.summary(),
                overdue = !node.done &&
                    dueEvent?.let { TaskRepeat.dueDate(it) }?.let { TaskRepeat.isOverdue(it, LocalDate.now()) } == true,
                readOnly = readOnly,
                onEditDue = { dueEdit = true },
                onEditRepeat = { repeatDialog = true },
            )
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
                        val headTint = ownBand.rowTint()
                        Row(
                            Modifier.fillMaxWidth()
                                .then(if (headTint != null) Modifier.background(headTint) else Modifier)
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = node.done,
                                onCheckedChange = { setDone(node.nodeId, it) },
                                enabled = !readOnly,
                                colors = ownBand.color()
                                    ?.let { CheckboxDefaults.colors(uncheckedColor = it, checkedColor = it) }
                                    ?: CheckboxDefaults.colors(),
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
                    // Unterpunkte: visuell separiert vom Rest (eigener Kasten); ggf. Prio-getönt.
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp).tag("box:subitems"),
                        colors = ownBand.boxTint()
                            ?.let { CardDefaults.cardColors(containerColor = it.compositeOver(MaterialTheme.colorScheme.surfaceVariant)) }
                            ?: CardDefaults.cardColors(),
                    ) {
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Text(
                                "Unterpunkte",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                            for ((i, s) in subShown.withIndex()) {
                                val sBand = PrioritySort.bandOf(s, subDues[s.nodeId], now)
                                val sTint = sBand.rowTint()
                                val row: @Composable () -> Unit = {
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .tag(rowTag(s.title))
                                            .columnDragItem(subDrag, i)
                                            .then(if (sTint != null) Modifier.background(sTint) else Modifier)
                                            .padding(start = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        // Auch Text-/Notiz-Unterpunkte tragen einen Haken (done gibt es auf jedem Knoten).
                                        Checkbox(
                                            checked = s.done, onCheckedChange = { setDone(s.nodeId, it) }, enabled = !readOnly,
                                            colors = sBand.color()
                                                ?.let { CheckboxDefaults.colors(uncheckedColor = it, checkedColor = it) }
                                                ?: CheckboxDefaults.colors(),
                                        )
                                        // clickable nur auf dem Text -> Tap/Long-Press auf dem Ziehgriff navigiert nicht.
                                        Text(
                                            s.title.ifBlank { "(ohne Titel)" },
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            textDecoration = if (s.done) TextDecoration.LineThrough else null,
                                            modifier = Modifier.weight(1f).clickable { if (s.kind == NodeKind.TODO) subTodo = s else subNote = s },
                                        )
                                        if (!readOnly) {
                                            ColumnDragHandle(
                                                subDrag, i, s.title, subShown.size,
                                                onDragStart = { openTrash = null },
                                                onDrop = { from, to ->
                                                    val cur = subShown.toMutableList()
                                                    val moved = cur.removeAt(from)
                                                    cur.add(to, moved)
                                                    if (prioSort) {
                                                        val ranked = cur.map { PrioritySort.ranked(it, subDues[it.nodeId], now) }
                                                        val plan = resolveDrop(ranked, to)
                                                        scope.launch { withContext(Dispatchers.IO) { repo.applyPriorityDrop(moved.nodeId, plan) } }
                                                    } else {
                                                        scope.launch { withContext(Dispatchers.IO) { repo.reorderNode(moved.nodeId, cur.getOrNull(to - 1), cur.getOrNull(to + 1)) } }
                                                    }
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
                            containerColor = ownBand.boxTint()?.compositeOver(MaterialTheme.colorScheme.surfaceVariant),
                            onOpen = { attOpen = it.node },
                        )
                    }
                }
                if (otherEvents.isNotEmpty()) {
                    item(key = "events-head") {
                        Text(
                            "Termine",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                    for (e in otherEvents) {
                        item(key = e.nodeId) { CalendarRow(post = e, onClick = { calEdit = e }) }
                    }
                }
            }
        }
    }
    UndoRedoButtons(repo.undo, todo.nodeId, Modifier.align(Alignment.BottomStart))
    }
    if (tagPicker) {
        TagPickerSheet(
            available = allTagsCache,
            assigned = node.tags,
            allowCreate = true,
            onPick = { raw -> tagPicker = false; addTag(raw) },
            onDismiss = { tagPicker = false },
        )
    }
    if (prioPick) {
        PriorityPickerDialog(
            current = Priority.handBand(node.ext),
            onPick = { level ->
                prioPick = false
                scope.launch { withContext(Dispatchers.IO) { repo.setPriority(node.nodeId, level) } }
            },
            onDismiss = { prioPick = false },
        )
    }
    if (repeatDialog) {
        RepeatDialog(
            initial = repeatRule,
            initialMode = TaskRepeat.mode(node.ext),
            hasDue = dueEvent != null,
            onSave = { rule, mode ->
                repeatDialog = false
                scope.launch { withContext(Dispatchers.IO) {
                    repo.headContent(node.nodeId)?.let {
                        repo.editNode(
                            node.nodeId,
                            it.copy(ext = it.ext + (TaskRepeat.KEY_RULE to rule.format()) + (TaskRepeat.KEY_MODE to mode)),
                        )
                    }
                } }
            },
            onRemove = if (repeatRule != null) {
                {
                    repeatDialog = false
                    scope.launch { withContext(Dispatchers.IO) {
                        repo.headContent(node.nodeId)?.let {
                            repo.editNode(node.nodeId, it.copy(ext = it.ext - TaskRepeat.KEY_RULE - TaskRepeat.KEY_MODE))
                        }
                    } }
                }
            } else null,
            onDismiss = { repeatDialog = false },
        )
    }
}
