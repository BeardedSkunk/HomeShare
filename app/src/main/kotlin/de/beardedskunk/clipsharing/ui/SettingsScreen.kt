package de.beardedskunk.clipsharing.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import de.beardedskunk.clipsharing.backup.FritzController
import de.beardedskunk.clipsharing.data.DeviceIdentity
import de.beardedskunk.clipsharing.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Einstellungen: FRITZ!Box-Zugang (FTPES) und lokales Bild-Speicherbudget.
 * Bietet "Speichern" und einen manuellen "Jetzt synchronisieren"-Knopf.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settings: Settings, identity: DeviceIdentity, fritz: FritzController, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var groupName by remember { mutableStateOf(identity.groupName) }
    var passphrase by remember { mutableStateOf(settings.groupPassphrase) }
    var host by remember { mutableStateOf(settings.fritzHost) }
    var port by remember { mutableStateOf(settings.fritzPort.toString()) }
    var user by remember { mutableStateOf(settings.fritzUser) }
    var pass by remember { mutableStateOf(settings.fritzPassword) }
    var baseDir by remember { mutableStateOf(settings.fritzBaseDir) }
    var budget by remember { mutableStateOf(settings.imageBudgetMb.toString()) }
    val status by fritz.status.collectAsState()
    var busy by remember { mutableStateOf(false) }

    fun save() {
        identity.groupName = groupName.trim().ifBlank { identity.groupName }
        settings.groupPassphrase = passphrase
        settings.fritzHost = host.trim()
        settings.fritzPort = port.toIntOrNull() ?: 21
        settings.fritzUser = user.trim()
        settings.fritzPassword = pass
        settings.fritzBaseDir = baseDir.trim().ifBlank { "/clipsharing" }
        settings.imageBudgetMb = budget.toIntOrNull() ?: 0
    }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
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
            Text("Gruppe (nur Geräte mit gleicher Passphrase syncen)", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(groupName, { groupName = it }, label = { Text("Gruppenname") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                passphrase, { passphrase = it }, label = { Text("Gruppen-Passphrase (Verschlüsselung)") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            Text("FRITZ!Box-Backup (FTPES)", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(host, { host = it }, label = { Text("Host") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                port, { port = it }, label = { Text("Port (21)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(user, { user = it }, label = { Text("FRITZ!Box-Benutzer") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                pass, { pass = it }, label = { Text("Passwort") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(baseDir, { baseDir = it }, label = { Text("Basisordner") }, modifier = Modifier.fillMaxWidth())

            Text("Speicher", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                budget, { budget = it }, label = { Text("Bild-Budget in MB (0 = unbegrenzt)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text("Speichern") }
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    save()
                    busy = true
                    scope.launch {
                        withContext(Dispatchers.IO) { fritz.sync() }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "Synchronisiere…" else "Jetzt mit FRITZ!Box synchronisieren") }

            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
