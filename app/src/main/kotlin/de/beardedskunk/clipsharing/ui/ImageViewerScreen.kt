package de.beardedskunk.clipsharing.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.beardedskunk.clipsharing.data.BlobStore

/**
 * Vollbild-Ansicht eines Bildes mit Pinch-to-Zoom (Doppeltipp setzt zurück).
 *
 * Die Aktions-Icons oben rechts erscheinen nur, wenn der jeweilige Callback gesetzt
 * ist – in den Merge-/Konflikt-Ansichten werden Bearbeiten/Löschen daher ausgeblendet.
 * [title] zeigt (gekürzt) den Bildtitel; ist er null/leer, bleibt die Leiste titellos.
 * Beim Edit-Icon: Tippen = System-Default bzw. Chooser, langes Drücken erzwingt den Chooser.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ImageViewerScreen(
    blobStore: BlobStore,
    sha: String,
    onBack: () -> Unit,
    title: String? = null,
    onShare: (() -> Unit)? = null,
    onEdit: ((forceChooser: Boolean) -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val bitmap = rememberBlobBitmap(blobStore, sha, preferFull = true)
    var scale by remember(sha) { mutableStateOf(1f) }
    var offset by remember(sha) { mutableStateOf(Offset.Zero) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (!title.isNullOrBlank()) {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    if (onShare != null) {
                        IconButton(onClick = onShare) {
                            Icon(Icons.Filled.Share, contentDescription = "Teilen")
                        }
                    }
                    if (onEdit != null) {
                        // Tippen = Default/Chooser, lang drücken = Chooser erzwingen.
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .combinedClickable(
                                    onClick = { onEdit(false) },
                                    onLongClick = { onEdit(true) },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.Edit, contentDescription = "Bearbeiten")
                        }
                    }
                    if (onDelete != null) {
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Filled.Delete, contentDescription = "Löschen")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .pointerInput(sha) {
                    detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero })
                }
                .pointerInput(sha) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        offset = if (scale > 1f) offset + pan else Offset.Zero
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = title,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text("Bild nicht lokal verfügbar")
            }
        }
    }
}
