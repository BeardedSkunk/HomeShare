package de.beardedskunk.clipsharing

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import de.beardedskunk.clipsharing.data.BlobStore
import de.beardedskunk.clipsharing.data.Feed
import de.beardedskunk.clipsharing.data.FeedRepository
import de.beardedskunk.clipsharing.sync.SyncManager
import de.beardedskunk.clipsharing.ui.FeedListScreen
import de.beardedskunk.clipsharing.ui.FeedScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = appGraph
        graph.sync.start()
        setContent {
            ClipTheme {
                Surface {
                    AppRoot(graph.repo, graph.blobStore, graph.sync)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) appGraph.sync.stop()
    }
}

@Composable
fun ClipTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}

/** Einfache zustandsbasierte Navigation ohne zusaetzliche Navigationsbibliothek. */
@Composable
fun AppRoot(repo: FeedRepository, blobStore: BlobStore, sync: SyncManager) {
    var openFeed by remember { mutableStateOf<Feed?>(null) }
    val status by sync.status.collectAsState()
    val feed = openFeed
    if (feed == null) {
        FeedListScreen(repo = repo, statusText = status.lastMessage, onOpenFeed = { openFeed = it })
    } else {
        FeedScreen(repo = repo, blobStore = blobStore, feed = feed, onBack = { openFeed = null })
    }
}
