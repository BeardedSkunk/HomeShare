package de.beardedskunk.clipsharing.ui

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import de.beardedskunk.clipsharing.data.BlobStore

/**
 * Vollbild-Ansicht eines Bildes mit Teilen-Funktion (Android-Share-Menü via
 * FileProvider). Zeigt das Voll-Bild, falls lokal vorhanden, sonst das Thumbnail.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(blobStore: BlobStore, sha: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val bitmap = rememberBlobBitmap(blobStore, sha, preferFull = true)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bild") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val file = if (blobStore.hasFull(sha)) blobStore.fullFile(sha) else blobStore.thumbFile(sha)
                        if (file.exists()) {
                            val uri = FileProvider.getUriForFile(
                                context,
                                context.packageName + ".fileprovider",
                                file,
                            )
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "image/*"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(send, "Bild teilen"))
                        }
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = "Teilen")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text("Bild nicht lokal verfügbar")
            }
        }
    }
}
