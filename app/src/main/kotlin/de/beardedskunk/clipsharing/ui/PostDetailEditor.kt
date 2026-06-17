package de.beardedskunk.clipsharing.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Detail-/Editier-Ansicht eines Beitrags: langer Text (mit In-Post-Suche) plus die
 * enthaltenen Bilder im Fluss – im echten Seitenverhältnis, in der Höhe auf 20 %
 * der Bildschirmhöhe begrenzt. Bilder lassen sich antippen (Vollansicht) und über
 * das ×-Symbol aus dem Beitrag entfernen. [post] == null erzeugt einen neuen Beitrag.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    var findOpen by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var matchIdx by remember { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }

    val matches: List<Int> = remember(tfv.text, findQuery) {
        if (findQuery.isBlank()) emptyList() else findAllMatches(tfv.text, findQuery)
    }

    fun jumpTo(index: Int) {
        if (matches.isEmpty()) return
        val i = ((index % matches.size) + matches.size) % matches.size
        matchIdx = i
        val start = matches[i]
        tfv = tfv.copy(selection = TextRange(start, start + findQuery.length))
        focusRequester.requestFocus()
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val sha = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { blobStore.put(it) }
                }
                if (sha != null && sha !in images) images = images + sha
            }
        }
    }

    fun save() {
        val text = tfv.text
        scope.launch {
            withContext(Dispatchers.IO) {
                if (post == null) {
                    repo.createPost(feed.id, text, images)
                } else {
                    repo.editPost(feed.id, post.postId, text, images)
                }
            }
            onClose()
        }
    }

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
                title = { Text(if (post == null) "Neuer Eintrag" else "Bearbeiten") },
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
                        Icon(Icons.Filled.Check, contentDescription = "Speichern")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
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

            // Bilder im Fluss: echtes Seitenverhältnis, Höhe auf 20% begrenzt.
            for (sha in images) {
                Box(Modifier.fillMaxWidth().padding(8.dp)) {
                    val bmp = rememberBlobBitmap(blobStore, sha, preferFull = true)
                    if (bmp != null) {
                        Image(
                            bitmap = bmp,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = maxImageHeight)
                                .clickable { onOpenImage(sha) },
                        )
                    } else {
                        Text("🖼 (Bild nicht lokal)")
                    }
                    IconButton(
                        onClick = { images = images.filterNot { it == sha } },
                        modifier = Modifier.align(Alignment.TopEnd),
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Bild entfernen")
                    }
                }
            }
        }
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
