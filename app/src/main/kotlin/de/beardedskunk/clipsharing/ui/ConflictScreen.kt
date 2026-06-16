package de.beardedskunk.clipsharing.ui

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
import de.beardedskunk.clipsharing.core.DiffOp
import de.beardedskunk.clipsharing.core.PostContent
import de.beardedskunk.clipsharing.core.PostVersion
import de.beardedskunk.clipsharing.core.TextDiff
import de.beardedskunk.clipsharing.data.Feed
import de.beardedskunk.clipsharing.data.FeedRepository
import de.beardedskunk.clipsharing.data.PostState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Loest einen nebenlaeufigen Bearbeitungskonflikt auf: zeigt die gemeinsame Basis
 * und je konkurrierender Version einen farbigen Wort-Diff gegen die Basis. Der
 * Nutzer waehlt eine Version (oder schreibt eine eigene). Die Auswahl erzeugt eine
 * Merge-Version, wodurch der Konflikt fuer alle Geraete erledigt ist.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictScreen(
    repo: FeedRepository,
    feed: Feed,
    post: PostState,
    onResolved: () -> Unit,
    onCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var heads by remember { mutableStateOf<List<PostVersion>>(emptyList()) }
    var baseText by remember { mutableStateOf("") }
    var manualInitial by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(post.postId) {
        withContext(Dispatchers.IO) {
            val p = repo.history(post.postId)
            val h = p.heads()
            val base = if (h.size >= 2) p.lowestCommonAncestor(h[0].versionId, h[1].versionId) else null
            heads = h
            baseText = base?.content?.text ?: ""
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
                Text("Gemeinsame Basis", fontWeight = FontWeight.Bold)
                Text(baseText.ifBlank { "(leer)" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Wähle, welche Fassung gelten soll. Deine Wahl gilt danach für alle Geräte.",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            itemsIndexed(heads) { index, head ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Version ${'A' + index} · Gerät ${head.deviceId.take(6)}",
                            fontWeight = FontWeight.Bold,
                        )
                        if (head.content.deleted) {
                            Text("Diese Fassung löscht den Beitrag.", color = MaterialTheme.colorScheme.error)
                        } else {
                            Text(diffAnnotated(baseText, head.content.text))
                            if (head.content.imageHashes.isNotEmpty()) {
                                Text("(${head.content.imageHashes.size} Bild(er))", style = MaterialTheme.typography.labelSmall)
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
                    Text("Eigene Fassung schreiben")
                }
            }
        }
    }
}

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
