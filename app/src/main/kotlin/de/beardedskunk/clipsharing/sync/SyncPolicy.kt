package de.beardedskunk.clipsharing.sync

/** Was bei einem Sync-Durchlauf laufen darf. */
data class SyncPlan(val syncPeers: Boolean, val syncFritz: Boolean)

/**
 * Reine Entscheidungslogik fuer den Sync – ohne Android, damit testbar.
 *
 * Harte Regeln:
 *  - **Sync-Schalter aus** -> gar nichts (Gerät nimmt sich komplett aus dem Abgleich).
 *  - **ohne WLAN** -> keinerlei Sync-Versuche (weder Gerät-zu-Gerät noch FRITZ!Box).
 *  - mit WLAN und Schalter an: Peers immer; die FRITZ!Box nur, wenn sie konfiguriert ist
 *    und nicht gerade ein Box-Sync laeuft. So greift der Handy-zu-Handy-Sync auch dann,
 *    wenn die FRITZ!Box-Zugangsdaten falsch sind.
 */
object SyncPolicy {
    fun plan(
        wifiConnected: Boolean,
        fritzConfigured: Boolean,
        fritzBusy: Boolean,
        syncEnabled: Boolean = true,
    ): SyncPlan =
        if (!syncEnabled || !wifiConnected) {
            SyncPlan(syncPeers = false, syncFritz = false)
        } else {
            SyncPlan(syncPeers = true, syncFritz = fritzConfigured && !fritzBusy)
        }
}
