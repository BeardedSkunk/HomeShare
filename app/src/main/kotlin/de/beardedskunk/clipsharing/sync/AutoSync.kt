package de.beardedskunk.clipsharing.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
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
 *  - bei WLAN-/Netzwerkwechsel (ConnectivityManager-Callback),
 *  - wenn die App in den Vordergrund kommt (Activity-onResume).
 *
 * Synct sowohl mit den LAN-Peers als auch (falls konfiguriert) mit der FRITZ!Box.
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
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                syncManager.refresh()
                trigger()
            }
        }
        netCallback = cb
        runCatching { cm?.registerDefaultNetworkCallback(cb) }
    }

    fun stop() {
        netCallback?.let { cb -> runCatching { cm?.unregisterNetworkCallback(cb) } }
        netCallback = null
        scope.cancel()
    }

    /** Einen Sync-Durchlauf anstoßen (Peers + FRITZ!Box). */
    fun trigger() {
        scope.launch {
            runCatching { syncManager.syncNow() }
            if (settings.fritzConfigured() && !fritzBusy) {
                fritzBusy = true
                try {
                    fritz.sync()
                } finally {
                    fritzBusy = false
                }
            }
        }
    }
}
