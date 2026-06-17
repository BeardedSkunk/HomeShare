package de.beardedskunk.clipsharing.ui

import android.content.ComponentName
import android.content.Intent
import android.provider.MediaStore
import android.widget.Toast
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
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
 * Detail-/Editier-Ansicht eines Beitrags: langer Text (mit In-Post-Suche) plus die
 * enthaltenen Bilder im Fluss – im echten Seitenverhältnis, in der Höhe auf 20 %
 * der Bildschirmhöhe begrenzt. Bilder lassen sich antippen (Vollansicht) und über
 * das ×-Symbol aus dem Beitrag entfernen. [post] == null erzeugt einen neuen Beitrag.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PostDetailEditor(
    repo: FeedRepository,
    blobStore: BlobStore,
    feed: Feed,
    post: PostState?,
    onOpenImage: (String) -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val maxImageHeight = (LocalConfiguration.current.screenHeightDp * 0.2f).dp

    var tfv by remember { mutableStateOf(TextFieldValue(post?.text ?: "")) }
    var images by remember { mutableStateOf(post?.imageHashes ?: emptyList()) }
    // Bildtitel als TextFieldValue, damit die Suche eine Auswahl/den Cursor setzen kann.
    var imageTitles by remember {
        mutableStateOf((post?.imageHashes ?: emptyList()).indices.map { i -> TextFieldValue(post?.imageTitles?.getOrNull(i) ?: "") })
    }
    // Gespeicherter Stand (fuer gruener/grauer Haken) + aktuelle Post-Id (fuer Folge-Speicherungen).
    var currentPostId by remember { mutableStateOf(post?.postId) }
    var savedText by remember { mutableStateOf(post?.text ?: "") }
    var savedImages by remember { mutableStateOf(post?.imageHashes ?: emptyList()) }
    var savedTitles by remember {
        mutableStateOf((post?.imageHashes ?: emptyList()).indices.map { i -> post?.imageTitles?.getOrNull(i) ?: "" })
    }
    var findOpen by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var matchIdx by remember { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    // Fokus-Anker je Bildtitel-Feld (Neuanlage bei Aenderung der Bildanzahl -> Felder neu verbunden).
    val titleFocusers = remember(images.size) { List(images.size) { FocusRequester() } }

    // Treffer ueber Text UND Bildtitel: target == -1 -> Haupttext, sonst Bildtitel-Index.
    val matches: List<FindHit> = remember(tfv.text, imageTitles, findQuery) {
        if (findQuery.isBlank()) {
            emptyList()
        } else {
            buildList {
                findAllMatches(tfv.text, findQuery).forEach { add(FindHit(-1, it)) }
                imageTitles.forEachIndexed { i, t -> findAllMatches(t.text, findQuery).forEach { add(FindHit(i, it)) } }
            }
        }
    }

    fun jumpTo(index: Int) {
        if (matches.isEmpty()) return
        val i = ((index % matches.size) + matches.size) % matches.size
        matchIdx = i
        val hit = matches[i]
        val range = TextRange(hit.start, hit.start + findQuery.length)
        if (hit.target < 0) {
            tfv = tfv.copy(selection = range)
            focusRequester.requestFocus()
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

    // --- Bild-Aktionen (Long-Press-Menü pro Bild) ---
    val editTargets = remember { imageEditTargets(context) }
    var imageMenuFor by remember { mutableStateOf<Int?>(null) }
    var pendingEdit by remember { mutableStateOf<PendingEdit?>(null) }
    val authority = remember { context.packageName + ".fileprovider" }

    // Ergebnis eines externen Editors: bevorzugt die zurückgegebene URI, sonst die
    // (ggf. überschriebene) Temp-Datei. Weicht das Bild ab, ersetzt es unser Bild.
    val editLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val pe = pendingEdit ?: return@rememberLauncherForActivityResult
        pendingEdit = null
        scope.launch {
            val newSha = withContext(Dispatchers.IO) {
                val candidates = buildList {
                    result.data?.data?.let { u ->
                        runCatching { context.contentResolver.openInputStream(u)?.use { it.readBytes() } }.getOrNull()?.let { add(it) }
                    }
                    runCatching { if (pe.tempFile.exists()) pe.tempFile.readBytes() else null }.getOrNull()?.let { add(it) }
                }
                var picked: String? = null
                for (b in candidates) if (b.isNotEmpty()) {
                    val s = blobStore.put(b)
                    if (s != pe.originalSha) { picked = s; break }
                }
                runCatching { pe.tempFile.delete() }
                picked
            }
            if (newSha != null && pe.index < images.size) {
                images = images.toMutableList().also { it[pe.index] = newSha }
                Toast.makeText(context, "Bild aktualisiert – zum Sichern speichern (✓).", Toast.LENGTH_SHORT).show()
            } else if (newSha == null) {
                Toast.makeText(context, "Keine Änderung übernommen.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // target == null -> impliziter Intent (System-Default bzw. Resolver); forceChooser
    // erzwingt den Auswahldialog trotz gesetztem Default.
    fun launchEdit(index: Int, sha: String, target: EditTarget?, forceChooser: Boolean) {
        val full = blobStore.readFull(sha)
        if (full == null) {
            Toast.makeText(context, "Vollbild nicht lokal – erst antippen zum Laden.", Toast.LENGTH_SHORT).show()
            return
        }
        // Editierbare Kopie unter thumbs/ (vom FileProvider freigegeben, wird nicht gesynct).
        val tmp = blobStore.thumbFile("_edit_${System.currentTimeMillis()}_$index.png")
        runCatching { tmp.writeBytes(full) }.onFailure {
            Toast.makeText(context, "Konnte Bild nicht vorbereiten.", Toast.LENGTH_SHORT).show(); return
        }
        val uri = FileProvider.getUriForFile(context, authority, tmp)
        val base = Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(uri, "image/png")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
        }
        val toLaunch = when {
            target != null -> Intent(base).setComponent(ComponentName(target.pkg, target.cls))
            forceChooser -> Intent.createChooser(base, "Bearbeiten mit…").apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            else -> base
        }
        pendingEdit = PendingEdit(index, tmp, sha)
        runCatching { editLauncher.launch(toLaunch) }.onFailure {
            pendingEdit = null
            runCatching { tmp.delete() }
            Toast.makeText(context, "Keine App zum Bearbeiten gefunden.", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareImage(sha: String) {
        val file: File? = when {
            blobStore.hasFull(sha) -> blobStore.fullFile(sha)
            blobStore.hasThumb(sha) -> blobStore.thumbFile(sha)
            else -> null
        }
        if (file == null) {
            Toast.makeText(context, "Bild nicht lokal.", Toast.LENGTH_SHORT).show(); return
        }
        val uri = FileProvider.getUriForFile(context, authority, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(send, "Bild teilen")) }
    }

    // Speichert, bleibt aber in der Ansicht (Haken wird gruen, da Stand = gespeichert).
    fun save() {
        val text = tfv.text
        val imgs = images
        val titles = imageTitles.map { it.text }
        scope.launch {
            val pid = currentPostId
            val newId = withContext(Dispatchers.IO) {
                if (pid == null) {
                    repo.createPost(feed.id, text, imgs, titles).postId
                } else {
                    repo.editPost(feed.id, pid, text, imgs, titles)
                    pid
                }
            }
            currentPostId = newId
            savedText = text
            savedImages = imgs
            savedTitles = titles
        }
    }

    val dirty = tfv.text != savedText || images != savedImages || imageTitles.map { it.text } != savedTitles

    fun delete() {
        if (post == null) { onClose(); return }
        scope.launch {
            withContext(Dispatchers.IO) { repo.deletePost(feed.id, post.postId) }
            onClose()
        }
    }

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
                    IconButton(onClick = { findOpen = !findOpen }) {
                        Icon(Icons.Filled.Search, contentDescription = "Im Text suchen")
                    }
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
                    IconButton(onClick = { save() }) {
                        // Grau bei lokalen Aenderungen; gruen UND groesser/dicker, sobald der
                        // angezeigte Stand gespeichert ist (faellt deutlicher auf).
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "Speichern",
                            tint = if (dirty) androidx.compose.ui.graphics.Color.Gray else androidx.compose.ui.graphics.Color(0xFF2E7D32),
                            modifier = if (dirty) Modifier.size(24.dp) else Modifier.size(34.dp),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()),
        ) {
            if (findOpen) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OutlinedTextField(
                        value = findQuery,
                        onValueChange = { findQuery = it; matchIdx = 0 },
                        placeholder = { Text("Suchen…") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Text(if (matches.isEmpty()) "0/0" else "${matchIdx + 1}/${matches.size}")
                    IconButton(enabled = matches.isNotEmpty(), onClick = { jumpTo(matchIdx - 1) }) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Vorheriger Treffer")
                    }
                    IconButton(enabled = matches.isNotEmpty(), onClick = { jumpTo(matchIdx + 1) }) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Nächster Treffer")
                    }
                }
            }

            OutlinedTextField(
                value = tfv,
                onValueChange = { tfv = it },
                placeholder = { Text("Text…") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp)
                    .padding(8.dp)
                    .focusRequester(focusRequester),
            )

            // Bilder im Fluss: echtes Seitenverhältnis, Höhe auf 20% begrenzt,
            // darunter je ein editierbarer Titel (versioniert + gesynct).
            images.forEachIndexed { index, sha ->
                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                    Box(Modifier.fillMaxWidth()) {
                        val bmp = rememberBlobBitmap(blobStore, sha, preferFull = true)
                        // Tippen = groß ansehen; lang drücken = Aktionsmenü (ansehen/bearbeiten/teilen/löschen).
                        val imgModifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxImageHeight)
                            .combinedClickable(
                                onClick = { onOpenImage(sha) },
                                onLongClick = { imageMenuFor = index },
                            )
                        if (bmp != null) {
                            Image(
                                bitmap = bmp,
                                contentDescription = imageTitles.getOrNull(index)?.text,
                                contentScale = ContentScale.Fit,
                                modifier = imgModifier,
                            )
                        } else {
                            Text("🖼 (Bild nicht lokal)", modifier = imgModifier)
                        }
                        IconButton(
                            onClick = {
                                images = images.toMutableList().also { it.removeAt(index) }
                                imageTitles = imageTitles.toMutableList().also { if (index < it.size) it.removeAt(index) }
                            },
                            modifier = Modifier.align(Alignment.TopEnd),
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Bild entfernen")
                        }
                        DropdownMenu(expanded = imageMenuFor == index, onDismissRequest = { imageMenuFor = null }) {
                            DropdownMenuItem(
                                text = { Text("Öffnen zum Anschauen") },
                                onClick = { imageMenuFor = null; onOpenImage(sha) },
                            )
                            if (editTargets.size in 1..3) {
                                // Bis zu drei Editoren direkt anbieten.
                                editTargets.forEach { t ->
                                    DropdownMenuItem(
                                        text = { Text("Bearbeiten mit ${t.label}") },
                                        onClick = { imageMenuFor = null; launchEdit(index, sha, t, forceChooser = false) },
                                    )
                                }
                            } else {
                                // 0 oder >3 Editoren: ein Knopf – Tippen = Default/Chooser,
                                // lang drücken = Chooser erzwingen.
                                EditMenuItem(
                                    onTap = { imageMenuFor = null; launchEdit(index, sha, null, forceChooser = false) },
                                    onLongPress = { imageMenuFor = null; launchEdit(index, sha, null, forceChooser = true) },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Teilen") },
                                onClick = { imageMenuFor = null; shareImage(sha) },
                            )
                            DropdownMenuItem(
                                text = { Text("Löschen") },
                                onClick = {
                                    imageMenuFor = null
                                    images = images.toMutableList().also { if (index < it.size) it.removeAt(index) }
                                    imageTitles = imageTitles.toMutableList().also { if (index < it.size) it.removeAt(index) }
                                },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = imageTitles.getOrElse(index) { TextFieldValue("") },
                        onValueChange = { v ->
                            imageTitles = imageTitles.toMutableList().also {
                                while (it.size <= index) it.add(TextFieldValue(""))
                                it[index] = v
                            }
                        },
                        label = { Text("Bildtitel") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(titleFocusers.getOrNull(index)?.let { Modifier.focusRequester(it) } ?: Modifier),
                    )
                }
            }
        }
    }
}

/** Ein Suchtreffer: [target] == -1 -> Haupttext, sonst Index des Bildtitel-Felds. */
private data class FindHit(val target: Int, val start: Int)

/** Laufende externe Bearbeitung: welches Bild, Temp-Datei und Original-SHA (für Vergleich). */
private data class PendingEdit(val index: Int, val tempFile: File, val originalSha: String)

/**
 * Menüeintrag „Bearbeiten" mit Doppelfunktion: Tippen öffnet den Standard-Editor
 * (bzw. den System-Chooser, wenn kein Default gesetzt ist), langes Drücken erzwingt
 * den Chooser. Eigene Zeile, da [DropdownMenuItem] kein Long-Press unterstützt.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EditMenuItem(onTap: () -> Unit, onLongPress: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text("Bearbeiten")
        Text(
            "lang drücken: App wählen",
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Alle Start-Indizes von [needle] in [haystack] (case-insensitive, ueberlappungsfrei). */
private fun findAllMatches(haystack: String, needle: String): List<Int> {
    if (needle.isEmpty()) return emptyList()
    val out = ArrayList<Int>()
    var from = 0
    while (true) {
        val idx = haystack.indexOf(needle, from, ignoreCase = true)
        if (idx < 0) break
        out += idx
        from = idx + needle.length
    }
    return out
}
