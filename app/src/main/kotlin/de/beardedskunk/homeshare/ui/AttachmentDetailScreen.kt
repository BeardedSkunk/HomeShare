package de.beardedskunk.homeshare.ui

import android.content.ComponentName
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
 * Detailansicht EINES Anhangs (Bild oder Datei): oben Titel + Markdown der Anhang-Notiz
 * (das TEXT-Kind des Anhang-Knotens), darunter FIX das Bild (mit Pinch-to-Zoom) bzw. die
 * Datei-Zeile – in Render- UND Edit-Modus. Kein FAB, keine weiteren Anhänge.
 * Long-Press aufs Bild: Teilen / Bearbeiten (externe App). Long-Press auf Datei:
 * Teilen / Öffnen (/ Als Text öffnen bei text-artigem MIME).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AttachmentDetailScreen(
    repo: FeedRepository,
    blobStore: BlobStore,
    attachment: NodeState,
    readOnly: Boolean = false,
    onOpenShare: ((NodeState) -> Unit)? = null,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val revision by repo.revision.collectAsState()

    var att by remember { mutableStateOf(attachment) }
    var capId by remember { mutableStateOf<String?>(null) }
    var tfv by remember { mutableStateOf(TextFieldValue("")) }
    var loaded by remember { mutableStateOf(false) }
    var sourceMode by remember { mutableStateOf(false) }
    var headerExpanded by remember { mutableStateOf(true) }   // beim Öffnen ausgeklappt; klappt beim Reinzoomen ein

    LaunchedEffect(revision) {
        val fresh = withContext(Dispatchers.IO) {
            val a = repo.getPostState(attachment.nodeId)
            val cap = repo.children(attachment.nodeId).firstOrNull { it.type == NodeType.TEXT }
            a to cap
        }
        fresh.first?.let { att = it }
        capId = fresh.second?.nodeId
        // Text nur beim ersten Laden übernehmen – laufende Bearbeitung nicht überschreiben.
        if (!loaded) { tfv = TextFieldValue(fresh.second?.text ?: ""); loaded = true }
    }

    fun save() {
        val text = tfv.text
        scope.launch {
            withContext(Dispatchers.IO) {
                val id = capId
                if (id != null) {
                    repo.headContent(id)?.let { repo.editNode(id, it.copy(text = text)) }
                } else {
                    capId = repo.createNode(NodeContent(parentId = att.nodeId, type = NodeType.TEXT, text = text)).nodeId
                }
            }
        }
    }

    fun toggleTask(line: Int) {
        val lines = tfv.text.split("\n").toMutableList()
        if (line in lines.indices) {
            lines[line] = flipTaskLine(lines[line])
            tfv = tfv.copy(text = lines.joinToString("\n"))
            save()
        }
    }

    // ---- Externe Bild-Bearbeitung (via temporärem Galerie-Eintrag, wie früher im Editor) ----
    val editTargets = remember { imageEditTargets(context) }
    var pendingEditUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val editLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val galleryUri = pendingEditUri ?: return@rememberLauncherForActivityResult
        pendingEditUri = null
        val originalSha = att.blobHash
        scope.launch {
            val newSha = withContext(Dispatchers.IO) {
                val candidates = buildList {
                    result.data?.data?.let { u ->
                        runCatching { context.contentResolver.openInputStream(u)?.use { it.readBytes() } }.getOrNull()?.let { add(it) }
                    }
                    MediaStoreEdit.read(context, galleryUri)?.let { add(it) }
                }
                var picked: String? = null
                for (b in candidates) if (b.isNotEmpty()) {
                    val s = blobStore.put(b)
                    if (s != originalSha) { picked = s; break }
                }
                MediaStoreEdit.delete(context, galleryUri)
                picked
            }
            if (newSha != null) {
                withContext(Dispatchers.IO) {
                    repo.headContent(att.nodeId)?.let { repo.editNode(att.nodeId, it.copy(blobHash = newSha)) }
                }
                toast(context, "Bild geändert.")
            } else {
                toast(context, "Keine Änderung übernommen.")
            }
        }
    }

    fun launchEdit(target: EditTarget?, forceChooser: Boolean) {
        val sha = att.blobHash ?: return
        val full = blobStore.readFull(sha) ?: return toast(context, "Vollbild nicht lokal – erst syncen.")
        val uri = MediaStoreEdit.createPending(context, full, "homeshare_edit_${System.currentTimeMillis()}.png")
            ?: return toast(context, "Konnte Bild nicht vorbereiten.")
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
        pendingEditUri = uri
        runCatching { editLauncher.launch(toLaunch) }.onFailure {
            pendingEditUri = null
            MediaStoreEdit.delete(context, uri)
            toast(context, "Keine App zum Bearbeiten gefunden.")
        }
    }

    var findQuery by remember { mutableStateOf<String?>(null) }
    var matchIdx by remember { mutableStateOf(0) }
    val matches: List<Int> = remember(tfv.text, findQuery) {
        val q = findQuery ?: return@remember emptyList()
        if (q.isBlank()) emptyList() else findAllMatches(tfv.text, q)
    }
    val matchCount = matches.size
    fun stepMatch(delta: Int) {
        if (matchCount == 0) return
        matchIdx = ((matchIdx + delta) % matchCount + matchCount) % matchCount
    }

    BackHandler { if (sourceMode) { save(); sourceMode = false } else onClose() }

    Scaffold(
        topBar = {
            DetailTopBar(
                onBack = onClose,
                searchOpen = findQuery != null,
                onToggleSearch = { findQuery = if (findQuery != null) null else "" },
                onShare = null,
                menuContent = if (!readOnly || onOpenShare != null) { dismiss ->
                    if (onOpenShare != null) {
                        DropdownMenuItem(
                            text = { Text("Mit Gruppe teilen / Freigaben…") },
                            leadingIcon = { Icon(Icons.Filled.QrCode2, contentDescription = null) },
                            onClick = { dismiss(); onOpenShare(att) },
                            modifier = Modifier.tag("menu:share"),
                        )
                    }
                    if (!readOnly) {
                        DropdownMenuItem(
                            text = { Text("Anhang löschen") },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = { dismiss(); scope.launch { withContext(Dispatchers.IO) { repo.deleteNode(att.nodeId) }; onClose() } },
                            modifier = Modifier.tag("menu:delete-attachment"),
                        )
                    }
                } else null,
                sourceMode = sourceMode,
                onEditToggle = if (!readOnly) {
                    { if (sourceMode) { save(); sourceMode = false } else sourceMode = true }
                } else null,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            val fq = findQuery
            if (fq != null) {
                FindBar(
                    query = fq,
                    onQuery = { findQuery = it; matchIdx = 0 },
                    label = if (matchCount == 0) "0/0" else "${matchIdx + 1}/$matchCount",
                    hasMatches = matchCount > 0,
                    onPrev = { stepMatch(-1) },
                    onNext = { stepMatch(1) },
                )
            }
            // ---- Beschreibung (Titel + Markdown) – höhenbegrenzt + scrollbar; der Anhang darunter
            // bekommt den ganzen Rest (weight(1f)). Titel bleibt immer sichtbar, der Markdown-Body
            // ist über das Ausklapp-Chevron einklappbar (klappt beim Reinzoomen automatisch ein). ----
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = if (sourceMode) Dp.Unspecified else 240.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (sourceMode) {
                    MarkdownEditField(
                        value = tfv,
                        onValueChange = { tfv = it },
                        fieldModifier = Modifier.heightIn(min = 100.dp).padding(8.dp).tag("field:body"),
                    )
                } else {
                    MarkdownRenderHeader(
                        text = tfv.text,
                        expanded = headerExpanded,
                        onExpandedChange = { headerExpanded = it },
                        modifier = Modifier.padding(horizontal = 12.dp),
                        emptyTitle = null,
                        highlight = findQuery?.takeIf { it.isNotBlank() },
                        onToggleTask = if (readOnly) null else ::toggleTask,
                        onEditAt = if (readOnly) null else { _ -> sourceMode = true },
                    )
                }
            }

            // ---- Fixer Anhang: Bild (Pinch-to-Zoom) bzw. Datei-Zeile ----
            var menuOpen by remember { mutableStateOf(false) }
            if (att.kind == NodeKind.IMAGE && att.blobHash != null) {
                val bmp = rememberBlobBitmap(blobStore, att.blobHash!!, preferFull = true)
                var scale by remember { mutableStateOf(1f) }
                var offset by remember { mutableStateOf(Offset.Zero) }
                // Anzahl aufliegender Finger: Long-Press (Menü) nur bei höchstens einem Finger zulassen,
                // sonst feuert er beim Still-Halten am Maximal-Zoom und blockiert das Rauszoomen.
                var pointerCount by remember { mutableStateOf(0) }
                Box(
                    Modifier.fillMaxWidth().weight(1f)
                        // Gezoomtes Bild in der Box halten: sonst läuft es über die obere Kante in den
                        // scrollbaren Beschreibungs-Bereich – dort landen dann die Finger statt beim Bild
                        // und das Rauszoomen wird unmöglich (Hit-Testing folgt den Layout-Grenzen).
                        .clipToBounds()
                        .pointerInput(att.blobHash) {
                            awaitPointerEventScope {
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    pointerCount = ev.changes.count { it.pressed }
                                }
                            }
                        }
                        .pointerInput(att.blobHash) {
                            detectTapGestures(
                                onDoubleTap = { scale = 1f; offset = Offset.Zero; headerExpanded = true },
                                onLongPress = { if (pointerCount <= 1) menuOpen = true },
                            )
                        }
                        .pointerInput(att.blobHash) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val newScale = (scale * zoom).coerceIn(1f, 6f)
                                // Reinzoomen → Kopf einklappen (Platz fürs Bild); ganz rausgezoomt → wieder ausklappen.
                                headerExpanded = newScale <= 1f
                                scale = newScale
                                offset = if (newScale > 1f) offset + pan else Offset.Zero
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (bmp != null) {
                        Image(
                            bitmap = bmp, contentDescription = postTitle(tfv.text),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().graphicsLayer {
                                scaleX = scale; scaleY = scale
                                translationX = offset.x; translationY = offset.y
                            },
                        )
                    } else {
                        Text("🖼 (Bild nicht lokal)")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Teilen") }, onClick = { menuOpen = false; AttachmentPicker.shareExternally(context, blobStore, att) })
                        if (!readOnly) {
                            if (editTargets.size in 1..3) {
                                editTargets.forEach { t ->
                                    DropdownMenuItem(text = { Text("Bearbeiten mit ${t.label}") }, onClick = { menuOpen = false; launchEdit(t, false) })
                                }
                            } else {
                                EditMenuItem(onTap = { menuOpen = false; launchEdit(null, false) }, onLongPress = { menuOpen = false; launchEdit(null, true) })
                            }
                        }
                    }
                }
            } else {
                Box {
                    Row(
                        Modifier.fillMaxWidth()
                            .tag("attachment:file")
                            .combinedClickable(
                                onClick = { AttachmentPicker.openExternally(context, blobStore, att) },
                                onLongClick = { menuOpen = true },
                            )
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(NodeKind.FILE.uiIcon(), contentDescription = null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(att.fileName ?: att.title.ifBlank { "Datei" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            att.mime?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Teilen") }, onClick = { menuOpen = false; AttachmentPicker.shareExternally(context, blobStore, att) })
                        DropdownMenuItem(text = { Text("Öffnen") }, onClick = { menuOpen = false; AttachmentPicker.openExternally(context, blobStore, att) })
                        if (AttachmentPicker.isTextLike(att.mime)) {
                            DropdownMenuItem(text = { Text("Als Text öffnen") }, onClick = { menuOpen = false; AttachmentPicker.openAsText(context, blobStore, att) })
                        }
                    }
                }
                Spacer(Modifier.size(24.dp))
            }
        }
    }
}

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
