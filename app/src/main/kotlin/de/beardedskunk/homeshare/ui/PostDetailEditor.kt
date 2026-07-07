package de.beardedskunk.homeshare.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Sell
import de.beardedskunk.homeshare.core.Tags
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import de.beardedskunk.homeshare.core.NodeContent
import de.beardedskunk.homeshare.core.NodeType
import de.beardedskunk.homeshare.data.BlobStore
import de.beardedskunk.homeshare.data.FeedRepository
import de.beardedskunk.homeshare.data.NodeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Offset des ZEILENENDES der Zeile, die [offset] enthaelt. Fuer #5-lite: Tipp auf
 * gerenderten Text -> Edit-Modus mit Cursor am Ende genau dieser Zeile.
 */
private fun endOfLineAt(text: String, offset: Int): Int {
    val o = offset.coerceIn(0, text.length)
    val nl = text.indexOf('\n', o)
    return if (nl < 0) text.length else nl
}

/**
 * Detail-/Editier-Ansicht eines Beitrags. Zwei Modi:
 *  - gerendert (Standard bei bestehendem Beitrag): Markdown wird angezeigt, Haken sind
 *    antippbar, Listen-Zeilen per Handle verschiebbar. Oben rechts der grüne Haken.
 *  - Quelltext (Standard bei neuem Beitrag): roher Markdown mit Toolbar. Oben rechts ✎.
 * Die erste Zeile ist immer der markup-freie Titel. Anhänge (Bilder/Dateien) hängen als
 * Kindknoten am Beitrag und erscheinen als „Anhänge“-Kasten unter dem Text (beide Modi);
 * angelegt werden sie über den FAB (nur Bild + Datei), Details in [AttachmentDetailScreen].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PostDetailEditor(
    repo: FeedRepository,
    blobStore: BlobStore,
    parentId: String,
    post: NodeState?,
    /**
     * Wurde der Beitrag aus einer Suche geoeffnet: dieser Suchbegriff. Die Ansicht startet dann
     * im RENDER-Modus mit aktiver Suche (Treffer hervorgehoben, durchsteppbar).
     */
    searchQuery: String? = null,
    /**
     * Geteilter Suchzustand: `null` = Suche zu, sonst offen (ggf. leerer String). Wird über alle
     * Navigationsebenen geteilt; Schließen (null) leert ihn und propagiert nach oben.
     */
    onSearchQueryChange: (String?) -> Unit = {},
    /** #10: Nur-Lese-Ansicht (Fremdfeed ohne Schreibrecht) – keine Bearbeitung möglich. */
    readOnly: Boolean = false,
    /**
     * false = nur Titel+Markdown-Body bearbeiten, KEINE Anhänge laden/anbieten.
     * Nötig für die Beschreibung von LISTEN: deren IMAGE/FILE-Kinder sind echte
     * Listeneinträge und dürfen hier nicht als Anhänge erscheinen.
     */
    showAttachments: Boolean = true,
    onOpenShare: ((NodeState) -> Unit)? = null,
    onSearchTag: ((String) -> Unit)? = null,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val density = LocalDensity.current
    val imeBottom = with(density) { WindowInsets.ime.getBottom(density).toDp() }

    var sourceMode by remember { mutableStateOf(post == null) }
    var tfv by remember { mutableStateOf(TextFieldValue(post?.text ?: "")) }
    var currentNodeId by remember { mutableStateOf(post?.nodeId) }

    // Undo-Anker: bei Neuanlage ein stabiler Besuchs-Schlüssel (nach Schließen/Wiederöffnen
    // startet die Kette der dann existierenden Notiz leer — akzeptiert).
    val anchorId = remember { post?.nodeId ?: "new:" + java.util.UUID.randomUUID() }
    RegisterUndoAnchor(repo.undo, anchorId)

    var nodeTags by remember { mutableStateOf(post?.tags ?: emptyList<String>()) }
    var tagPicker by remember { mutableStateOf(false) }
    var allTagsCache by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(tagPicker) {
        if (tagPicker) allTagsCache = withContext(Dispatchers.IO) { repo.allTags() }
    }

    // ---- Anhänge (IMAGE/FILE-Kinder mit Caption-Titel) ----
    var attachments by remember { mutableStateOf<List<AttachmentRow>>(emptyList()) }
    var attOpen by remember { mutableStateOf<NodeState?>(null) }
    val revision by repo.revision.collectAsState()
    LaunchedEffect(revision, currentNodeId, showAttachments) {
        val id = currentNodeId
        attachments = if (showAttachments && id != null) {
            withContext(Dispatchers.IO) { loadAttachmentRows(repo, id) }
        } else {
            emptyList()
        }
        if (id != null) {
            val fresh = withContext(Dispatchers.IO) { repo.getNode(id)?.tags }
            if (fresh != null) nodeTags = fresh
        }
    }

    // ---- Suche (nur im Body; Anhang-Texte haben ihre eigene Detailansicht) ----
    val findOpen = searchQuery != null
    val findQuery = searchQuery ?: ""
    var matchIdx by remember { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    val matches: List<Int> = remember(tfv.text, findQuery) {
        if (findQuery.isBlank()) emptyList() else findAllMatches(tfv.text, findQuery)
    }

    fun jumpTo(index: Int) {
        if (matches.isEmpty()) return
        val i = ((index % matches.size) + matches.size) % matches.size
        matchIdx = i
        tfv = tfv.copy(selection = TextRange(matches[i], matches[i] + findQuery.length))
        focusRequester.requestFocus()
    }

    // Im Render-Modus zaehlt die RenderedView ihre (gerenderten) Treffer; im Edit-Modus die Quell-Treffer.
    var renderMatchCount by remember { mutableStateOf(0) }
    val matchCount = if (sourceMode) matches.size else renderMatchCount
    LaunchedEffect(sourceMode) { matchIdx = 0 }
    LaunchedEffect(searchQuery) { matchIdx = 0 }

    fun stepMatch(delta: Int) {
        val c = matchCount
        if (c == 0) return
        val next = ((matchIdx + delta) % c + c) % c
        if (sourceMode) jumpTo(next) else matchIdx = next // render: RenderedView scrollt
    }

    // Tipp auf gerenderten Text -> Quelltext fokussieren (Cursor sitzt schon an der Quellstelle, Tbd #2).
    var pendingEditFocus by remember { mutableStateOf(false) }
    LaunchedEffect(pendingEditFocus, sourceMode) {
        if (pendingEditFocus && sourceMode) {
            kotlinx.coroutines.delay(120)
            runCatching { focusRequester.requestFocus() }
            pendingEditFocus = false
        }
    }

    // ---- Anhänge anlegen (FAB): Eintrags-Knoten bei neuem Beitrag erst jetzt anlegen. ----
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val entryId = currentNodeId ?: withContext(Dispatchers.IO) {
                repo.createNode(NodeContent(parentId = parentId, type = NodeType.TEXT, text = tfv.text)).nodeId
            }
            currentNodeId = entryId
            withContext(Dispatchers.IO) { uris.forEach { AttachmentPicker.addImage(context, repo, blobStore, entryId, it) } }
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val entryId = currentNodeId ?: withContext(Dispatchers.IO) {
                repo.createNode(NodeContent(parentId = parentId, type = NodeType.TEXT, text = tfv.text)).nodeId
            }
            currentNodeId = entryId
            withContext(Dispatchers.IO) { AttachmentPicker.addFile(context, repo, blobStore, entryId, uri) }
        }
    }

    /** Persistiert den Text des Eintrags (Anhänge werden sofort beim Anlegen/Löschen persistiert). */
    fun save() {
        val text = tfv.text
        // Leere Neuanlage nicht anlegen — sonst erzeugt der Auto-Save-Debounce leere Knoten.
        if (currentNodeId == null && text.isBlank()) return
        scope.launch {
            val newId = withContext(Dispatchers.IO) {
                val entryId = currentNodeId
                if (entryId == null) {
                    repo.createNode(NodeContent(parentId = parentId, type = NodeType.TEXT, text = text)).nodeId
                } else {
                    val hc = repo.headContent(entryId) ?: NodeContent(parentId = parentId, type = NodeType.TEXT)
                    // Typ NICHT anfassen: der Editor bearbeitet auch LISTen (Beschreibung) und
                    // TODOs – deren Knotentyp muss erhalten bleiben.
                    repo.editNode(entryId, hc.copy(text = text))
                    entryId
                }
            }
            currentNodeId = newId
        }
    }

    fun delete() {
        val nid = currentNodeId
        if (nid == null) { onClose(); return }
        scope.launch { withContext(Dispatchers.IO) { repo.deleteNode(nid) }; onClose() }
    }

    fun addTag(raw: String) = scope.launch { withContext(Dispatchers.IO) {
        val id = currentNodeId ?: return@withContext
        val vocab = repo.allTags()
        repo.headContent(id)?.let { repo.editNode(id, it.copy(tags = Tags.add(it.tags, raw, vocab))) }
    } }
    fun removeTag(tag: String) = scope.launch { withContext(Dispatchers.IO) {
        val id = currentNodeId ?: return@withContext
        repo.headContent(id)?.let { repo.editNode(id, it.copy(tags = Tags.remove(it.tags, tag))) }
    } }

    // Haken in der gerenderten Ansicht umschalten -> Zeile kippen + sofort neue Version.
    fun toggleTask(sourceLine: Int) {
        val lines = tfv.text.split("\n").toMutableList()
        if (sourceLine in lines.indices) {
            lines[sourceLine] = flipTaskLine(lines[sourceLine])
            tfv = tfv.copy(text = lines.joinToString("\n"))
            save()
        }
    }

    // Auto-Save: 3 s Tipp-Pause committet (dank editNode-Guard gratis, wenn nichts geändert).
    LaunchedEffect(sourceMode, tfv.text) {
        if (!sourceMode) return@LaunchedEffect
        kotlinx.coroutines.delay(3000)
        save()
    }
    // App in den Hintergrund -> offenen Quelltext committen (Auto-Save-Modell, kein Verwerfen).
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE && sourceMode) save()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // ---- Anhang-Detailansicht (modal) ----
    attOpen?.let { a ->
        BackHandler { attOpen = null }
        AttachmentDetailScreen(repo = repo, blobStore = blobStore, attachment = a, readOnly = readOnly, onOpenShare = onOpenShare, onClose = { attOpen = null })
        return
    }

    // Back speichert im Quelltext-Modus statt zu verwerfen (behebt den alten Datenverlust-Bug);
    // eine leere Neuanlage schließt direkt. Überschreibt den BackHandler des aufrufenden Screens.
    BackHandler {
        if (sourceMode) {
            save()
            if (currentNodeId == null && tfv.text.isBlank()) onClose() else sourceMode = false
        } else {
            onClose()
        }
    }

    // Links-Swipe -> stehende Mülltonne; EIN Zustand für Anhänge und Markdown-Zeilen.
    var openTrash by remember { mutableStateOf<String?>(null) }
    val attachmentBox: @Composable (Dp) -> Unit = { hPad ->
        AttachmentBox(
            attachments, blobStore,
            horizontalPadding = hPad,
            openTrashKey = openTrash,
            onOpenTrash = if (readOnly) null else ({ openTrash = it }),
            onDelete = if (readOnly) null else ({ a ->
                openTrash = null
                scope.launch { withContext(Dispatchers.IO) { repo.deleteNode(a.node.nodeId) } }
            }),
            onReorder = if (readOnly) null else ({ moved, prev, next ->
                scope.launch { withContext(Dispatchers.IO) { repo.reorderNode(moved.nodeId, prev, next) } }
            }),
            onOpen = { attOpen = it.node },
        )
    }

    Box {
    Scaffold(
        topBar = {
            DetailTopBar(
                onBack = onClose,
                searchOpen = findOpen,
                onToggleSearch = { onSearchQueryChange(if (findOpen) null else "") },
                onShare = null,
                menuContent = if (!readOnly && currentNodeId != null || onOpenShare != null && currentNodeId != null) { dismiss ->
                    if (onOpenShare != null && currentNodeId != null) {
                        DropdownMenuItem(
                            text = { Text("Mit Gruppe teilen / Freigaben…") },
                            leadingIcon = { Icon(Icons.Filled.QrCode2, contentDescription = null) },
                            onClick = {
                                dismiss()
                                scope.launch {
                                    val n = withContext(Dispatchers.IO) { currentNodeId?.let { repo.getNode(it) } }
                                    n?.let { onOpenShare(it) }
                                }
                            },
                            modifier = Modifier.tag("menu:share"),
                        )
                    }
                    if (!readOnly && currentNodeId != null) {
                        DropdownMenuItem(
                            text = { Text("Tag hinzufügen…") },
                            leadingIcon = { Icon(Icons.Filled.Sell, contentDescription = null) },
                            onClick = { dismiss(); tagPicker = true },
                            modifier = Modifier.tag("menu:add-tag"),
                        )
                    }
                    if (!readOnly && post != null) {
                        DropdownMenuItem(
                            text = { Text("Löschen") },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = { dismiss(); delete() },
                            modifier = Modifier.tag("menu:delete-note"),
                        )
                    }
                } else null,
                sourceMode = sourceMode,
                onEditToggle = if (!readOnly) {
                    { if (sourceMode) { save(); sourceMode = false } else sourceMode = true }
                } else null,
            )
        },
        floatingActionButton = {
            // Anhänge (nur Bild + Datei) über den FAB – wie in der Aufgaben-Ansicht.
            if (!readOnly && showAttachments) AttachmentAddFab(
                onPickImages = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onPickFile = { pickFile.launch(arrayOf("*/*")) },
            )
        },
    ) { padding ->
        // Such-Leiste FIX oben (ausserhalb des scrollenden Inhalts) -> sie bleibt sichtbar,
        // auch wenn die Tastatur hochpoppt (#7). Inhalt darunter bekommt imePadding.
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (findOpen) {
                FindBar(
                    query = findQuery,
                    onQuery = { onSearchQueryChange(it); matchIdx = 0 },
                    label = if (matchCount == 0) "0/0" else "${matchIdx + 1}/$matchCount",
                    hasMatches = matchCount > 0,
                    onPrev = { stepMatch(-1) },
                    onNext = { stepMatch(1) },
                )
            }
            if (currentNodeId != null) {
                TagRow(
                    tags = nodeTags,
                    onAdd = if (!readOnly) { { tagPicker = true } } else null,
                    onRemove = if (!readOnly) { { removeTag(it) } } else null,
                    onSearchTag = onSearchTag,
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = imeBottom)) {
                if (sourceMode) {
                    SourceEditor(
                        tfv = tfv,
                        onTfvChange = { tfv = it },
                        focusRequester = focusRequester,
                        attachmentBox = attachmentBox,
                        bottomInset = imeBottom,
                    )
                } else {
                    RenderedView(
                        text = tfv.text,
                        onToggleTask = { if (!readOnly) toggleTask(it) },
                        onMoveLine = if (readOnly) null else { from, to ->
                            tfv = TextFieldValue(moveLineTo(tfv.text, from, to))
                            save()
                        },
                        onEditAt = { off ->
                            if (!readOnly) {
                                // Tipp auf gerenderten Text -> Edit. Bei aktiver Suche den Treffer
                                // an/um der Tippstelle markieren; sonst Cursor ans Zeilenende (#5-lite).
                                val sel = if (findOpen && findQuery.isNotBlank()) {
                                    var i = tfv.text.indexOf(findQuery, maxOf(0, off - findQuery.length), ignoreCase = true)
                                    if (i < 0) i = tfv.text.indexOf(findQuery, 0, ignoreCase = true)
                                    if (i >= 0) TextRange(i, i + findQuery.length) else TextRange(endOfLineAt(tfv.text, off))
                                } else {
                                    TextRange(endOfLineAt(tfv.text, off))
                                }
                                tfv = tfv.copy(selection = sel)
                                sourceMode = true
                                pendingEditFocus = true
                            }
                        },
                        attachmentBox = attachmentBox,
                        hasAttachments = attachments.isNotEmpty(),
                        openTrashKey = openTrash,
                        onOpenTrash = if (readOnly) null else ({ openTrash = it }),
                        onDeleteLine = if (readOnly) null else ({ line ->
                            openTrash = null
                            tfv = TextFieldValue(deleteLineWithChildren(tfv.text, line))
                            save()
                        }),
                        query = if (findOpen) findQuery else null,
                        currentMatch = matchIdx,
                        onMatchCount = { renderMatchCount = it },
                    )
                }
            }
        }
    }
    UndoRedoButtons(repo.undo, anchorId, Modifier.align(Alignment.BottomStart))
    }
    if (tagPicker) {
        TagPickerSheet(
            available = allTagsCache,
            assigned = nodeTags,
            allowCreate = true,
            onPick = { raw -> tagPicker = false; addTag(raw) },
            onDismiss = { tagPicker = false },
        )
    }
}

/** FAB-Menü für Anhänge: bewusst NUR Bild + Datei (keine Termine/Notizen über den FAB). */
@Composable
fun AttachmentFabMenu(expanded: Boolean, onDismiss: () -> Unit, onPickImages: () -> Unit, onPickFile: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            leadingIcon = { Icon(de.beardedskunk.homeshare.core.NodeKind.IMAGE.uiIcon(), contentDescription = null) },
            text = { Text("Bild") },
            onClick = { onDismiss(); onPickImages() },
            modifier = Modifier.tag("menu:create:image"),
        )
        DropdownMenuItem(
            leadingIcon = { Icon(de.beardedskunk.homeshare.core.NodeKind.FILE.uiIcon(), contentDescription = null) },
            text = { Text("Datei") },
            onClick = { onDismiss(); onPickFile() },
            modifier = Modifier.tag("menu:create:file"),
        )
    }
}

/** Fixe Such-Leiste (oben, ausserhalb des Scrolls) – Suchfeld + Treffer-Zähler + vor/zurück. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FindBar(
    query: String,
    onQuery: (String) -> Unit,
    label: String,
    hasMatches: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = query, onValueChange = onQuery,
            placeholder = { Text("Suchen…") }, singleLine = true, modifier = Modifier.weight(1f).tag("field:find"),
        )
        Text(label)
        IconButton(enabled = hasMatches, onClick = onPrev, modifier = Modifier.tag("find:prev")) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Vorheriger Treffer") }
        IconButton(enabled = hasMatches, onClick = onNext, modifier = Modifier.tag("find:next")) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Nächster Treffer") }
    }
}

/** Quelltext-Editor: Haupttext-Feld + Toolbar; darunter der Anhänge-Kasten. */
@Composable
private fun SourceEditor(
    tfv: TextFieldValue,
    onTfvChange: (TextFieldValue) -> Unit,
    focusRequester: FocusRequester,
    attachmentBox: @Composable (Dp) -> Unit,
    bottomInset: androidx.compose.ui.unit.Dp,
) {
    // Editier-Fläche scrollt; Such-Leiste/Padding/imePadding liegen im Eltern-Layout (fix oben).
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        MarkdownEditField(
            value = tfv,
            onValueChange = onTfvChange,
            fieldModifier = Modifier.heightIn(min = 120.dp).padding(8.dp).tag("field:body"),
            focusRequester = focusRequester,
        )
        attachmentBox(12.dp)
        // Puffer = Tastatur ODER FAB, je nachdem was höher ist — letztes Feld bleibt scrollbar.
        Spacer(Modifier.height(maxOf(bottomInset, ATTACHMENT_FAB_CLEARANCE)))
    }
}


/** Gerenderte Ansicht: Titel als Überschrift, Markdown-Körper mit antippbaren Haken und
 *  verschiebbaren Listen-Zeilen; darunter der Anhänge-Kasten. */
@Composable
private fun RenderedView(
    text: String,
    onToggleTask: (Int) -> Unit,
    onEditAt: (Int) -> Unit,
    attachmentBox: @Composable (Dp) -> Unit,
    hasAttachments: Boolean,
    /** Tonnen-Zustand des Screens (geteilt mit dem Anhänge-Kasten). */
    openTrashKey: String?,
    onOpenTrash: ((String?) -> Unit)?,
    /** Quellzeile samt tiefer eingerückter Folgezeilen löschen; null = kein Swipe-Löschen. */
    onDeleteLine: ((Int) -> Unit)?,
    query: String?,
    currentMatch: Int,
    onMatchCount: (Int) -> Unit,
    /** Zeile [from] vor/hinter Zeile [to] verschieben (absolute Quellzeilen); null = kein Drag. */
    onMoveLine: ((from: Int, to: Int) -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val body = MaterialTheme.typography.bodyLarge
    val title = postTitle(text)
    val blocks = remember(text) { parseMarkdownBody(text) }
    val q = query?.takeIf { it.isNotBlank() }
    val titleItems = if (title.isNotBlank()) 1 else 0
    val drag = rememberMdLineDragState()

    // Treffer-Anker in LazyColumn-Item-Reihenfolge: [Titel?] + Bloecke.
    val anchors = remember(text, q) {
        if (q == null) emptyList() else buildList {
            var item = 0
            if (title.isNotBlank()) { matchRanges(title, q).forEach { add(item to it) }; item++ }
            for (b in blocks) { matchRanges(b.plain, q).forEach { add(item to it) }; item++ }
        }
    }
    LaunchedEffect(anchors.size) { onMatchCount(anchors.size) }
    LaunchedEffect(currentMatch, anchors) {
        anchors.getOrNull(currentMatch)?.let { (itemIdx, _) ->
            runCatching { listState.animateScrollToItem(itemIdx) }
        }
    }
    val cur = anchors.getOrNull(currentMatch)
    fun curRangeFor(itemIndex: Int): IntRange? =
        if (cur != null && cur.first == itemIndex) cur.second else null

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = ATTACHMENT_FAB_CLEARANCE)) {
        if (title.isNotBlank()) {
            item("title") {
                Text(
                    highlightedText(title, q, curRangeFor(0)),
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }
        markdownBlockItems(
            blocks = blocks,
            listState = listState,
            drag = drag,
            bodyStyle = body,
            firstItemIndex = titleItems,
            onToggleTask = onToggleTask,
            onEditAt = onEditAt,
            onMoveLine = onMoveLine,
            onDeleteLine = onDeleteLine,
            openTrashKey = openTrashKey,
            onOpenTrash = onOpenTrash,
            highlight = q,
            currentRangeFor = ::curRangeFor,
        )
        if (hasAttachments) {
            item("attachments") { attachmentBox(0.dp) }
        }
    }
}
