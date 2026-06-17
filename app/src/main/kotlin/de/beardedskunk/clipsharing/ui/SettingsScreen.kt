package de.beardedskunk.clipsharing.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import de.beardedskunk.clipsharing.backup.FritzController
import de.beardedskunk.clipsharing.data.BlobStore
import de.beardedskunk.clipsharing.data.DeviceIdentity
import de.beardedskunk.clipsharing.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Einstellungen: Gruppe (Name + Passphrase), FRITZ!Box-Backup (FTP, Standard
 * Klartext; Passwort im Klartext sichtbar; Test-/Einricht-Button) und Speicher
 * (belegt/frei in GB, Bild-Budget in GB).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    identity: DeviceIdentity,
    fritz: FritzController,
    blobStore: BlobStore,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var deviceName by remember { mutableStateOf(identity.deviceName) }
    var groupName by remember { mutableStateOf(identity.groupName) }
    var passphrase by remember { mutableStateOf(settings.groupPassphrase) }
    var host by remember { mutableStateOf(settings.fritzHost) }
    var port by remember { mutableStateOf(settings.fritzPort.toString()) }
    var user by remember { mutableStateOf(settings.fritzUser) }
    var pass by remember { mutableStateOf(settings.fritzPassword) }
    var baseDir by remember { mutableStateOf(settings.fritzBaseDir) }
    var useFtps by remember { mutableStateOf(settings.fritzUseFtps) }
    var budget by remember {
        mutableStateOf(if (settings.imageBudgetGb > 0f) settings.imageBudgetGb.toString().replace('.', ',') else "")
    }
    var busy by remember { mutableStateOf(false) }

    var usedBytes by remember { mutableStateOf(0L) }
    var freeBytes by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        val used = withContext(Dispatchers.IO) { blobStore.totalFullBytes() }
        val free = withContext(Dispatchers.IO) { context.filesDir.usableSpace }
        usedBytes = used
        freeBytes = free
    }

    fun save() {
        identity.deviceName = deviceName.trim().ifBlank { identity.deviceName }
        identity.groupName = groupName.trim().ifBlank { identity.groupName }
        settings.groupPassphrase = passphrase
        settings.fritzHost = host.trim()
        settings.fritzPort = port.toIntOrNull() ?: 21
        settings.fritzUser = user.trim()
        settings.fritzPassword = pass
        settings.fritzBaseDir = baseDir.trim().ifBlank { "/clipsharing" }
        settings.fritzUseFtps = useFtps
        settings.imageBudgetGb = budget.replace(',', '.').toFloatOrNull() ?: 0f
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Dieses Gerät", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                deviceName, { deviceName = it }, label = { Text("Gerätename (z. B. F101, Pixel)") },
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Gruppe (nur Geräte mit gleicher Passphrase syncen)", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(groupName, { groupName = it }, label = { Text("Gruppenname") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                passphrase, { passphrase = it }, label = { Text("Gruppen-Passphrase (Klartext)") },
                modifier = Modifier.fillMaxWidth(),
            )

            Text("FRITZ!Box-Backup", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(host, { host = it }, label = { Text("Host (z. B. fritz.box)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                port, { port = it }, label = { Text("Port (21)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(user, { user = it }, label = { Text("FRITZ!Box-Benutzer") }, modifier = Modifier.fillMaxWidth())
            // Passwort bewusst im Klartext sichtbar (kein PasswordVisualTransformation).
            OutlinedTextField(pass, { pass = it }, label = { Text("Passwort (Klartext)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(baseDir, { baseDir = it }, label = { Text("Basisordner") }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "FTPS (verschlüsselt) – bei FRITZ!Box meist NICHT nutzbar, aus lassen",
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = useFtps, onCheckedChange = { useFtps = it })
            }

            Button(
                enabled = !busy,
                onClick = {
                    save()
                    busy = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { fritz.testAndSync() }
                        busy = false
                        val msg = result.getOrElse { "FRITZ!Box-Fehler: ${it.message}" }
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "Teste & synchronisiere…" else "Verbindung testen & jetzt sichern") }

            Text("Speicher", style = MaterialTheme.typography.titleMedium)
            Text("Bilder lokal belegt: ${gb(usedBytes)} · Frei auf Gerät: ${gb(freeBytes)}")
            OutlinedTextField(
                budget, { budget = it }, label = { Text("Bild-Budget in GB (0 = unbegrenzt)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )

            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text("Speichern") }
        }
    }
}

/**
 * Anzeige in GB mit angepasster Genauigkeit: ab 0,5 GB eine Nachkommastelle,
 * bei kleineren Mengen so viele Stellen, dass mindestens zwei signifikante
 * Ziffern sichtbar sind (z. B. 0,0028 GB statt 0,00 GB).
 */
private fun gb(bytes: Long): String {
    val v = bytes / 1_073_741_824.0
    if (v <= 0.0) return "0 GB"
    val decimals = if (v >= 0.5) 1 else (Math.floor(-Math.log10(v)).toInt() + 2).coerceIn(2, 9)
    return String.format(Locale.GERMANY, "%.${decimals}f GB", v)
}
