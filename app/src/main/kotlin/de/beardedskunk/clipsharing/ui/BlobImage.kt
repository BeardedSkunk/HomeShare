package de.beardedskunk.clipsharing.ui

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import de.beardedskunk.clipsharing.data.BlobStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Laedt ein Blob-Bild als [ImageBitmap]. [preferFull] = true nimmt das Voll-Bild,
 * sonst das Thumbnail; ist das Voll-Bild nicht lokal, wird auf das Thumbnail
 * zurueckgegriffen. Dekodierung laeuft auf dem IO-Dispatcher.
 */
@Composable
fun rememberBlobBitmap(blobStore: BlobStore, sha: String, preferFull: Boolean = false): ImageBitmap? {
    var img by remember(sha, preferFull) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(sha, preferFull) {
        img = withContext(Dispatchers.IO) {
            val file = when {
                preferFull && blobStore.hasFull(sha) -> blobStore.fullFile(sha)
                blobStore.hasThumb(sha) -> blobStore.thumbFile(sha)
                blobStore.hasFull(sha) -> blobStore.fullFile(sha)
                else -> null
            }
            if (file != null && file.exists()) {
                BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
            } else {
                null
            }
        }
    }
    return img
}
