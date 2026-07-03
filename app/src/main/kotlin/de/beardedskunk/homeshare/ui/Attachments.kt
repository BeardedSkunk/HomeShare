package de.beardedskunk.homeshare.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.beardedskunk.homeshare.core.NodeKind
import de.beardedskunk.homeshare.core.NodeType
import de.beardedskunk.homeshare.data.BlobStore
import de.beardedskunk.homeshare.data.FeedRepository
import de.beardedskunk.homeshare.data.NodeState

/**
 * Gemeinsame Anhänge-Darstellung für Notiz-, Aufgaben- und künftige Ansichten:
 * ein Kasten „Anhänge" mit einer Zeile je Bild/Datei (Thumbnail bzw. Datei-Icon +
 * Titel = 1. Zeile der Anhang-Notiz). Tap öffnet die [AttachmentDetailScreen].
 */

/** Anhang-Knoten plus Titel seiner Beschreibungs-Notiz (Caption-TEXT-Kind). */
data class AttachmentRow(val node: NodeState, val captionTitle: String)

/** Lädt die IMAGE/FILE-Kinder von [parentId] samt Caption-Titel. IO – auf Dispatchers.IO aufrufen. */
fun loadAttachmentRows(repo: FeedRepository, parentId: String): List<AttachmentRow> =
    repo.children(parentId)
        .filter { it.type == NodeType.IMAGE || it.type == NodeType.FILE }
        .map { a ->
            val cap = repo.children(a.nodeId).firstOrNull { it.type == NodeType.TEXT }
            AttachmentRow(a, cap?.text?.lineSequence()?.firstOrNull().orEmpty())
        }

/** Anzeigename einer Anhang-Zeile. */
fun AttachmentRow.label(): String =
    captionTitle.ifBlank { node.fileName ?: node.title.ifBlank { if (node.kind == NodeKind.IMAGE) "Bild" else "Datei" } }

@Composable
fun AttachmentBox(
    attachments: List<AttachmentRow>,
    blobStore: BlobStore,
    modifier: Modifier = Modifier,
    onOpen: (AttachmentRow) -> Unit,
) {
    if (attachments.isEmpty()) return
    Card(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).tag("box:attachments")) {
        Column(Modifier.padding(vertical = 6.dp)) {
            Text(
                "Anhänge",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            for (a in attachments) AttachmentRowView(a, blobStore, onOpen = { onOpen(a) })
        }
    }
}

@Composable
fun AttachmentRowView(a: AttachmentRow, blobStore: BlobStore, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .tag(rowTag(a.label()))
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (a.node.kind == NodeKind.IMAGE && a.node.blobHash != null) {
            val bmp = rememberBlobBitmap(blobStore, a.node.blobHash, preferFull = false)
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                if (bmp != null) Image(bitmap = bmp, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Text("🖼")
            }
        } else {
            Icon(NodeKind.FILE.uiIcon(), contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(
            a.label(),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
    }
}
