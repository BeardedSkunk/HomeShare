package de.beardedskunk.clipsharing.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import de.beardedskunk.clipsharing.crypto.GroupCrypto
import de.beardedskunk.clipsharing.data.DeviceIdentity
import de.beardedskunk.clipsharing.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

data class SyncStatus(
    val running: Boolean = false,
    val lastMessage: String = "",
)

/**
 * Treibt den Gerät-zu-Gerät-Sync im LAN:
 *  - betreibt einen TCP-Server (ephemerer Port) und gleicht eingehende Verbindungen ab,
 *  - registriert sich per NSD/mDNS als `_clipfeed._tcp` (mit Gruppe + Geräte-Id),
 *  - entdeckt andere Instanzen derselben Gruppe und synct aktiv mit ihnen.
 *
 * Funktioniert in jedem gemeinsamen WLAN. Transport derzeit unverschlüsselt
 * (TLS + Gruppen-Passphrase: siehe Plan, folgt). Benötigt ein Gerät/WLAN zum
 * echten Testen.
 */
class SyncManager(
    context: Context,
    private val source: OpSource,
    private val identity: DeviceIdentity,
    private val settings: Settings,
) {
    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var serverSocket: ServerSocket? = null
    private var localPort = 0
    private var regListener: NsdManager.RegistrationListener? = null
    private var discListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val knownPeers = ConcurrentHashMap<String, InetSocketAddress>()

    val status = MutableStateFlow(SyncStatus())

    @Synchronized
    fun start() {
        if (status.value.running) return
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        multicastLock = wifi.createMulticastLock("clipsharing-nsd").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
        startServer()
        registerService()
        startDiscovery()
        status.value = SyncStatus(running = true, lastMessage = "Sync aktiv")
    }

    @Synchronized
    fun stop() {
        runCatching { discListener?.let { nsd.stopServiceDiscovery(it) } }
        runCatching { regListener?.let { nsd.unregisterService(it) } }
        discListener = null
        regListener = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { multicastLock?.release() }
        multicastLock = null
        scope.cancel()
        status.value = SyncStatus(running = false, lastMessage = "Sync gestoppt")
    }

    private fun startServer() {
        val ss = ServerSocket(0)
        serverSocket = ss
        localPort = ss.localPort
        scope.launch {
            while (!ss.isClosed) {
                val socket = runCatching { ss.accept() }.getOrNull() ?: break
                handle(socket, initiator = false)
            }
        }
    }

    private fun handle(socket: Socket, initiator: Boolean) {
        scope.launch {
            runCatching {
                socket.use {
                    it.soTimeout = SOCKET_TIMEOUT_MS
                    val key = GroupCrypto.deriveKey(
                        settings.groupPassphrase.ifBlank { DEFAULT_PASSPHRASE },
                        identity.groupName.toByteArray(Charsets.UTF_8),
                    )
                    val channel = SecureChannel(it.getInputStream(), it.getOutputStream(), key)
                    val result = if (initiator) {
                        PeerProtocol.runInitiator(source, channel)
                    } else {
                        PeerProtocol.runResponder(source, channel)
                    }
                    status.value = status.value.copy(
                        lastMessage = "Sync ok: +${result.pulled} empfangen, ${result.pushed} gesendet",
                    )
                }
            }.onFailure { Log.w(TAG, "Sync-Verbindung fehlgeschlagen", it) }
        }
    }

    private fun registerService() {
        val info = NsdServiceInfo().apply {
            serviceName = "clip-${identity.deviceId.take(8)}"
            serviceType = SERVICE_TYPE
            port = localPort
            setAttribute(ATTR_GROUP, identity.groupName)
            setAttribute(ATTR_DEVICE, identity.deviceId)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD-Registrierung fehlgeschlagen: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        regListener = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun startDiscovery() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType.trimEnd('.') == SERVICE_TYPE.trimEnd('.')) resolve(info)
            }
        }
        discListener = listener
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun resolve(info: NsdServiceInfo) {
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                val device = resolved.attributes[ATTR_DEVICE]?.toString(Charsets.UTF_8)
                val group = resolved.attributes[ATTR_GROUP]?.toString(Charsets.UTF_8)
                if (device == identity.deviceId) return // eigenes Gerät
                if (group != identity.groupName) return // andere Gruppe
                val host = resolved.host ?: return
                knownPeers[device] = InetSocketAddress(host, resolved.port)
                connectAndSync(InetSocketAddress(host, resolved.port))
            }
        }
        runCatching { nsd.resolveService(info, listener) }
            .onFailure { Log.w(TAG, "NSD-Resolve fehlgeschlagen", it) }
    }

    private fun connectAndSync(addr: InetSocketAddress) {
        scope.launch {
            val socket = runCatching {
                Socket().apply { connect(addr, CONNECT_TIMEOUT_MS) }
            }.getOrNull() ?: return@launch
            handle(socket, initiator = true)
        }
    }

    /** Aktiv mit allen bisher entdeckten Peers synchronisieren (fuer Auto-Sync). */
    fun syncNow() {
        for (addr in knownPeers.values) connectAndSync(addr)
    }

    /** Discovery neu starten (z. B. nach WLAN-Wechsel). */
    @Synchronized
    fun refresh() {
        if (!status.value.running) return
        runCatching { discListener?.let { nsd.stopServiceDiscovery(it) } }
        discListener = null
        knownPeers.clear()
        startDiscovery()
    }

    companion object {
        private const val TAG = "SyncManager"
        private const val DEFAULT_PASSPHRASE = "clipsharing-default"
        private const val SERVICE_TYPE = "_clipfeed._tcp."
        private const val ATTR_GROUP = "grp"
        private const val ATTR_DEVICE = "dev"
        private const val SOCKET_TIMEOUT_MS = 15_000
        private const val CONNECT_TIMEOUT_MS = 8_000
    }
}
