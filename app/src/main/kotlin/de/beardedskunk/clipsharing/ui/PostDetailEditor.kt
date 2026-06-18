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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.getValue
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PostDetailEditor(
    repo: FeedRepository,
    blobStore: BlobStore,
    feed: Feed,
    post: PostState?,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val maxImageHeight = (LocalConfiguration.current.screenHeightDp * 0.2f).dp

    var sourceMode by remember { mutableStateOf(post == null) }
    var tfv by remember { mutableStateOf(TextFieldValue(post?.text ?: "")) }
    var images by remember { mutableStateOf(post?.imageHashes ?: emptyList()) }
    var imageTitles by remember {
        mutableStateOf((post?.imageHashes ?: emptyList()).indices.map { i -> TextFieldValue(post?.imageTitles?.getOrNull(i) ?: "") })
    }
    var currentPostId by remember { mutableStateOf(post?.postId) }
    var findOpen by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
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

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val sha = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { blobStore.put(it) }
                }
                if (sha != null && sha !in images) {
                    images = images + sha
                    imageTitles = imageTitles + TextFieldValue("")
                }
            }
        }
    }

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
                    if (sourceMode) {
                        IconButton(onClick = { findOpen = !findOpen }) {
                            Icon(Icons.Filled.Search, contentDescription = "Im Text suchen")
                        }
                        IconButton(onClick = {
                            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) {
                            Icon(Icons.Filled.Add, contentDescription = "Bild hinzufügen")
                        }
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
                },
            )
        },
    ) { padding ->
        if (sourceMode) {
            SourceEditor(
                padding = padding,
                tfv = tfv,
                onTfvChange = { nv -> tfv = handleEnter(tfv, nv) ?: nv },
                focusRequester = focusRequester,
                findOpen = findOpen,
                findQuery = findQuery,
                onFindQuery = { findQuery = it; matchIdx = 0 },
                matchLabel = if (matches.isEmpty()) "0/0" else "${matchIdx + 1}/${matches.size}",
                hasMatches = matches.isNotEmpty(),
                onPrev = { jumpTo(matchIdx - 1) },
                onNext = { jumpTo(matchIdx + 1) },
                onHelp = { helpOpen = true },
                applyToTfv = { transform -> tfv = transform(tfv); focusRequester.requestFocus() },
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
            )
        } else {
            RenderedView(
                padding = padding,
                text = tfv.text,
                onToggleTask = { toggleTask(it) },
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
            )
        }
    }
}

/** Quelltext-Editor mit Toolbar (Aufgabe/Fett/Kursiv/Durchgestrichen/Code/↑/↓/?) und Bild-Feldern. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SourceEditor(
    padding: androidx.compose.foundation.layout.PaddingValues,
    tfv: TextFieldValue,
    onTfvChange: (TextFieldValue) -> Unit,
    focusRequester: FocusRequester,
    findOpen: Boolean,
    findQuery: String,
    onFindQuery: (String) -> Unit,
    matchLabel: String,
    hasMatches: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onHelp: () -> Unit,
    applyToTfv: ((TextFieldValue) -> TextFieldValue) -> Unit,
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
) {
    // Auf der Titelzeile (1. Zeile) sind die Format-Knöpfe inaktiv.
    val firstNl = tfv.text.indexOf('\n').let { if (it < 0) tfv.text.length else it }
    val onTitleLine = tfv.selection.start <= firstNl
    val hasLineSelection = !tfv.selection.collapsed && minOf(tfv.selection.start, tfv.selection.end) > firstNl

    Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())) {
        if (findOpen) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedTextField(
                    value = findQuery, onValueChange = onFindQuery,
                    placeholder = { Text("Suchen…") }, singleLine = true, modifier = Modifier.weight(1f),
                )
                Text(matchLabel)
                IconButton(enabled = hasMatches, onClick = onPrev) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Vorheriger Treffer") }
                IconButton(enabled = hasMatches, onClick = onNext) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Nächster Treffer") }
            }
        }

        OutlinedTextField(
            value = tfv,
            onValueChange = onTfvChange,
            placeholder = { Text("Titel (1. Zeile), dann Markdown…") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).padding(8.dp).focusRequester(focusRequester),
        )

        // Markdown-Toolbar.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TbButton("☐ Aufgabe", enabled = !onTitleLine) { applyToTfv(::insertTask) }
            TbButton("B", enabled = !onTitleLine, bold = true) { applyToTfv { toggleWrap(it, "**") } }
            TbButton("I", enabled = !onTitleLine, italic = true) { applyToTfv { toggleWrap(it, "*") } }
            TbButton("S", enabled = !onTitleLine, strike = true) { applyToTfv { toggleWrap(it, "~~") } }
            TbButton("</>", enabled = !onTitleLine) { applyToTfv { applyCode(it) } }
            IconButton(enabled = hasLineSelection, onClick = { applyToTfv { moveLines(it, up = true) } }) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Zeile(n) nach oben")
            }
            IconButton(enabled = hasLineSelection, onClick = { applyToTfv { moveLines(it, up = false) } }) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Zeile(n) nach unten")
            }
            TbButton("?", enabled = true) { onHelp() }
        }

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
                OutlinedTextField(
                    value = imageTitles.getOrElse(index) { TextFieldValue("") },
                    onValueChange = { onTitleChange(index, it) },
                    placeholder = { Text("Titel (1. Zeile), dann Markdown…") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .then(titleFocusers.getOrNull(index)?.let { Modifier.focusRequester(it) } ?: Modifier),
                )
            }
        }
    }
}

/** Gerenderte Ansicht: Titel als Überschrift, Markdown-Körper mit antippbaren Haken, Bilder mit Beschreibung. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RenderedView(
    padding: androidx.compose.foundation.layout.PaddingValues,
    text: String,
    onToggleTask: (Int) -> Unit,
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
) {
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp)) {
        val title = postTitle(text)
        if (title.isNotBlank()) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 6.dp))
        }
        MarkdownBody(text, onToggleTask = onToggleTask)

        images.forEachIndexed { index, sha ->
            Spacer(Modifier.size(12.dp))
            Box(Modifier.fillMaxWidth()) {
                val bmp = rememberBlobBitmap(blobStore, sha, preferFull = true)
                val imgModifier = Modifier.fillMaxWidth().heightIn(max = maxImageHeight).combinedClickable(
                    onClick = { onView(index) }, onLongClick = { onImageMenu(index) },
                )
                if (bmp != null) Image(bitmap = bmp, contentDescription = null, contentScale = ContentScale.Fit, modifier = imgModifier)
                else Text("🖼 (Bild nicht lokal)", modifier = imgModifier)
                ImageActionMenu(
                    expanded = imageMenuFor == index, onDismiss = { onImageMenu(null) },
                    editTargets = emptyList(),
                    onView = { onImageMenu(null); onView(index) },
                    onEdit = { _, force -> onImageMenu(null); onEdit(index, sha, force) },
                    onShare = { onImageMenu(null); onShare(sha) },
                    onRemove = null,
                )
            }
            val desc = imageTitles.getOrNull(index)?.text ?: ""
            val dTitle = postTitle(desc)
            if (dTitle.isNotBlank()) {
                Text(dTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
            }
            if (postBody(desc).isNotBlank()) {
                MarkdownBody(desc, onToggleTask = { line -> onToggleImageTask(index, line) })
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

/** Kleiner Toolbar-Knopf. */
@Composable
private fun TbButton(label: String, enabled: Boolean, bold: Boolean = false, italic: Boolean = false, strike: Boolean = false, onClick: () -> Unit) {
    val color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    TextButton(onClick = onClick, enabled = enabled, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
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
