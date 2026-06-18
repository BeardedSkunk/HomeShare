package de.beardedskunk.clipsharing.ui

import android.content.ComponentName
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import de.beardedskunk.clipsharing.data.BlobStore
import de.beardedskunk.clipsharing.data.Feed
import de.beardedskunk.clipsharing.data.FeedRepository
import de.beardedskunk.clipsharing.data.PostState
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Detail-/Editier-Ansicht eines Beitrags. Zwei Modi:
 *  - gerendert (Standard bei bestehendem Beitrag): Markdown wird angezeigt, Haken sind
 *    antippbar (jeder Klick = neue, gesyncte Version). Oben rechts der grüne Haken.
 *  - Quelltext (Standard bei neuem Beitrag): roher Markdown mit Toolbar. Oben rechts ✎.
 * Tippen auf den grünen Haken -> Quelltext; tippen auf ✎ -> speichern + zurück zu gerendert.
 * Die erste Zeile ist immer der markup-freie Titel.
 */
/**
 * Offset des ZEILENENDES der Zeile, die [offset] enthaelt. Fuer #5-lite: Tipp auf
 * gerenderten Text -> Edit-Modus mit Cursor am Ende genau dieser Zeile.
 */
private fun endOfLineAt(text: String, offset: Int): Int {
    val o = offset.coerceIn(0, text.length)
    val nl = text.indexOf('\n', o)
    return if (nl < 0) text.length else nl
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PostDetailEditor(
    repo: FeedRepository,
    blobStore: BlobStore,
    feed: Feed,
    post: PostState?,
    /**
     * Wurde der Beitrag aus einer Suche geoeffnet: dieser Suchbegriff. Die Ansicht startet dann
     * im RENDER-Modus mit aktiver Suche (Treffer hervorgehoben, durchsteppbar, Bild-Beschreibungen
     * klappen am Treffer auf). Tippt man dann in einen Treffer, oeffnet der Edit-Modus mit
     * markiertem Treffer.
     */
    searchQuery: String? = null,
    /**
     * Geteilter Suchzustand: `null` = Suche zu, sonst offen (ggf. leerer String). Wird über alle
     * Navigationsebenen geteilt; Schließen (null) leert ihn und propagiert nach oben.
     */
    onSearchQueryChange: (String?) -> Unit = {},
    /** #10: Nur-Lese-Ansicht (Fremdfeed ohne Schreibrecht) – keine Bearbeitung möglich. */
    readOnly: Boolean = false,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val maxImageHeight = (LocalConfiguration.current.screenHeightDp * 0.2f).dp
    // Tastaturhöhe hier (noch unkonsumiert) als Zahl lesen: begrenzt unten das Viewport UND liefert
    // den gleichen Wert als Scroll-Puffer, damit der Cursor des letzten Felds nie hinter der Tastatur landet.
    val density = LocalDensity.current
    val imeBottom = with(density) { WindowInsets.ime.getBottom(density).toDp() }

    var sourceMode by remember { mutableStateOf(post == null) }
    var tfv by remember { mutableStateOf(TextFieldValue(post?.text ?: "")) }
    var images by remember { mutableStateOf(post?.imageHashes ?: emptyList()) }
    var imageTitles by remember {
        mutableStateOf((post?.imageHashes ?: emptyList()).indices.map { i -> TextFieldValue(post?.imageTitles?.getOrNull(i) ?: "") })
    }
    var currentPostId by remember { mutableStateOf(post?.postId) }
    // Suche ist geteilter Zustand (siehe onSearchQueryChange): null = zu, sonst offen.
    val findOpen = searchQuery != null
    val findQuery = searchQuery ?: ""
    var matchIdx by remember { mutableStateOf(0) }
    var helpOpen by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val titleFocusers = remember(images.size) { List(images.size) { FocusRequester() } }

    val matches: List<FindHit> = remember(tfv.text, imageTitles, findQuery) {
        if (findQuery.isBlank()) emptyList() else buildList {
            findAllMatches(tfv.text, findQuery).forEach { add(FindHit(-1, it)) }
            imageTitles.forEachIndexed { i, t -> findAllMatches(t.text, findQuery).forEach { add(FindHit(i, it)) } }
        }
    }

    fun jumpTo(index: Int) {
        if (matches.isEmpty()) return
        val i = ((index % matches.size) + matches.size) % matches.size
        matchIdx = i
        val hit = matches[i]
        val range = TextRange(hit.start, hit.start + findQuery.length)
        if (hit.target < 0) {
            tfv = tfv.copy(selection = range); focusRequester.requestFocus()
        } else {
            imageTitles = imageTitles.toMutableList().also {
                if (hit.target < it.size) it[hit.target] = it[hit.target].copy(selection = range)
            }
            titleFocusers.getOrNull(hit.target)?.requestFocus()
        }
    }

    // Im Render-Modus zaehlt die RenderedView ihre (gerenderten) Treffer; im Edit-Modus die Quell-Treffer.
    var renderMatchCount by remember { mutableStateOf(0) }
    val matchCount = if (sourceMode) matches.size else renderMatchCount
    // Beim Wechsel Render<->Edit bzw. neuer Suche von vorne zaehlen.
    LaunchedEffect(sourceMode) { matchIdx = 0 }

    fun stepMatch(delta: Int) {
        val c = matchCount
        if (c == 0) return
        val next = ((matchIdx + delta) % c + c) % c
        if (sourceMode) jumpTo(next) else matchIdx = next // render: RenderedView scrollt/klappt auf
    }

    // Nach Bildauswahl in der Renderview: zum neuen Bild springen + dessen Titel-Feld fokussieren (Tbd #6).
    var pendingFocusImage by remember { mutableStateOf<Int?>(null) }
    // Tipp auf gerenderten Text -> Quelltext fokussieren (Cursor sitzt schon an der Quellstelle, Tbd #2).
    var pendingEditFocus by remember { mutableStateOf(false) }
    // Mehrfachauswahl: man kann gleich mehrere Bilder picken, ohne extra "OK" (Tbd #11).
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val before = images.size
                val newShas = withContext(Dispatchers.IO) {
                    uris.mapNotNull { uri ->
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { blobStore.put(it) }
                    }
                }
                var imgs = images
                var titles = imageTitles
                for (sha in newShas) if (sha !in imgs) {
                    imgs = imgs + sha
                    titles = titles + TextFieldValue("")
                }
                images = imgs
                imageTitles = titles
                if (imgs.size > before) {
                    // Auch aus der Renderview heraus: in den Quelltext-Modus wechseln und springen.
                    sourceMode = true
                    pendingFocusImage = imgs.size - 1
                }
            }
        }
    }

    // Neu hinzugefügtes Bild fokussieren -> Compose scrollt das Titel-Feld automatisch in den Blick (Tbd #6).
    LaunchedEffect(pendingFocusImage, sourceMode) {
        val idx = pendingFocusImage
        if (idx != null && sourceMode && idx < titleFocusers.size) {
            kotlinx.coroutines.delay(150)
            runCatching { titleFocusers[idx].requestFocus() }
            pendingFocusImage = null
        }
    }
    // Tipp auf gerenderten Text: Quelltext-Feld fokussieren (Cursor wurde schon gesetzt, Tbd #2).
    LaunchedEffect(pendingEditFocus, sourceMode) {
        if (pendingEditFocus && sourceMode) {
            kotlinx.coroutines.delay(120)
            runCatching { focusRequester.requestFocus() }
            pendingEditFocus = false
        }
    }

    // Neue Suche / geänderter Begriff -> Trefferzähler von vorne.
    LaunchedEffect(searchQuery) { matchIdx = 0 }

    val editTargets = remember { imageEditTargets(context) }
    var imageMenuFor by remember { mutableStateOf<Int?>(null) }
    var viewingIndex by remember { mutableStateOf<Int?>(null) }
    var pendingEdit by remember { mutableStateOf<PendingEdit?>(null) }
    val authority = remember { context.packageName + ".fileprovider" }

    // Persistiert den aktuellen Arbeitsstand als neue Version.
    fun save() {
        val text = tfv.text
        val imgs = images
        val titles = imageTitles.map { it.text }
        scope.launch {
            val pid = currentPostId
            val newId = withContext(Dispatchers.IO) {
                if (pid == null) repo.createPost(feed.id, text, imgs, titles).postId
                else { repo.editPost(feed.id, pid, text, imgs, titles); pid }
            }
            currentPostId = newId
        }
    }

    // Bild-Bearbeitung kommt über einen temporären Galerie-Eintrag zurück (Markup u. a.
    // schreiben nur dort in-place). Änderung -> Arbeitsstand ersetzen und in den
    // Quelltext-Modus wechseln (ungespeichert; erst ✎->✓ übernimmt sie).
    val editLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val pe = pendingEdit ?: return@rememberLauncherForActivityResult
        pendingEdit = null
        scope.launch {
            val newSha = withContext(Dispatchers.IO) {
                val candidates = buildList {
                    result.data?.data?.let { u ->
                        runCatching { context.contentResolver.openInputStream(u)?.use { it.readBytes() } }.getOrNull()?.let { add(it) }
                    }
                    MediaStoreEdit.read(context, pe.galleryUri)?.let { add(it) }
                }
                var picked: String? = null
                for (b in candidates) if (b.isNotEmpty()) {
                    val s = blobStore.put(b)
                    if (s != pe.originalSha) { picked = s; break }
                }
                MediaStoreEdit.delete(context, pe.galleryUri)
                picked
            }
            if (newSha != null && pe.index < images.size) {
                images = images.toMutableList().also { it[pe.index] = newSha }
                viewingIndex = null
                sourceMode = true
                Toast.makeText(context, "Bild geändert – mit ✎→✓ übernehmen.", Toast.LENGTH_SHORT).show()
            } else if (newSha == null) {
                Toast.makeText(context, "Keine Änderung übernommen.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun launchEdit(index: Int, sha: String, target: EditTarget?, forceChooser: Boolean) {
        val full = blobStore.readFull(sha)
        if (full == null) {
            Toast.makeText(context, "Vollbild nicht lokal – erst antippen zum Laden.", Toast.LENGTH_SHORT).show(); return
        }
        val uri = MediaStoreEdit.createPending(context, full, "clipsharing_edit_${System.currentTimeMillis()}.png")
        if (uri == null) {
            Toast.makeText(context, "Konnte Bild nicht vorbereiten.", Toast.LENGTH_SHORT).show(); return
        }
        val base = Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(uri, "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        val toLaunch = when {
            target != null -> Intent(base).setComponent(ComponentName(target.pkg, target.cls))
            forceChooser -> Intent.createChooser(base, "Bearbeiten mit…").apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            else -> base
        }
        pendingEdit = PendingEdit(index, uri, sha)
        runCatching { editLauncher.launch(toLaunch) }.onFailure {
            pendingEdit = null
            MediaStoreEdit.delete(context, uri)
            Toast.makeText(context, "Keine App zum Bearbeiten gefunden.", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareImage(sha: String) {
        val file: File? = when {
            blobStore.hasFull(sha) -> blobStore.fullFile(sha)
            blobStore.hasThumb(sha) -> blobStore.thumbFile(sha)
            else -> null
        }
        if (file == null) { Toast.makeText(context, "Bild nicht lokal.", Toast.LENGTH_SHORT).show(); return }
        val uri = FileProvider.getUriForFile(context, authority, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(send, "Bild teilen")) }
    }

    fun delete() {
        if (post == null) { onClose(); return }
        scope.launch { withContext(Dispatchers.IO) { repo.deletePost(feed.id, post.postId) }; onClose() }
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

    fun toggleImageTask(index: Int, sourceLine: Int) {
        val cur = imageTitles.getOrNull(index) ?: return
        val lines = cur.text.split("\n").toMutableList()
        if (sourceLine in lines.indices) {
            lines[sourceLine] = flipTaskLine(lines[sourceLine])
            imageTitles = imageTitles.toMutableList().also { it[index] = cur.copy(text = lines.joinToString("\n")) }
            save()
        }
    }

    // --- Vollbild-Ansicht mit Aktionen (Bearbeiten/Löschen verändern -> Quelltext-Modus). ---
    val vi = viewingIndex
    if (vi != null && vi < images.size) {
        BackHandler { viewingIndex = null }
        val sha = images[vi]
        ImageViewerScreen(
            blobStore = blobStore,
            sha = sha,
            title = imageTitles.getOrNull(vi)?.text?.substringBefore('\n'),
            onShare = { shareImage(sha) },
            onEdit = { force -> launchEdit(vi, images[vi], null, force) },
            onDelete = {
                images = images.toMutableList().also { if (vi < it.size) it.removeAt(vi) }
                imageTitles = imageTitles.toMutableList().also { if (vi < it.size) it.removeAt(vi) }
                sourceMode = true
                viewingIndex = null
            },
            onBack = { viewingIndex = null },
        )
        return
    }

    if (helpOpen) MarkdownHelpDialog(onDismiss = { helpOpen = false })

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Abbrechen")
                    }
                },
                actions = {
                    // Suche in BEIDEN Modi (gerendert + Quelltext). Schließen leert den Begriff
                    // und propagiert nach oben (siehe onSearchQueryChange).
                    IconButton(onClick = { onSearchQueryChange(if (findOpen) null else "") }) {
                        Icon(
                            if (findOpen) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = if (findOpen) "Suche schließen" else "Im Text suchen",
                        )
                    }
                    if (!readOnly) {
                        // Bild hinzufügen in beiden Modi – auch in der Renderview (Tbd #6).
                        IconButton(onClick = {
                            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) {
                            Icon(Icons.Filled.Add, contentDescription = "Bild hinzufügen")
                        }
                        if (post != null) {
                            IconButton(onClick = { delete() }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Löschen")
                            }
                        }
                        // Modus-Umschalter: gerendert zeigt ✓ (grün), Quelltext zeigt ✎.
                        IconButton(onClick = {
                            if (sourceMode) { save(); sourceMode = false } else { sourceMode = true }
                        }) {
                            if (sourceMode) {
                                Icon(Icons.Filled.Edit, contentDescription = "Speichern & anzeigen")
                            } else {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "Bearbeiten",
                                    tint = Color(0xFF2E7D32),
                                    modifier = Modifier.size(30.dp),
                                )
                            }
                        }
                    }
                },
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
                        onTfvChange = { nv -> tfv = handleEnter(tfv, nv) ?: nv },
                        focusRequester = focusRequester,
                        onHelp = { helpOpen = true },
                        applyToTfv = { transform -> tfv = transform(tfv); focusRequester.requestFocus() },
                        // Bildtext-Toolbar wirkt auf das jeweilige Beschreibungsfeld (gleiche Mechanik wie Haupttext).
                        titleApply = { idx, transform ->
                            imageTitles = imageTitles.toMutableList().also {
                                while (it.size <= idx) it.add(TextFieldValue(""))
                                it[idx] = transform(it[idx])
                            }
                            titleFocusers.getOrNull(idx)?.requestFocus()
                        },
                        images = images,
                        imageTitles = imageTitles,
                        titleFocusers = titleFocusers,
                        maxImageHeight = maxImageHeight,
                        blobStore = blobStore,
                        editTargets = editTargets,
                        imageMenuFor = imageMenuFor,
                        onImageMenu = { imageMenuFor = it },
                        onView = { viewingIndex = it },
                        onEdit = { idx, sha, t, force -> launchEdit(idx, sha, t, force) },
                        onShare = { shareImage(it) },
                        onRemove = { idx ->
                            images = images.toMutableList().also { if (idx < it.size) it.removeAt(idx) }
                            imageTitles = imageTitles.toMutableList().also { if (idx < it.size) it.removeAt(idx) }
                        },
                        onTitleChange = { idx, nv ->
                            imageTitles = imageTitles.toMutableList().also {
                                while (it.size <= idx) it.add(TextFieldValue(""))
                                it[idx] = handleEnter(it[idx], nv) ?: nv
                            }
                        },
                        bottomInset = imeBottom,
                    )
                } else {
                    RenderedView(
                        text = tfv.text,
                        onToggleTask = { if (!readOnly) toggleTask(it) },
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
                        images = images,
                        imageTitles = imageTitles,
                        maxImageHeight = maxImageHeight,
                        blobStore = blobStore,
                        imageMenuFor = imageMenuFor,
                        onImageMenu = { imageMenuFor = it },
                        onView = { viewingIndex = it },
                        onEdit = { idx, sha, force -> launchEdit(idx, sha, null, force) },
                        onShare = { shareImage(it) },
                        onToggleImageTask = { idx, line -> toggleImageTask(idx, line) },
                        query = if (findOpen) findQuery else null,
                        currentMatch = matchIdx,
                        onMatchCount = { renderMatchCount = it },
                    )
                }
            }
        }
    }
}

/** Fixe Such-Leiste (oben, ausserhalb des Scrolls) – Suchfeld + Treffer-Zähler + vor/zurück. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FindBar(
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
            placeholder = { Text("Suchen…") }, singleLine = true, modifier = Modifier.weight(1f),
        )
        Text(label)
        IconButton(enabled = hasMatches, onClick = onPrev) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Vorheriger Treffer") }
        IconButton(enabled = hasMatches, onClick = onNext) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Nächster Treffer") }
    }
}

/** Quelltext-Editor: Haupttext-Feld + Bild-Felder, jeweils mit der gleichen [MarkdownToolbar]. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SourceEditor(
    tfv: TextFieldValue,
    onTfvChange: (TextFieldValue) -> Unit,
    focusRequester: FocusRequester,
    onHelp: () -> Unit,
    applyToTfv: ((TextFieldValue) -> TextFieldValue) -> Unit,
    titleApply: (Int, (TextFieldValue) -> TextFieldValue) -> Unit,
    images: List<String>,
    imageTitles: List<TextFieldValue>,
    titleFocusers: List<FocusRequester>,
    maxImageHeight: androidx.compose.ui.unit.Dp,
    blobStore: BlobStore,
    editTargets: List<EditTarget>,
    imageMenuFor: Int?,
    onImageMenu: (Int?) -> Unit,
    onView: (Int) -> Unit,
    onEdit: (Int, String, EditTarget?, Boolean) -> Unit,
    onShare: (String) -> Unit,
    onRemove: (Int) -> Unit,
    onTitleChange: (Int, TextFieldValue) -> Unit,
    bottomInset: androidx.compose.ui.unit.Dp,
) {
    val scope = rememberCoroutineScope()
    // Pro Bildbeschreibungsfeld ein Requester: beim Tippen aktiv ins Sichtfeld holen, da Composes
    // automatisches bringIntoView nur beim Fokussieren feuert, nicht beim Wachsen durch Eingabe.
    val titleBivrs = remember(images.size) { List(images.size) { BringIntoViewRequester() } }
    // Editier-Flaeche scrollt; Such-Leiste/Padding/imePadding liegen im Eltern-Layout (fix oben).
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        OutlinedTextField(
            value = tfv,
            onValueChange = onTfvChange,
            placeholder = { Text("Titel (1. Zeile), dann Markdown…") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).padding(8.dp).focusRequester(focusRequester),
        )
        MarkdownToolbar(value = tfv, apply = applyToTfv, onHelp = onHelp)

        images.forEachIndexed { index, sha ->
            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                Box(Modifier.fillMaxWidth()) {
                    val bmp = rememberBlobBitmap(blobStore, sha, preferFull = true)
                    val imgModifier = Modifier.fillMaxWidth().heightIn(max = maxImageHeight).combinedClickable(
                        onClick = { onView(index) }, onLongClick = { onImageMenu(index) },
                    )
                    if (bmp != null) Image(bitmap = bmp, contentDescription = imageTitles.getOrNull(index)?.text, contentScale = ContentScale.Fit, modifier = imgModifier)
                    else Text("🖼 (Bild nicht lokal)", modifier = imgModifier)
                    IconButton(onClick = { onRemove(index) }, modifier = Modifier.align(Alignment.TopEnd)) {
                        Icon(Icons.Filled.Close, contentDescription = "Bild entfernen")
                    }
                    ImageActionMenu(
                        expanded = imageMenuFor == index, onDismiss = { onImageMenu(null) },
                        editTargets = editTargets,
                        onView = { onImageMenu(null); onView(index) },
                        onEdit = { t, force -> onImageMenu(null); onEdit(index, sha, t, force) },
                        onShare = { onImageMenu(null); onShare(sha) },
                        onRemove = { onImageMenu(null); onRemove(index) },
                    )
                }
                val titleValue = imageTitles.getOrElse(index) { TextFieldValue("") }
                OutlinedTextField(
                    value = titleValue,
                    onValueChange = { onTitleChange(index, it); scope.launch { titleBivrs[index].bringIntoView() } },
                    placeholder = { Text("Titel (1. Zeile), dann Markdown…") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .bringIntoViewRequester(titleBivrs[index])
                        .then(titleFocusers.getOrNull(index)?.let { Modifier.focusRequester(it) } ?: Modifier),
                )
                // Gleiche Toolbar wie beim Haupttext – wirkt auf genau dieses Beschreibungsfeld.
                MarkdownToolbar(value = titleValue, apply = { transform -> titleApply(index, transform) }, onHelp = onHelp)
            }
        }
        // Tastaturhoher Puffer: das letzte (Bild-)Feld kann so über die Tastatur gescrollt
        // werden, damit der Cursor beim Tippen nie dahinter verschwindet.
        Spacer(Modifier.height(bottomInset))
    }
}

/**
 * Markdown-Toolbar (Aufgabe/Fett/Kursiv/Durchgestrichen/Code/Zeile↑↓). Wird sowohl für den
 * Haupttext als auch für jede Bildbeschreibung verwendet – damit beide identisch funktionieren.
 * [value] dient der Aktiv/Inaktiv-Logik (Titelzeile, Zeilenauswahl), [apply] wendet eine
 * Transformation auf das zugehörige Feld an (und fokussiert es danach wieder).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun MarkdownToolbar(
    value: TextFieldValue,
    apply: ((TextFieldValue) -> TextFieldValue) -> Unit,
    onHelp: () -> Unit,
) {
    // Auf der Titelzeile (1. Zeile) sind die Format-Knöpfe inaktiv.
    val firstNl = value.text.indexOf('\n').let { if (it < 0) value.text.length else it }
    val onTitleLine = value.selection.start <= firstNl
    val hasLineSelection = !value.selection.collapsed && minOf(value.selection.start, value.selection.end) > firstNl
    // Rendert genau MARKDOWN_TOOLBAR (datengetrieben + exhaustives when => kein Knopf fällt unbemerkt weg).
    // FlowRow statt horizontalem Scroll: passt nicht alles in eine Zeile (schmale Geräte), bricht der
    // Rest (↑ ↓ ?) in eine zweite Zeile um – nie abgeschnitten, alle Labels bleiben sichtbar.
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (item in MARKDOWN_TOOLBAR) when (item) {
            MarkdownToolbarItem.TASK -> TbButton("☐ Aufgabe", enabled = !onTitleLine) { apply(::insertTask) }
            MarkdownToolbarItem.BOLD -> TbButton("B", enabled = !onTitleLine, bold = true) { apply { toggleWrap(it, "**") } }
            MarkdownToolbarItem.ITALIC -> TbButton("I", enabled = !onTitleLine, italic = true) { apply { toggleWrap(it, "*") } }
            MarkdownToolbarItem.STRIKE -> TbButton("S", enabled = !onTitleLine, strike = true) { apply { toggleWrap(it, "~~") } }
            MarkdownToolbarItem.CODE -> TbButton("</>", enabled = !onTitleLine) { apply { applyCode(it) } }
            // Markierte Zeilen-/Blöcke nach oben/unten verschieben (nur bei Zeilenauswahl aktiv).
            MarkdownToolbarItem.MOVE_UP -> TbButton("↑", enabled = hasLineSelection) { apply { moveLines(it, up = true) } }
            MarkdownToolbarItem.MOVE_DOWN -> TbButton("↓", enabled = hasLineSelection) { apply { moveLines(it, up = false) } }
            MarkdownToolbarItem.HELP -> TbButton("?", enabled = true) { onHelp() }
        }
    }
}

/** Gerenderte Ansicht: Titel als Überschrift, Markdown-Körper mit antippbaren Haken, Bilder mit Beschreibung. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RenderedView(
    text: String,
    onToggleTask: (Int) -> Unit,
    onEditAt: (Int) -> Unit,
    images: List<String>,
    imageTitles: List<TextFieldValue>,
    maxImageHeight: androidx.compose.ui.unit.Dp,
    blobStore: BlobStore,
    imageMenuFor: Int?,
    onImageMenu: (Int?) -> Unit,
    onView: (Int) -> Unit,
    onEdit: (Int, String, Boolean) -> Unit,
    onShare: (String) -> Unit,
    onToggleImageTask: (Int, Int) -> Unit,
    query: String?,
    currentMatch: Int,
    onMatchCount: (Int) -> Unit,
) {
    val descExpanded = remember { mutableStateMapOf<Int, Boolean>() }
    val listState = rememberLazyListState()
    val body = MaterialTheme.typography.bodyLarge
    val title = postTitle(text)
    val blocks = remember(text) { parseMarkdownBody(text) }
    val q = query?.takeIf { it.isNotBlank() }
    val titleItems = if (title.isNotBlank()) 1 else 0

    // Treffer-Anker in LazyColumn-Item-Reihenfolge: [Titel?] + Bloecke + Bilder.
    // Triple(itemIndex, bildIndexOder-1, gerenderter Treffer-Bereich).
    val anchors = remember(text, q, imageTitles.map { it.text }, images) {
        if (q == null) emptyList() else buildList {
            var item = 0
            if (title.isNotBlank()) { matchRanges(title, q).forEach { add(Triple(item, -1, it)) }; item++ }
            for (b in blocks) { matchRanges(b.plain, q).forEach { add(Triple(item, -1, it)) }; item++ }
            images.indices.forEach { i ->
                matchRanges(imageTitles.getOrNull(i)?.text ?: "", q).forEach { add(Triple(item, i, it)) }
                item++
            }
        }
    }
    LaunchedEffect(anchors.size) { onMatchCount(anchors.size) }
    LaunchedEffect(currentMatch, anchors) {
        anchors.getOrNull(currentMatch)?.let { (itemIdx, img, _) ->
            if (img >= 0) descExpanded[img] = true // Treffer in Bild-Beschreibung -> diese aufklappen
            runCatching { listState.animateScrollToItem(itemIdx) }
        }
    }
    val cur = anchors.getOrNull(currentMatch)
    fun curRangeFor(itemIndex: Int): IntRange? =
        if (cur != null && cur.first == itemIndex && cur.second < 0) cur.third else null

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), state = listState) {
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
            MdBlockView(b, body, onToggleTask, onEditAt, highlight = q, currentRange = curRangeFor(titleItems + idx))
        }
        itemsIndexed(images) { i, sha ->
            Column(Modifier.fillMaxWidth()) {
                Spacer(Modifier.size(12.dp))
                Box(Modifier.fillMaxWidth()) {
                    val bmp = rememberBlobBitmap(blobStore, sha, preferFull = true)
                    val imgModifier = Modifier.fillMaxWidth().heightIn(max = maxImageHeight).combinedClickable(
                        onClick = { onView(i) }, onLongClick = { onImageMenu(i) },
                    )
                    if (bmp != null) Image(bitmap = bmp, contentDescription = null, contentScale = ContentScale.Fit, modifier = imgModifier)
                    else Text("🖼 (Bild nicht lokal)", modifier = imgModifier)
                    ImageActionMenu(
                        expanded = imageMenuFor == i, onDismiss = { onImageMenu(null) },
                        editTargets = emptyList(),
                        onView = { onImageMenu(null); onView(i) },
                        onEdit = { _, force -> onImageMenu(null); onEdit(i, sha, force) },
                        onShare = { onImageMenu(null); onShare(sha) },
                        onRemove = null,
                    )
                }
                val desc = imageTitles.getOrNull(i)?.text ?: ""
                val dTitle = postTitle(desc)
                val hasBody = postBody(desc).isNotBlank()
                val isOpen = descExpanded[i] == true
                if (dTitle.isNotBlank() || hasBody) {
                    Row(
                        Modifier.fillMaxWidth().let { if (hasBody) it.clickable { descExpanded[i] = !isOpen } else it },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            highlightedText(dTitle.ifBlank { "Details" }, q, null),
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f).padding(top = 4.dp),
                        )
                        if (hasBody) {
                            Icon(
                                if (isOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                contentDescription = if (isOpen) "Einklappen" else "Aufklappen",
                            )
                        }
                    }
                }
                if (hasBody && isOpen) MarkdownBody(desc, onToggleTask = { line -> onToggleImageTask(i, line) }, highlight = q)
            }
        }
    }
}

/** Aktionsmenü pro Bild: Öffnen / Bearbeiten(…) / Teilen / (Löschen). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageActionMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    editTargets: List<EditTarget>,
    onView: () -> Unit,
    onEdit: (EditTarget?, Boolean) -> Unit,
    onShare: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("Öffnen") }, onClick = onView)
        if (editTargets.size in 1..3) {
            editTargets.forEach { t ->
                DropdownMenuItem(text = { Text("Bearbeiten mit ${t.label}") }, onClick = { onEdit(t, false) })
            }
        } else {
            EditMenuItem(onTap = { onEdit(null, false) }, onLongPress = { onEdit(null, true) })
        }
        DropdownMenuItem(text = { Text("Teilen") }, onClick = onShare)
        if (onRemove != null) DropdownMenuItem(text = { Text("Löschen") }, onClick = onRemove)
    }
}

/** Kleiner Toolbar-Knopf. defaultMinSize hebt den 58-dp-Mindestbreiten-Boden von TextButton auf,
 *  sonst passen die 8 Knöpfe (inkl. ↑ ↓ ?) auf schmalen Geräten nicht nebeneinander. */
@Composable
private fun TbButton(label: String, enabled: Boolean, bold: Boolean = false, italic: Boolean = false, strike: Boolean = false, onClick: () -> Unit) {
    val color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 4.dp),
        modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 36.dp),
    ) {
        Text(
            label,
            color = color,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (italic) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
            textDecoration = if (strike) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
        )
    }
}

/** Kurzhilfe zu Markdown – ohne das, wofür es Knöpfe gibt (fett/kursiv/durchgestrichen/code). */
@Composable
private fun MarkdownHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
        title = { Text("Markdown – Kurzhilfe") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                HelpRow("# Titel ist die 1. Zeile", "schmucklos, kein Markdown")
                HelpRow("## Überschrift", "Abschnitts-Überschrift")
                HelpRow("- Eintrag", "Aufzählung")
                HelpRow("1. Eintrag", "nummerierte Liste")
                HelpRow("- [ ] offen", "offene Aufgabe")
                HelpRow("- [x] erledigt", "erledigte Aufgabe")
                HelpRow("  - eingerückt", "Unterpunkt (2 Leerzeichen)")
                HelpRow("> Zitat", "Zitat")
                HelpRow("--- ", "Trennlinie")
                HelpRow("[Text](https://…)", "Link")
                Spacer(Modifier.size(8.dp))
                Text(
                    "Enter setzt Listen automatisch fort; leerer Eintrag + Enter beendet die Liste. Fett, kursiv, durchgestrichen und Code haben eigene Knöpfe.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun HelpRow(syntax: String, meaning: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(syntax, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(170.dp))
        Text(meaning, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Ein Suchtreffer: [target] == -1 -> Haupttext, sonst Index des Bildtitel-Felds. */
private data class FindHit(val target: Int, val start: Int)

/** Laufende externe Bearbeitung: welches Bild, vorläufiger Galerie-Eintrag und Original-SHA. */
private data class PendingEdit(val index: Int, val galleryUri: android.net.Uri, val originalSha: String)

/**
 * Menüeintrag „Bearbeiten" mit Doppelfunktion: Tippen öffnet Standard/Chooser,
 * langes Drücken erzwingt den Chooser.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EditMenuItem(onTap: () -> Unit, onLongPress: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().combinedClickable(onClick = onTap, onLongClick = onLongPress).padding(horizontal = 16.dp, vertical = 12.dp),
    ) { Text("Bearbeiten") }
}

/** Alle Start-Indizes von [needle] in [haystack] (case-insensitive, ueberlappungsfrei). */
private fun findAllMatches(haystack: String, needle: String): List<Int> {
    if (needle.isEmpty()) return emptyList()
    val out = ArrayList<Int>()
    var from = 0
    while (true) {
        val idx = haystack.indexOf(needle, from, ignoreCase = true)
        if (idx < 0) break
        out += idx; from = idx + needle.length
    }
    return out
}
