package de.beardedskunk.clipsharing.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import de.beardedskunk.clipsharing.backup.FritzController
import de.beardedskunk.clipsharing.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Stößt den Sync automatisch an – ohne Knopfdruck:
 *  - bei jeder lokalen Änderung (Hook im Repository),
 *  - wenn ein WLAN verfügbar wird (ConnectivityManager-Callback),
 *  - wenn die App in den Vordergrund kommt (Activity-onResume).
 *
 * **WLAN-Pflicht:** Es gibt keinerlei Sync-Versuche, solange kein WLAN aktiv ist.
 * AutoSync besitzt deshalb den Lebenszyklus des [SyncManager]s: NSD/Discovery läuft
 * nur bei WLAN. Geht das WLAN, wird der SyncManager pausiert. Synct sowohl mit den
 * LAN-Peers als auch (falls konfiguriert) mit der FRITZ!Box – der Peer-Sync greift
 * auch dann, wenn die FRITZ!Box-Zugangsdaten falsch sind.
 */
class AutoSync(
    context: Context,
    private val settings: Settings,
    private val fritz: FritzController,
    private val syncManager: SyncManager,
) {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var fritzBusy = false

    fun start() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        // Nur WLAN-Netze beobachten – Mobilfunk löst keinen Sync aus.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // WLAN da: nur hochfahren, wenn der Sync-Schalter an ist.
                if (!settings.syncEnabled) { syncManager.disable(); return }
                syncManager.start()
                syncManager.refresh()
                trigger()
            }

            override fun onLost(network: Network) {
                // WLAN weg: jede Sync-Aktivität einstellen, kein Discovery, keine Versuche.
                syncManager.pause()
            }
        }
        netCallback = cb
        runCatching { cm?.registerNetworkCallback(request, cb) }
        // Falls bereits WLAN besteht, sofort starten (onAvailable kommt nicht garantiert sofort).
        if (settings.syncEnabled && wifiConnected()) {
            syncManager.start()
            trigger()
        } else if (!settings.syncEnabled) {
            syncManager.disable()
        } else {
            syncManager.pause()
        }
    }

    /**
     * Sync-Schalter umlegen (Einstellungen). Persistiert und wirkt sofort: AUS faehrt den
     * SyncManager komplett herunter (Server/Beacon/Verbindungen weg – wie offline), AN
     * faehrt ihn bei vorhandenem WLAN wieder hoch und stoesst einen Durchlauf an.
     */
    fun setSyncEnabled(enabled: Boolean) {
        settings.syncEnabled = enabled
        if (enabled) {
            if (wifiConnected()) {
                syncManager.start()
                syncManager.refresh()
                trigger()
            }
        } else {
            syncManager.pause()
        }
    }

    fun stop() {
        netCallback?.let { cb -> runCatching { cm?.unregisterNetworkCallback(cb) } }
        netCallback = null
        syncManager.stop()
        scope.cancel()
    }

    /** Einen Sync-Durchlauf anstoßen (Peers + FRITZ!Box) – nur bei aktivem WLAN. */
    fun trigger() {
        val plan = SyncPolicy.plan(wifiConnected(), settings.fritzConfigured(), fritzBusy, settings.syncEnabled)
        if (!plan.syncPeers && !plan.syncFritz) {
            syncManager.pause()
            return
        }
        scope.launch {
            if (plan.syncPeers) runCatching { syncManager.syncNow() }
            if (plan.syncFritz) {
                fritzBusy = true
                try {
                    fritz.sync()
                } finally {
                    fritzBusy = false
                }
            }
        }
    }

    /**
     * Ist IRGENDEIN WLAN verbunden? Bewusst NICHT nur das Default-Netz (`activeNetwork`)
     * prüfen: sind WLAN UND Mobilfunk an und hat das WLAN (noch) kein Internet, macht
     * Android Mobilfunk zum Default-Netz – dann wäre `activeNetwork` Mobilfunk, obwohl
     * WLAN verbunden ist. Für LAN-Sync genügt aber ein verbundenes WLAN. Daher alle Netze prüfen.
     */
    private fun wifiConnected(): Boolean {
        val mgr = cm ?: return false
        @Suppress("DEPRECATION")
        for (n in mgr.allNetworks) {
            val caps = mgr.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true
        }
        return false
    }
}
