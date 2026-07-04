package de.beardedskunk.homeshare

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.rememberSaveable
import de.beardedskunk.homeshare.data.NodeState
import de.beardedskunk.homeshare.ui.ListScreen
import de.beardedskunk.homeshare.ui.FeedShareScreen
import de.beardedskunk.homeshare.ui.SettingsScreen
import de.beardedskunk.homeshare.ui.SharePickerScreen
import de.beardedskunk.homeshare.ui.SharedContent
import de.beardedskunk.homeshare.sync.SyncForegroundService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Helle System-Leisten (dunkle Icons) – unsere UI ist hell, sonst sind
        // Status-/Navigationsleisten-Icons auf hellem Grund unlesbar.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // Edge-to-Edge (decorFitsSystemWindows=false) pant das Fenster bei offener Tastatur
        // sonst nach oben (Default ~adjustPan) und kollidiert mit imePadding() -> TopAppBar
        // fliegt raus, der Cursor verschwindet hinter der Tastatur. adjustResize -> nur die
        // IME-Inset wird gemeldet, imePadding() federt sie sauber ab.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
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
                // testTags als resource-id im uiautomator-Dump sichtbar machen
                // (automatisierte UI-Pruefung ohne Pixelsuche).
                @OptIn(ExperimentalComposeUiApi::class)
                Surface(Modifier.semantics { testTagsAsResourceId = true }) {
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
    // Navigations-Stack der geöffneten Listen als nodeIds (leer = Wurzel „Feeds"). Erlaubt Listen in
    // Listen. Nur IDs, damit der Stack per rememberSaveable einen Config-Change (z. B. Drehen) überlebt –
    // sonst wirft die neu erstellte Activity zurück zur Wurzel. Die NodeState-Objekte rekonstruieren wir
    // aus dem Graph.
    val navIds = rememberSaveable { mutableStateListOf<String>() }
    // EINE geteilte Suche über alle Ebenen: null = zu, sonst offen. Beim Zurückgehen bleibt sie erhalten.
    var searchQuery by remember { mutableStateOf<String?>(null) }
    var pendingShare by remember { mutableStateOf(initialShare) }
    var showSettings by remember { mutableStateOf(false) }
    var sharingFeed by remember { mutableStateOf<NodeState?>(null) }
    val status by graph.sync.status.collectAsState()
    val webUrl by graph.web.url.collectAsState()

    // Kalender-Berechtigung anfordern, sobald ein Kalender-Feed geöffnet wird.
    val calPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result -> if (result.values.any { it }) graph.calendarSync.requestSync() }
    // Aktuellen Knoten aus dem Graph rekonstruieren; gelöschter/unbekannter Knoten ⇒ null (= Wurzel).
    val current: NodeState? = navIds.lastOrNull()?.let { graph.repo.getNode(it) }
    LaunchedEffect(current?.nodeId) {
        if (current != null && current.isCalendarFeed && !graph.calendarSync.hasPermission()) {
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
            onShared = { feed -> pendingShare = null; navIds.clear(); navIds.add(feed.nodeId) },
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
            statusText = status.lastMessage,
            webUrl = webUrl,
            onToggleWeb = { graph.web.toggle() },
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

    ListScreen(
        repo = graph.repo,
        blobStore = graph.blobStore,
        sync = graph.sync,
        settings = graph.settings,
        container = current,
        onOpenSettings = { showSettings = true },
        onOpenShare = { sharingFeed = it },
        onOpenList = { navIds.add(it.nodeId) },
        onRequestCalendarSync = { graph.calendarSync.requestSync() },
        searchQuery = searchQuery,
        onSearchQueryChange = { searchQuery = it },
        onBack = { if (navIds.isNotEmpty()) navIds.removeAt(navIds.lastIndex) },
    )
}
