package de.beardedskunk.clipsharing

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import de.beardedskunk.clipsharing.data.Feed
import de.beardedskunk.clipsharing.ui.FeedListScreen
import de.beardedskunk.clipsharing.ui.FeedScreen
import de.beardedskunk.clipsharing.ui.FeedShareScreen
import de.beardedskunk.clipsharing.ui.SettingsScreen
import de.beardedskunk.clipsharing.ui.SharePickerScreen
import de.beardedskunk.clipsharing.ui.SharedContent
import de.beardedskunk.clipsharing.sync.SyncForegroundService

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
        // AutoSync besitzt den SyncManager-Lebenszyklus: NSD/Sync laufen nur bei WLAN.
        graph.repo.onLocalChange = { graph.autoSync.trigger() }
        // JEDE Aenderung (lokal + Sync-Ingest) -> Kalender-Sync in den Android-Kalender.
        graph.repo.onAnyChange = { graph.calendarSync.requestSync() }
        // Sync über einen Vordergrund-Service halten (läuft auch im Standby weiter, W8).
        // Ist der Sync-Schalter aus, kein Service/Notification – nur AutoSync (deaktiviert sich selbst).
        if (graph.settings.syncEnabled) SyncForegroundService.start(this) else graph.autoSync.start()
        graph.calendarSync.requestSync()
        val shared = parseShared(intent)
        setContent {
            ClipTheme {
                Surface {
                    AppRoot(graph, shared)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // App im Vordergrund -> einmal synchronisieren.
        appGraph.autoSync.trigger()
    }

    // Kein autoSync.stop() mehr beim Schliessen: der Vordergrund-Service haelt den Sync am
    // Leben (W8). Beendet wird er nur ueber den Sync-Aus-Schalter (stoppt Service + Sync).

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
    var openFeedQuery by remember { mutableStateOf<String?>(null) }
    var pendingShare by remember { mutableStateOf(initialShare) }
    var showSettings by remember { mutableStateOf(false) }
    var sharingFeed by remember { mutableStateOf<Feed?>(null) }
    val status by graph.sync.status.collectAsState()
    val webUrl by graph.web.url.collectAsState()

    // Kalender-Berechtigung anfordern, sobald ein Kalender-Feed geöffnet wird.
    val calPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result -> if (result.values.any { it }) graph.calendarSync.requestSync() }
    LaunchedEffect(openFeed?.id) {
        val f = openFeed
        if (f != null && f.calendar && !graph.calendarSync.hasPermission()) {
            calPermLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
        }
    }

    val share = pendingShare
    if (share != null) {
        BackHandler { pendingShare = null }
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
        BackHandler { showSettings = false }
        SettingsScreen(
            settings = graph.settings,
            identity = graph.identity,
            fritz = graph.fritz,
            blobStore = graph.blobStore,
            onSyncEnabledChanged = { graph.autoSync.setSyncEnabled(it) },
            onBack = { showSettings = false },
        )
        return
    }

    val sharing = sharingFeed
    if (sharing != null) {
        BackHandler { sharingFeed = null }
        FeedShareScreen(repo = graph.repo, sync = graph.sync, feed = sharing, onBack = { sharingFeed = null })
        return
    }

    val feed = openFeed
    if (feed == null) {
        FeedListScreen(
            repo = graph.repo,
            sync = graph.sync,
            statusText = status.lastMessage,
            webUrl = webUrl,
            onToggleWeb = { graph.web.toggle() },
            onOpenSettings = { showSettings = true },
            onOpenShare = { sharingFeed = it },
            onOpenFeed = { f, q -> openFeed = f; openFeedQuery = q },
        )
    } else {
        FeedScreen(
            repo = graph.repo,
            blobStore = graph.blobStore,
            feed = feed,
            settings = graph.settings,
            initialQuery = openFeedQuery,
            onRequestCalendarSync = { graph.calendarSync.requestSync() },
            onBack = { openFeed = null; openFeedQuery = null },
        )
    }
}
