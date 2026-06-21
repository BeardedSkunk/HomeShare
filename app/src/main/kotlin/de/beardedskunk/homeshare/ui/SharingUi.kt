package de.beardedskunk.homeshare.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import de.beardedskunk.homeshare.data.NodeState
import de.beardedskunk.homeshare.data.FeedRepository
import de.beardedskunk.homeshare.data.FeedRight
import de.beardedskunk.homeshare.data.PairingPayload
import de.beardedskunk.homeshare.data.ShareGrant
import de.beardedskunk.homeshare.sync.SyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun qrBitmap(text: String, size: Int = 640): Bitmap? =
    runCatching { BarcodeEncoder().encodeBitmap(text, BarcodeFormat.QR_CODE, size, size) }.getOrNull()

/**
 * Freigabe-Verwaltung eines EIGENEN Feeds (#10): zeigt die berechtigten Fremdgruppen mit
 * Rechten, erlaubt write/merge umzuschalten und Zugriff zu entziehen, und „Gruppe hinzufügen"
 * öffnet das QR-Pairing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedShareScreen(repo: FeedRepository, sync: SyncManager, feed: NodeState, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val revision by repo.revision.collectAsState()
    var grants by remember { mutableStateOf<List<ShareGrant>>(emptyList()) }
    var pairing by remember { mutableStateOf<PairingPayload?>(null) }
    LaunchedEffect(revision) { grants = withContext(Dispatchers.IO) { repo.feedShares(feed.nodeId) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Freigaben: ${feed.title}") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Diesen Feed mit anderen Gruppen teilen. Lesen ist immer erlaubt; Schreiben und Mergen sind zuschaltbar.", style = MaterialTheme.typography.bodySmall)
            if (grants.isEmpty()) Text("Noch keine Gruppe berechtigt.")
            for (g in grants) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(g.label, fontWeight = FontWeight.Medium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Schreiben", Modifier.weight(1f))
                            Switch(checked = g.right.canWrite(), onCheckedChange = { on ->
                                val nr = if (on) FeedRight.WRITE else FeedRight.READ
                                scope.launch { withContext(Dispatchers.IO) { repo.setShareRight(feed.nodeId, g.capId, nr) } }
                            })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Mergen (Konflikte lösen)", Modifier.weight(1f))
                            Switch(checked = g.right.canMerge(), onCheckedChange = { on ->
                                val nr = if (on) FeedRight.MERGE else FeedRight.WRITE
                                scope.launch { withContext(Dispatchers.IO) { repo.setShareRight(feed.nodeId, g.capId, nr) } }
                            })
                        }
                        TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { repo.revokeShare(feed.nodeId, g.capId) } } }) {
                            Text("Zugriff entziehen")
                        }
                    }
                }
            }
            Button(onClick = { scope.launch { pairing = withContext(Dispatchers.IO) { sync.startPairing(feed.nodeId, feed.title) } } }, modifier = Modifier.fillMaxWidth()) {
                Text("Gruppe hinzufügen (QR)")
            }
        }
    }

    pairing?.let { p ->
        PairingQrDialog(sync = sync, payload = p, onClose = { sync.cancelPairing(p.capId); pairing = null })
    }
}

/** Zeigt den QR + den Code-Text (zum Abtippen/Faken) und schließt automatisch nach erfolgreichem Pairing. */
@Composable
fun PairingQrDialog(sync: SyncManager, payload: PairingPayload, onClose: () -> Unit) {
    val code = remember(payload) { PairingPayload.encode(payload) }
    val bmp = remember(code) { qrBitmap(code) }
    // Auto-Close, sobald die Fremdgruppe gekoppelt hat.
    DisposableEffect(payload.capId) {
        val prev = sync.onPairingComplete
        sync.onPairingComplete = { capId -> if (capId == payload.capId) onClose() }
        onDispose { sync.onPairingComplete = prev }
    }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Fertig") } },
        title = { Text("Mit Fremdgruppe koppeln") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Die andere Gruppe scannt diesen QR ODER fügt den Code ein. Gültig 2 Minuten.", style = MaterialTheme.typography.bodySmall)
                if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = "QR", modifier = Modifier.size(220.dp))
                Text(code, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            }
        },
    )
}

/** „Geteilten Feed hinzufügen" auf dem Fremdgerät: QR scannen ODER Code einfügen -> abonnieren. */
@Composable
fun AddSharedFeedDialog(sync: SyncManager, onDone: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { code = it }
    }
    fun subscribe() {
        val payload = PairingPayload.parse(code.trim())
        if (payload == null) { Toast.makeText(context, "Ungültiger Code", Toast.LENGTH_SHORT).show(); return }
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { sync.pairAndSubscribe(payload) }
            busy = false
            Toast.makeText(context, if (ok) "Feed „${payload.feedName}“ abonniert" else "Kopplung fehlgeschlagen", Toast.LENGTH_LONG).show()
            if (ok) onDone()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(enabled = !busy, onClick = { subscribe() }) { Text("Abonnieren") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
        title = { Text("Geteilten Feed hinzufügen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    runCatching { scanLauncher.launch(ScanOptions().setOrientationLocked(false).setBeepEnabled(false).setPrompt("Freigabe-QR scannen")) }
                        .onFailure { Toast.makeText(context, "Scanner nicht verfügbar – Code einfügen", Toast.LENGTH_SHORT).show() }
                }) { Text("QR scannen") }
                OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("oder Code einfügen") }, modifier = Modifier.fillMaxWidth())
            }
        },
    )
}
