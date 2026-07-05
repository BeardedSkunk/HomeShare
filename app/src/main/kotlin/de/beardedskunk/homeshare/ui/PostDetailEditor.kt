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
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val density = LocalDensity.current
    val imeBottom = with(density) { WindowInsets.ime.getBottom(density).toDp() }

    var sourceMode by remember { mutableStateOf(post == null) }
    var tfv by remember { mutableStateOf(TextFieldValue(post?.text ?: "")) }
    var currentNodeId by remember { mutableStateOf(post?.nodeId) }

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

    // Haken in der gerenderten Ansicht umschalten -> Zeile kippen + sofort neue Version.
    fun toggleTask(sourceLine: Int) {
        val lines = tfv.text.split("\n").toMutableList()
        if (sourceLine in lines.indices) {
            lines[sourceLine] = flipTaskLine(lines[sourceLine])
            tfv = tfv.copy(text = lines.joinToString("\n"))
            save()
        }
    }

    // ---- Anhang-Detailansicht (modal) ----
    attOpen?.let { a ->
        BackHandler { attOpen = null }
        AttachmentDetailScreen(repo = repo, blobStore = blobStore, attachment = a, readOnly = readOnly, onClose = { attOpen = null })
        return
    }

    // Links-Swipe -> stehende Mülltonne; EIN Zustand für Anhänge und Markdown-Zeilen.
    var openTrash by remember { mutableStateOf<String?>(null) }
    val attachmentBox: @Composable () -> Unit = {
        AttachmentBox(
            attachments, blobStore,
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    BackIconButton(onClick = onClose, contentDescription = "Abbrechen")
                },
                actions = {
                    // Suche in BEIDEN Modi (gerendert + Quelltext).
                    IconButton(onClick = { onSearchQueryChange(if (findOpen) null else "") }, modifier = Modifier.tag("topbar:search")) {
                        Icon(
                            if (findOpen) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = if (findOpen) "Suche schließen" else "Im Text suchen",
                        )
                    }
                    if (!readOnly) {
                        if (post != null) {
                            IconButton(onClick = { delete() }, modifier = Modifier.tag("topbar:delete")) {
                                Icon(Icons.Filled.Delete, contentDescription = "Löschen")
                            }
                        }
                        // Modus-Umschalter: gerendert zeigt ✓ (grün), Quelltext zeigt ✎.
                        EditToggleButton(sourceMode = sourceMode, onToggle = {
                            if (sourceMode) { save(); sourceMode = false } else { sourceMode = true }
                        })
                    }
                },
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
    attachmentBox: @Composable () -> Unit,
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
        attachmentBox()
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
    attachmentBox: @Composable () -> Unit,
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

    // ---- Zeilen-Drag in der gerenderten Ansicht (ersetzt die ↑/↓-Toolbar-Pfeile) ----
    // Nur Listen-Zeilen (Task/Bullet/Nummer) sind greifbar; der Drop ist auf den
    // zusammenhängenden Listen-Lauf um die gegriffene Zeile begrenzt.
    fun dragLine(b: MdBlock): Int = when (b) {
        is MdBlock.Task -> b.sourceLine
        is MdBlock.Bullet -> b.sourceLine
        is MdBlock.Numbered -> b.sourceLine
        else -> -1
    }
    var mdDragIndex by remember { mutableStateOf(-1) }
    var mdDragOffset by remember { mutableStateOf(0f) }
    fun endLineDrag(idx: Int) {
        if (onMoveLine != null) {
            val itemIdx = titleItems + idx
            val items = listState.layoutInfo.visibleItemsInfo
            val dragged = items.firstOrNull { it.index == itemIdx }
            if (dragged != null) {
                val center = dragged.offset + mdDragOffset + dragged.size / 2f
                // Mittellinien-Regel: umsortiert wird um so viele Positionen, wie das Zentrum
                // des gezogenen Items Nachbar-ZENTREN überquert hat (kein "berührt reicht").
                var target = idx
                for (it2 in items) {
                    if (it2.index == itemIdx) continue
                    val c2 = it2.offset + it2.size / 2f
                    if (it2.index > itemIdx && center > c2) target++
                    if (it2.index < itemIdx && center < c2) target--
                }
                var lo = idx
                while (lo - 1 >= 0 && dragLine(blocks[lo - 1]) > 0) lo--
                var hi = idx
                while (hi + 1 < blocks.size && dragLine(blocks[hi + 1]) > 0) hi++
                target = target.coerceIn(lo, hi)
                if (target != idx) onMoveLine(dragLine(blocks[idx]), dragLine(blocks[target]))
            }
        }
        mdDragIndex = -1
        mdDragOffset = 0f
    }

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
        itemsIndexed(blocks) { idx, b ->
            val line = dragLine(b)
            if (onMoveLine != null && line > 0) {
                val row: @Composable () -> Unit = {
                    Row(
                        Modifier.fillMaxWidth()
                            .then(if (idx == mdDragIndex) Modifier.zIndex(1f).graphicsLayer { translationY = mdDragOffset } else Modifier),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) {
                            MdBlockView(b, body, onToggleTask, onEditAt, highlight = q, currentRange = curRangeFor(titleItems + idx))
                        }
                        Icon(
                            Icons.Filled.DragIndicator,
                            contentDescription = "Zeile verschieben",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.tag("drag:line:$line").pointerInput(idx, blocks) {
                                detectVerticalDragGestures(
                                    onDragStart = { onOpenTrash?.invoke(null); mdDragIndex = idx; mdDragOffset = 0f },
                                    onVerticalDrag = { change, amount -> change.consume(); mdDragOffset += amount },
                                    onDragEnd = { endLineDrag(idx) },
                                    onDragCancel = { mdDragIndex = -1; mdDragOffset = 0f },
                                )
                            },
                        )
                    }
                }
                if (onDeleteLine != null && onOpenTrash != null) {
                    // Tonne löscht die Zeile samt ihrer eingerückten Unterpunkte.
                    SwipeRevealRow(
                        key = "line:$line", openKey = openTrashKey, onOpenChange = onOpenTrash,
                        onDelete = { onDeleteLine(line) },
                    ) { row() }
                } else {
                    row()
                }
            } else {
                MdBlockView(b, body, onToggleTask, onEditAt, highlight = q, currentRange = curRangeFor(titleItems + idx))
            }
        }
        if (hasAttachments) {
            item("attachments") { attachmentBox() }
        }
    }
}
