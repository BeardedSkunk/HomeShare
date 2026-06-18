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
        passphrase = settings.groupPassphrase,
        deviceId = identity.deviceId,
        useFtps = settings.fritzUseFtps,
    )

    /** Blockierend – vom Aufrufer auf einem IO-Thread ausfuehren. */
    fun sync(): Result<ReplicaResult> = runCatching {
        FritzReplica(config(), source, blobStore).sync()
    }.onSuccess {
        val warn = if (it.droppedOps > 0) " ⚠ ${it.droppedOps} nicht ladbar" else ""
        status.value = "FRITZ!Box ok: +${it.pulledOps} empfangen, ${it.pushedOps} Ops / ${it.pushedBlobs} Bilder gesendet$warn"
    }.onFailure {
        status.value = "FRITZ!Box-Fehler: ${it.message}"
    }

    /**
     * Verbindung testen, Ordner anlegen UND sofort einmal synchronisieren – damit
     * man direkt sieht, ob Daten zur Box und zurueck wandern. Liefert eine
     * menschenlesbare Erfolgs-/Fehlermeldung (fuer einen Toast).
     * Blockierend – auf IO-Thread aufrufen.
     */
    fun testAndSync(): Result<String> = runCatching {
        val r = FritzReplica(config(), source, blobStore).sync()
        "Backup läuft. Gesendet: ${r.pushedOps} Einträge / ${r.pushedBlobs} Bilder · " +
            "Empfangen: ${r.pulledOps} Einträge / ${r.pulledBlobs} Bilder." +
            if (r.droppedOps > 0) " ⚠ ${r.droppedOps} Einträge nicht ladbar (FTP-Abbruch) – erneut versuchen." else ""
    }
}
