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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

data class SyncStatus(
    val running: Boolean = false,
    val lastMessage: String = "",
)

/**
 * Treibt den Gerät-zu-Gerät-Sync im LAN. Drei Discovery-Wege, die sich ergänzen:
 *  1. **UDP-Broadcast-Beacon** (Hauptweg, aus der Rommee-App übernommen): jedes Gerät
 *     funkt periodisch einen Beacon an `255.255.255.255` *und* an die gerichteten
 *     Subnetz-Broadcasts jeder Schnittstelle (manche Router/OEMs filtern den globalen).
 *     Wer einen fremden Beacon der eigenen Gruppe hört, verbindet sich. Kommt komplett
 *     ohne mDNS aus.
 *  2. **NSD/mDNS** (`_clipfeed._tcp`) als Zusatz – Resolves werden serialisiert, sonst
 *     wirft Android „has no client mapping".
 *  3. **Manuelle Fallback-Peers** aus den Einstellungen (IPs/Bereiche).
 *
 * Der TCP-Sync-Server lauscht auf einem festen Port (damit Beacon/Manuell ihn treffen),
 * fällt bei Belegung auf einen flüchtigen Port zurück. Transport ist über [SecureChannel]
 * (AES-GCM, Gruppen-Passphrase) verschlüsselt.
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
    private var beaconSocket: DatagramSocket? = null
    private var regListener: NsdManager.RegistrationListener? = null
    private var discListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val knownPeers = java.util.concurrent.ConcurrentHashMap<String, InetSocketAddress>()

    // NSD-Resolves serialisieren (Android erlaubt nur einen gleichzeitig).
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    val status = MutableStateFlow(SyncStatus())

    @Synchronized
    fun start() {
        if (status.value.running) return
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        multicastLock = wifi.createMulticastLock("clipsharing-disc").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
        startServer()
        if (AUTO_DISCOVERY) {
            startBeacon()
            registerService()
            startDiscovery()
        }
        startManualPeerLoop()
        status.value = SyncStatus(running = true, lastMessage = "Sync aktiv")
    }

    @Synchronized
    fun stop() {
        teardown()
        status.value = SyncStatus(running = false, lastMessage = "Sync gestoppt")
    }

    /**
     * Pausieren, weil kein WLAN aktiv ist: alle Discovery-Wege, Server und Lock werden
     * abgebaut, damit es keinerlei Sync-Versuche gibt. [start] fährt alles wieder hoch.
     */
    @Synchronized
    fun pause() {
        if (!status.value.running && status.value.lastMessage == NO_WIFI) return
        teardown()
        status.value = SyncStatus(running = false, lastMessage = NO_WIFI)
    }

    /**
     * Wie [pause], aber vom Nutzer gewollt (Sync-Schalter aus). Eigener Status-Text,
     * damit nicht faelschlich „Kein WLAN" angezeigt wird.
     */
    @Synchronized
    fun disable() {
        teardown()
        status.value = SyncStatus(running = false, lastMessage = DISABLED)
    }

    private fun teardown() {
        runCatching { discListener?.let { nsd.stopServiceDiscovery(it) } }
        runCatching { regListener?.let { nsd.unregisterService(it) } }
        discListener = null
        regListener = null
        synchronized(resolveQueue) { resolveQueue.clear(); resolving = false }
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { beaconSocket?.close() }
        beaconSocket = null
        runCatching { multicastLock?.release() }
        multicastLock = null
        knownPeers.clear()
        scope.cancel()
    }

    // ----- TCP-Sync-Server -----

    private fun startServer() {
        val ss = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(SYNC_PORT))
            }
        }.getOrElse {
            // Fester Port belegt -> flüchtiger Port (Beacon trägt den echten Port mit).
            ServerSocket(0)
        }
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

    private fun connectAndSync(addr: InetSocketAddress) {
        scope.launch {
            val socket = runCatching {
                Socket().apply { connect(addr, CONNECT_TIMEOUT_MS) }
            }.getOrNull() ?: return@launch
            handle(socket, initiator = true)
        }
    }

    // ----- UDP-Broadcast-Beacon (Hauptweg) -----

    private fun startBeacon() {
        val sock = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(BEACON_PORT))
            }
        }.getOrNull()
        if (sock == null) {
            Log.w(TAG, "Beacon-Port $BEACON_PORT belegt – Broadcast-Discovery aus")
            return
        }
        beaconSocket = sock
        // Empfänger: fremde Beacons -> verbinden.
        scope.launch {
            val buf = ByteArray(2048)
            while (scope.isActive && !sock.isClosed) {
                val pkt = DatagramPacket(buf, buf.size)
                val ok = runCatching { sock.receive(pkt) }.isSuccess
                if (!ok) break
                val text = String(pkt.data, pkt.offset, pkt.length, Charsets.UTF_8)
                val beacon = BeaconCodec.parse(text) ?: continue
                if (beacon.deviceId == identity.deviceId) continue // eigener Beacon
                if (beacon.group != identity.groupName) continue
                val addr = InetSocketAddress(pkt.address, beacon.port)
                knownPeers[beacon.deviceId] = addr
                connectAndSync(addr)
            }
        }
        // Sender: periodisch funken.
        scope.launch {
            val payload = BeaconCodec.encode(Beacon(identity.groupName, identity.deviceId, localPort))
                .toByteArray(Charsets.UTF_8)
            while (scope.isActive && !sock.isClosed) {
                for (target in broadcastTargets()) {
                    runCatching { sock.send(DatagramPacket(payload, payload.size, target, BEACON_PORT)) }
                }
                delay(BEACON_INTERVAL_MS)
            }
        }
    }

    /** 255.255.255.255 plus die gerichteten Subnetz-Broadcasts aller IPv4-Schnittstellen. */
    private fun broadcastTargets(): Set<InetAddress> {
        val targets = linkedSetOf<InetAddress>()
        runCatching { targets.add(InetAddress.getByName("255.255.255.255")) }
        runCatching {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    ia.broadcast?.let { targets.add(it) }
                }
            }
        }
        return targets
    }

    // ----- NSD/mDNS (Zusatz, serialisierte Resolves) -----

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
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private fun startDiscovery() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType.trimEnd('.') == SERVICE_TYPE.trimEnd('.')) enqueueResolve(info)
            }
        }
        discListener = listener
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private fun enqueueResolve(info: NsdServiceInfo) {
        synchronized(resolveQueue) {
            if (resolveQueue.any { it.serviceName == info.serviceName }) return
            resolveQueue.addLast(info)
        }
        pumpResolve()
    }

    private fun pumpResolve() {
        val next: NsdServiceInfo
        synchronized(resolveQueue) {
            if (resolving) return
            next = resolveQueue.removeFirstOrNull() ?: return
            resolving = true
        }
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = finishResolve()
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                handleResolved(resolved)
                finishResolve()
            }
        }
        runCatching { nsd.resolveService(next, listener) }.onFailure { finishResolve() }
    }

    private fun finishResolve() {
        synchronized(resolveQueue) { resolving = false }
        pumpResolve()
    }

    private fun handleResolved(resolved: NsdServiceInfo) {
        val device = resolved.attributes[ATTR_DEVICE]?.toString(Charsets.UTF_8) ?: return
        val group = resolved.attributes[ATTR_GROUP]?.toString(Charsets.UTF_8)
        if (device == identity.deviceId) return
        if (group != identity.groupName) return
        val host = resolved.host ?: return
        val addr = InetSocketAddress(host, resolved.port)
        knownPeers[device] = addr
        connectAndSync(addr)
    }

    // ----- Manuelle Fallback-Peers -----

    private fun startManualPeerLoop() {
        scope.launch {
            while (scope.isActive) {
                connectManualPeers()
                delay(MANUAL_INTERVAL_MS)
            }
        }
    }

    private fun connectManualPeers() {
        val raw = settings.fallbackPeers
        if (raw.isBlank()) return
        val mine = localIpv4Addresses()
        for (ip in PeerList.parse(raw)) {
            if (ip in mine) continue // nicht mit sich selbst syncen
            connectAndSync(InetSocketAddress(ip, SYNC_PORT))
        }
    }

    private fun localIpv4Addresses(): Set<String> {
        val out = HashSet<String>()
        runCatching {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                for (addr in ni.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        addr.hostAddress?.let { out.add(it) }
                    }
                }
            }
        }
        return out
    }

    /** Aktiv mit allen bekannten + manuellen Peers synchronisieren (fuer Auto-Sync). */
    fun syncNow() {
        for (addr in knownPeers.values) connectAndSync(addr)
        connectManualPeers()
    }

    /** Discovery neu starten (z. B. nach WLAN-Wechsel). */
    @Synchronized
    fun refresh() {
        if (!status.value.running) return
        runCatching { discListener?.let { nsd.stopServiceDiscovery(it) } }
        discListener = null
        synchronized(resolveQueue) { resolveQueue.clear(); resolving = false }
        knownPeers.clear()
        startDiscovery()
    }

    companion object {
        private const val TAG = "SyncManager"
        // Normalbetrieb: true (Broadcast-Beacon + NSD aktiv). Nur zum isolierten Testen
        // des manuellen IP-Fallbacks vorübergehend auf false setzen.
        private const val AUTO_DISCOVERY = true
        private const val NO_WIFI = "Kein WLAN – Sync pausiert"
        private const val DISABLED = "Sync deaktiviert"
        private const val DEFAULT_PASSPHRASE = "clipsharing-default"
        private const val SERVICE_TYPE = "_clipfeed._tcp."
        private const val ATTR_GROUP = "grp"
        private const val ATTR_DEVICE = "dev"
        private const val SYNC_PORT = 47600
        private const val BEACON_PORT = 47601
        private const val BEACON_INTERVAL_MS = 2_000L
        private const val MANUAL_INTERVAL_MS = 20_000L
        private const val SOCKET_TIMEOUT_MS = 15_000
        private const val CONNECT_TIMEOUT_MS = 4_000
    }
}
