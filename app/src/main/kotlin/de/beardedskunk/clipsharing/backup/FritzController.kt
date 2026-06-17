package de.beardedskunk.clipsharing.backup

import de.beardedskunk.clipsharing.data.BlobStore
import de.beardedskunk.clipsharing.data.DeviceIdentity
import de.beardedskunk.clipsharing.data.Settings
import de.beardedskunk.clipsharing.sync.OpSource
import kotlinx.coroutines.flow.MutableStateFlow

/** Orchestriert die FRITZ!Box-Replik anhand der gespeicherten Einstellungen. */
class FritzController(
    private val settings: Settings,
    private val identity: DeviceIdentity,
    private val source: OpSource,
    private val blobStore: BlobStore,
) {
    val status = MutableStateFlow("")

    private fun config() = FritzConfig(
        host = settings.fritzHost,
        port = settings.fritzPort,
        user = settings.fritzUser,
        password = settings.fritzPassword,
        baseDir = settings.fritzBaseDir,
        group = identity.groupName,
        useFtps = settings.fritzUseFtps,
    )

    /** Blockierend – vom Aufrufer auf einem IO-Thread ausfuehren. */
    fun sync(): Result<ReplicaResult> = runCatching {
        FritzReplica(config(), source, blobStore).sync()
    }.onSuccess {
        status.value = "FRITZ!Box ok: +${it.pulledOps} empfangen, ${it.pushedOps} Ops / ${it.pushedBlobs} Bilder gesendet"
    }.onFailure {
        status.value = "FRITZ!Box-Fehler: ${it.message}"
    }

    /** Verbindung testen + Ordner anlegen. Blockierend – auf IO-Thread aufrufen. */
    fun test(): Result<String> = runCatching {
        FritzReplica(config(), source, blobStore).testAndPrepare()
    }.onSuccess {
        status.value = "Erfolgreich – Backup ist eingerichtet und läuft ab jetzt automatisch.\n$it"
    }.onFailure {
        status.value = "FRITZ!Box-Test fehlgeschlagen: ${it.message}"
    }
}
