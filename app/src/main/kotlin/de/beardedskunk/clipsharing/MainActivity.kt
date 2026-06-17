package de.beardedskunk.clipsharing

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import de.beardedskunk.clipsharing.data.Feed
import de.beardedskunk.clipsharing.ui.FeedListScreen
import de.beardedskunk.clipsharing.ui.FeedScreen
import de.beardedskunk.clipsharing.ui.SettingsScreen
import de.beardedskunk.clipsharing.ui.SharePickerScreen
import de.beardedskunk.clipsharing.ui.SharedContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Helle System-Leisten (dunkle Icons) – unsere UI ist hell, sonst sind
        // Status-/Navigationsleisten-Icons auf hellem Grund unlesbar.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val graph = appGraph
        graph.sync.start()
        val shared = parseShared(intent)
        setContent {
            ClipTheme {
                Surface {
                    AppRoot(graph, shared)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) appGraph.sync.stop()
    }

    private fun parseShared(intent: Intent?): SharedContent? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        return if (text != null || uri != null) SharedContent(text, uri) else null
    }
}

@Composable
fun ClipTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}

/** Einfache zustandsbasierte Navigation ohne zusaetzliche Navigationsbibliothek. */
@Composable
fun AppRoot(graph: AppGraph, initialShare: SharedContent?) {
    var openFeed by remember { mutableStateOf<Feed?>(null) }
    var pendingShare by remember { mutableStateOf(initialShare) }
    var showSettings by remember { mutableStateOf(false) }
    val status by graph.sync.status.collectAsState()
    val webUrl by graph.web.url.collectAsState()

    val share = pendingShare
    if (share != null) {
        SharePickerScreen(
            repo = graph.repo,
            blobStore = graph.blobStore,
            shared = share,
            onShared = { feed -> pendingShare = null; openFeed = feed },
            onCancel = { pendingShare = null },
        )
        return
    }

    if (showSettings) {
        SettingsScreen(
            settings = graph.settings,
            identity = graph.identity,
            fritz = graph.fritz,
            blobStore = graph.blobStore,
            onBack = { showSettings = false },
        )
        return
    }

    val feed = openFeed
    if (feed == null) {
        FeedListScreen(
            repo = graph.repo,
            statusText = status.lastMessage,
            webUrl = webUrl,
            onToggleWeb = { graph.web.toggle() },
            onOpenSettings = { showSettings = true },
            onOpenFeed = { openFeed = it },
        )
    } else {
        FeedScreen(repo = graph.repo, blobStore = graph.blobStore, feed = feed, onBack = { openFeed = null })
    }
}
