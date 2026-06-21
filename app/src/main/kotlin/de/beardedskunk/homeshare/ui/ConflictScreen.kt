package de.beardedskunk.homeshare.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import de.beardedskunk.homeshare.core.DiffOp
import de.beardedskunk.homeshare.core.NodeContent
import de.beardedskunk.homeshare.core.NodeVersion
import de.beardedskunk.homeshare.core.TextDiff
import de.beardedskunk.homeshare.data.BlobStore
import de.beardedskunk.homeshare.data.FeedRepository
import de.beardedskunk.homeshare.data.NodeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Löst einen nebenläufigen Bearbeitungskonflikt EINES Knotens auf. Im Knoten-Baum hat ein
 * Text-Eintrag nur Text (Bilder/Dateien sind eigene Kindknoten mit eigener Historie), daher geht es
 * hier um die Wahl der Text-Fassung (Wort-Diff gegen die gemeinsame Basis) bzw. Löschen. Die Auswahl
 * erzeugt eine Merge-Version → der Konflikt ist für alle Geräte erledigt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictScreen(
    repo: FeedRepository,
    @Suppress("UNUSED_PARAMETER") blobStore: BlobStore,
    @Suppress("UNUSED_PARAMETER") feed: NodeState,
    post: NodeState,
    @Suppress("UNUSED_PARAMETER") onOpenImage: (String) -> Unit,
    onResolved: () -> Unit,
    onCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var heads by remember { mutableStateOf<List<NodeVersion>>(emptyList()) }
    var baseText by remember { mutableStateOf("") }
    var deviceNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var manualInitial by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(post.nodeId) {
        withContext(Dispatchers.IO) {
            val p = repo.history(post.nodeId)
            val h = p.heads()
            val base = if (h.size >= 2) p.lowestCommonAncestor(h[0].versionId, h[1].versionId) else null
            heads = h
            baseText = base?.content?.text ?: ""
            deviceNames = repo.deviceNames()
        }
    }

    fun resolve(content: NodeContent) {
        scope.launch {
            withContext(Dispatchers.IO) { repo.resolveConflict(post.nodeId, content) }
            onResolved()
        }
    }

    val manual = manualInitial
    if (manual != null) {
        val baseContent = heads.firstOrNull { !it.content.deleted }?.content ?: NodeContent(parentId = post.parentId)
        PostEditor(
            initialText = manual,
            onSave = { text -> resolve(baseContent.copy(text = text, deleted = false)) },
            onDelete = { resolve(baseContent.copy(deleted = true)) },
            onCancel = { manualInitial = null },
        )
        return
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
                    "Dieser Eintrag wurde auf mehreren Geräten gleichzeitig geändert. " +
                        "Wähle, welche Fassung gelten soll – deine Wahl gilt danach für alle Geräte.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                Text("Gemeinsame Basis (vor den Änderungen)", fontWeight = FontWeight.Bold)
                Text(baseText.ifBlank { "(leer)" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            itemsIndexed(heads) { index, head ->
                val name = deviceNames[head.deviceId]?.takeIf { it.isNotBlank() } ?: "Gerät ${head.deviceId.take(6)}"
                val newest = head.versionId == newestId
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Fassung ${'A' + index} · $name" + if (newest) "  (neueste)" else "", fontWeight = FontWeight.Bold)
                        Text(
                            "Geändert: " + formatWhen(head.hlc.wallMillis),
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                        )
                        if (head.content.deleted) {
                            Text("Diese Fassung löscht den Eintrag.", color = MaterialTheme.colorScheme.error)
                        } else when {
                            head.content.text == baseText -> Text("Text unverändert.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            baseText.isBlank() -> Text(head.content.text.ifBlank { "(kein Text)" })
                            else -> Text(diffAnnotated(baseText, head.content.text))
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
                ) { Text("Eigene Text-Fassung schreiben") }
            }
        }
    }
}

private fun formatWhen(wallMillis: Long): String =
    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date(wallMillis))

/** Wort-Diff (Basis -> Ziel) eingefärbt: grün = neu, rot durchgestrichen = entfernt. */
private fun diffAnnotated(base: String, target: String): AnnotatedString = buildAnnotatedString {
    for (seg in TextDiff.diff(base, target)) {
        when (seg.op) {
            DiffOp.EQUAL -> append(seg.text)
            DiffOp.INSERT -> withStyle(SpanStyle(color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)) { append(seg.text) }
            DiffOp.DELETE -> withStyle(SpanStyle(color = Color(0xFFC62828), textDecoration = TextDecoration.LineThrough)) { append(seg.text) }
        }
    }
}
