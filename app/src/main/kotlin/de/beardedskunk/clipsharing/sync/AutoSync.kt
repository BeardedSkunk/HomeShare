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
                // WLAN da: SyncManager (NSD) hochfahren bzw. Discovery auffrischen, dann syncen.
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
        if (wifiConnected()) {
            syncManager.start()
            trigger()
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
        val plan = SyncPolicy.plan(wifiConnected(), settings.fritzConfigured(), fritzBusy)
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

    /** Ist gerade ein WLAN das aktive Netz? */
    private fun wifiConnected(): Boolean {
        val mgr = cm ?: return false
        val net = mgr.activeNetwork ?: return false
        val caps = mgr.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
