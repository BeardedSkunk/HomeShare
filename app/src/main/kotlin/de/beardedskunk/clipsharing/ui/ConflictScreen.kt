package de.beardedskunk.clipsharing.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import de.beardedskunk.clipsharing.core.DiffOp
import de.beardedskunk.clipsharing.core.PostContent
import de.beardedskunk.clipsharing.core.PostVersion
import de.beardedskunk.clipsharing.core.TextDiff
import de.beardedskunk.clipsharing.data.BlobStore
import de.beardedskunk.clipsharing.data.Feed
import de.beardedskunk.clipsharing.data.FeedRepository
import de.beardedskunk.clipsharing.data.PostState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Loest einen nebenlaeufigen Bearbeitungskonflikt auf. Zeigt die gemeinsame Basis,
 * pro konkurrierender Fassung einen verstaendlichen Vergleich (Wort-Diff beim Text,
 * Bilder als anklickbare Thumbnails) und – falls beide Fassungen dasselbe Bild
 * teilen – dieses einmal als Referenz oben. Der Nutzer waehlt eine Fassung (oder
 * schreibt eine eigene). Die Auswahl erzeugt eine Merge-Version, womit der Konflikt
 * fuer alle Geraete erledigt ist.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictScreen(
    repo: FeedRepository,
    blobStore: BlobStore,
    feed: Feed,
    post: PostState,
    onOpenImage: (String) -> Unit,
    onResolved: () -> Unit,
    onCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var heads by remember { mutableStateOf<List<PostVersion>>(emptyList()) }
    var baseText by remember { mutableStateOf("") }
    var deviceNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var manualInitial by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(post.postId) {
        withContext(Dispatchers.IO) {
            val p = repo.history(post.postId)
            val h = p.heads()
            val base = if (h.size >= 2) p.lowestCommonAncestor(h[0].versionId, h[1].versionId) else null
            heads = h
            baseText = base?.content?.text ?: ""
            deviceNames = repo.deviceNames()
        }
    }

    fun resolve(content: PostContent) {
        scope.launch {
            withContext(Dispatchers.IO) { repo.resolveConflict(feed.id, post.postId, content) }
            onResolved()
        }
    }

    val manual = manualInitial
    if (manual != null) {
        PostEditor(
            initialText = manual,
            onSave = { text -> resolve(PostContent(text = text)) },
            onDelete = { resolve(PostContent(deleted = true)) },
            onCancel = { manualInitial = null },
        )
        return
    }

    // Bilder, die in ALLEN Fassungen vorkommen -> einmal als Referenz oben zeigen.
    val liveHeads = heads.filter { !it.content.deleted }
    val commonImages: List<String> =
        if (liveHeads.size >= 2) {
            liveHeads.map { it.content.imageHashes.toSet() }
                .reduce { a, b -> a.intersect(b) }
                .toList()
        } else {
            emptyList()
        }
    val newestId = heads.lastOrNull()?.versionId

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Konflikt auflösen") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Dieser Beitrag wurde auf mehreren Geräten gleichzeitig geändert. " +
                        "Wähle, welche Fassung gelten soll – deine Wahl gilt danach für alle Geräte.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                Text("Gemeinsame Basis (vor den Änderungen)", fontWeight = FontWeight.Bold)
                Text(baseText.ifBlank { "(leer)" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (commonImages.isNotEmpty()) {
                item {
                    Text("Gemeinsames Bild (in allen Fassungen gleich)", fontWeight = FontWeight.Bold)
                    Text(
                        "Zum Vergrößern antippen.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ThumbRow(blobStore, commonImages, onOpenImage)
                }
            }
            itemsIndexed(heads) { index, head ->
                val name = deviceNames[head.deviceId]?.takeIf { it.isNotBlank() } ?: "Gerät ${head.deviceId.take(6)}"
                val newest = head.versionId == newestId
                val ownImages = head.content.imageHashes.filter { it !in commonImages }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Fassung ${'A' + index} · $name" + if (newest) "  (neueste)" else "",
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Geändert: " + formatWhen(head.hlc.wallMillis),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (head.content.deleted) {
                            Text("Diese Fassung löscht den Beitrag.", color = MaterialTheme.colorScheme.error)
                        } else {
                            // Text: unveraendert -> klar benennen; ohne gemeinsame Basis Klartext
                            // (sonst faerbt der Diff gegen "" alles gruen, was nichts aussagt);
                            // sonst Wort-Diff gegen die Basis.
                            when {
                                head.content.text == baseText ->
                                    Text("Text unverändert.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                baseText.isBlank() ->
                                    Text(head.content.text.ifBlank { "(kein Text)" })
                                else ->
                                    Text(diffAnnotated(baseText, head.content.text))
                            }
                            val titles = head.content.imageTitles.filter { it.isNotBlank() }
                            if (titles.isNotEmpty()) {
                                Text("Bildtitel: " + titles.joinToString(", "), style = MaterialTheme.typography.labelMedium)
                            }
                            if (ownImages.isNotEmpty()) {
                                Text(
                                    if (commonImages.isEmpty()) "Bild dieser Fassung (antippen für groß):"
                                    else "Nur in dieser Fassung (antippen für groß):",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                ThumbRow(blobStore, ownImages, onOpenImage)
                            } else if (head.content.imageHashes.isEmpty()) {
                                Text("Kein Bild.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Button(onClick = { resolve(head.content) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Diese Fassung behalten")
                        }
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = { manualInitial = heads.firstOrNull { !it.content.deleted }?.content?.text ?: "" },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Eigene Text-Fassung schreiben")
                }
            }
        }
    }
}

/** Reihe anklickbarer Bild-Thumbnails; Tippen oeffnet die Vollbild-Ansicht. */
@Composable
private fun ThumbRow(blobStore: BlobStore, hashes: List<String>, onOpenImage: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (sha in hashes) {
            val bmp = rememberBlobBitmap(blobStore, sha, preferFull = false)
            Box(
                Modifier.size(72.dp).clickable { onOpenImage(sha) },
                contentAlignment = Alignment.Center,
            ) {
                if (bmp != null) {
                    Image(bitmap = bmp, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Text("🖼")
                }
            }
        }
    }
}

private fun formatWhen(wallMillis: Long): String =
    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date(wallMillis))

/** Baut aus dem Wort-Diff (Basis -> Ziel) eingefaerbten Text: grün = neu, rot durchgestrichen = entfernt. */
private fun diffAnnotated(base: String, target: String): AnnotatedString = buildAnnotatedString {
    for (seg in TextDiff.diff(base, target)) {
        when (seg.op) {
            DiffOp.EQUAL -> append(seg.text)
            DiffOp.INSERT -> withStyle(SpanStyle(color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)) { append(seg.text) }
            DiffOp.DELETE -> withStyle(SpanStyle(color = Color(0xFFC62828), textDecoration = TextDecoration.LineThrough)) { append(seg.text) }
        }
    }
}
