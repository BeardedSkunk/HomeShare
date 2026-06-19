package de.beardedskunk.homeshare.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import de.beardedskunk.homeshare.crypto.GroupCrypto
import de.beardedskunk.homeshare.data.DeviceIdentity
import de.beardedskunk.homeshare.data.FeedRepository
import de.beardedskunk.homeshare.data.FeedRight
import de.beardedskunk.homeshare.data.ForeignFeedRef
import de.beardedskunk.homeshare.data.PairingPayload
import de.beardedskunk.homeshare.data.ShareGrant
import de.beardedskunk.homeshare.data.Settings
import java.io.InputStream
import java.io.OutputStream
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
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SyncManager(
    context: Context,
    private val repo: FeedRepository,
    private val identity: DeviceIdentity,
    private val settings: Settings,
    private val blobStore: de.beardedskunk.homeshare.data.BlobStore? = null,
) {
    /**
     * Direkter Blob-Abgleich (#11): nennt der Gegenseite die aktuell angezeigten Bilder,
     * die uns lokal fehlen, und liefert, was wir von deren Wunschliste haben. Null, wenn
     * kein BlobStore verdrahtet ist (z. B. in Tests) -> Protokoll laeuft dann nur mit Ops.
     */
    private val blobSync: BlobSync? = blobStore?.let { store ->
        object : BlobSync {
            override fun wanted(): Set<String> =
                repo.displayedImageHashes().filterTo(HashSet()) { !store.hasFull(it) }
            override fun has(sha: String): Boolean = store.hasFull(sha)
            override fun read(sha: String): ByteArray? = store.readFull(sha)
            override fun store(sha: String, bytes: ByteArray) {
                runCatching { store.putWithSha(sha, bytes) }
            }
        }
    }
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
    private var wifiLock: WifiManager.WifiLock? = null
    private val knownPeers = java.util.concurrent.ConcurrentHashMap<String, InetSocketAddress>()

    // #10: zuletzt gesehene Adresse je FREMDER Gruppe (fuer Cross-Group-Push aus syncNow).
    private val foreignGroupPeers = java.util.concurrent.ConcurrentHashMap<String, InetSocketAddress>()

    // #10: offene QR-Pairings dieses (anzeigenden) Geraets: capId -> (feedId, capSecret, Ablauf).
    private data class Pending(val feedId: String, val capSecret: String, val expiresAt: Long)
    private val pendingPairings = java.util.concurrent.ConcurrentHashMap<String, Pending>()

    /** Wird gerufen, wenn ein QR-Pairing abgeschlossen wurde (UI schliesst dann den QR-Screen). */
    @Volatile var onPairingComplete: ((capId: String) -> Unit)? = null

    // Drosselt den Verbindungs-Sturm (Beacon alle 2 s je Peer) auf hoechstens eine
    // Verbindung pro Peer und Cooldown – sonst laeuft der IO-Pool voll und der Server
    // nimmt nichts mehr an. Begrenzte Parallelitaet schuetzt zusaetzlich den Accept-Loop.
    private val gate = ConnectionGate()
    private val netDispatcher = Dispatchers.IO.limitedParallelism(SYNC_PARALLELISM)

    // Abgeleiteter Gruppen-Schluessel (PBKDF2, 120k Runden) wird gecacht: sonst wuerde
    // er bei JEDER Verbindung neu berechnet -> teuer und mitschuldig am Thread-Hunger.
    @Volatile private var cachedKey: javax.crypto.SecretKey? = null
    @Volatile private var cachedKeyId: String? = null

    // NSD-Resolves serialisieren (Android erlaubt nur einen gleichzeitig).
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    val status = MutableStateFlow(SyncStatus())

    @Synchronized
    fun start() {
        if (status.value.running) return
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        multicastLock = wifi.createMulticastLock("homeshare-disc").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
        // Hält das WLAN-Radio wach, damit der Server auch im Standby erreichbar bleibt (W8).
        @Suppress("DEPRECATION")
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "homeshare-sync").apply {
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
        runCatching { wifiLock?.release() }
        wifiLock = null
        knownPeers.clear()
        gate.clear()
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
        // Accept-Loop laeuft auf dem normalen Scope (NICHT auf dem begrenzten
        // netDispatcher), damit er IMMER eine Verbindung annehmen kann, auch wenn
        // gerade SYNC_PARALLELISM Handshakes laufen.
        scope.launch {
            while (!ss.isClosed) {
                val socket = runCatching { ss.accept() }.getOrNull() ?: break
                // Verarbeitung begrenzt-parallel; der Loop selbst blockiert nie.
                scope.launch(netDispatcher) { runResponder(socket) }
            }
        }
    }

    /** Cachebarer Gruppen-Schluessel (nur neu ableiten, wenn Passphrase/Gruppe wechseln). */
    private fun groupKey(): javax.crypto.SecretKey {
        val pass = settings.groupPassphrase.ifBlank { DEFAULT_PASSPHRASE }
        val grp = identity.groupName
        val id = "$pass $grp"
        cachedKey?.let { if (cachedKeyId == id) return it }
        val k = GroupCrypto.deriveKey(pass, grp.toByteArray(Charsets.UTF_8))
        cachedKey = k
        cachedKeyId = id
        return k
    }

    // ----- Verbindungs-Annahme: Klartext-Modus-Praeambel, dann passender Kanal -----

    /** Liest eine Klartext-Zeile (bis '\n') byteweise – ohne zu puffern, damit danach der
     * verschluesselte SecureChannel auf denselben Stroemen sauber weiterliest. */
    private fun readLineRaw(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun writeLine(out: OutputStream, line: String) {
        out.write((line + "\n").toByteArray(Charsets.UTF_8)); out.flush()
    }

    private fun runResponder(socket: Socket) {
        runCatching {
            socket.use {
                it.soTimeout = SOCKET_TIMEOUT_MS
                val input = it.getInputStream(); val output = it.getOutputStream()
                val parts = readLineRaw(input).split(' ')
                when (parts.getOrNull(0)) {
                    MODE_GROUP -> {
                        val r = PeerProtocol.runResponder(repo, SecureChannel(input, output, groupKey()), blobSync)
                        status.value = status.value.copy(lastMessage = "Sync ok: +${r.pulled} empfangen, ${r.pushed} gesendet")
                    }
                    MODE_FEED -> serveForeignFeed(parts, input, output)
                    MODE_PAIR -> servePairing(parts, input, output)
                    else -> Log.w(TAG, "Unbekannter Sync-Modus: ${parts.getOrNull(0)}")
                }
            }
        }.onFailure { Log.w(TAG, "Sync-Verbindung fehlgeschlagen: ${it.javaClass.simpleName}: ${it.message}") }
    }

    /** Original-Seite eines geteilten Feeds: Capability pruefen, dann CrossGroupProtocol. */
    private fun serveForeignFeed(parts: List<String>, input: InputStream, output: OutputStream) {
        val feedId = parts.getOrNull(1) ?: return
        val capId = parts.getOrNull(2) ?: return
        val grant = repo.grantFor(feedId, capId) ?: return // unbekannt/widerrufen -> nichts tun
        val capSecret = runCatching { GroupCrypto.decryptString(groupKey(), grant.encSecret) }.getOrNull() ?: return
        val ch = SecureChannel(input, output, GroupCrypto.keyFromToken(capSecret))
        val r = CrossGroupProtocol.runOriginal(repo, feedId, grant.right, ch, blobSync)
        status.value = status.value.copy(lastMessage = "Geteilt (${grant.label}): +${r.pulled}/${r.pushed}")
    }

    /** Pairing-Annahme: passendes offenes QR? -> capSecret-Kanal, Gruppenname lesen, Freigabe eintragen. */
    private fun servePairing(parts: List<String>, input: InputStream, output: OutputStream) {
        val feedId = parts.getOrNull(1) ?: return
        val capId = parts.getOrNull(2) ?: return
        val pending = pendingPairings[capId] ?: return
        if (now() > pending.expiresAt || pending.feedId != feedId) { pendingPairings.remove(capId); return }
        val ch = SecureChannel(input, output, GroupCrypto.keyFromToken(pending.capSecret))
        val groupName = ch.readText() // scheitert bei falschem capSecret -> kein Eintrag
        val encSecret = GroupCrypto.encryptString(groupKey(), pending.capSecret)
        repo.addShare(feedId, ShareGrant(capId, FeedRight.READ, groupName.ifBlank { "Fremdgruppe" }, encSecret))
        ch.writeText("OK")
        pendingPairings.remove(capId)
        onPairingComplete?.invoke(capId)
    }

    // ----- Verbindungs-Aufbau (Initiator) -----

    private fun connect(addr: InetSocketAddress): Socket? =
        runCatching { Socket().apply { connect(addr, CONNECT_TIMEOUT_MS) } }.getOrNull()

    /** Gleiche Gruppe: voller Abgleich (Modus GROUP). */
    private fun connectAndSync(addr: InetSocketAddress) {
        val peer = "G:${addr.address?.hostAddress ?: addr.hostString}:${addr.port}"
        if (!gate.tryAcquire(peer, now())) return
        scope.launch(netDispatcher) {
            try {
                connect(addr)?.use {
                    it.soTimeout = SOCKET_TIMEOUT_MS
                    writeLine(it.getOutputStream(), MODE_GROUP)
                    val r = PeerProtocol.runInitiator(repo, SecureChannel(it.getInputStream(), it.getOutputStream(), groupKey()), blobSync)
                    status.value = status.value.copy(lastMessage = "Sync ok: +${r.pulled} empfangen, ${r.pushed} gesendet")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Sync-Verbindung fehlgeschlagen: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                gate.release(peer, now())
            }
        }
    }

    /** Fremdgruppe: EINEN geteilten Feed ziehen/pushen (Modus FEED) mit capSecret-Kanal. */
    private fun connectFeed(addr: InetSocketAddress, ref: ForeignFeedRef) {
        val peer = "F:${addr.address?.hostAddress ?: addr.hostString}:${addr.port}:${ref.feedId}"
        if (!gate.tryAcquire(peer, now())) return
        scope.launch(netDispatcher) {
            try {
                connect(addr)?.use {
                    it.soTimeout = SOCKET_TIMEOUT_MS
                    writeLine(it.getOutputStream(), "$MODE_FEED ${ref.feedId} ${ref.capId}")
                    val ch = SecureChannel(it.getInputStream(), it.getOutputStream(), GroupCrypto.keyFromToken(ref.capSecret))
                    val r = CrossGroupProtocol.runForeign(repo, ref.feedId, ch, blobSync)
                    repo.updateForeignRight(ref.feedId, r.right)
                    status.value = status.value.copy(lastMessage = "Geteilt (von ${ref.originGroup}): +${r.pulled}/${r.pushed}")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Cross-Group-Sync fehlgeschlagen: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                gate.release(peer, now())
            }
        }
    }

    private fun now(): Long = android.os.SystemClock.elapsedRealtime()

    // ----- Pairing (#10), von der UI aufgerufen -----

    /** Eigentuemer: erzeugt eine frische Capability + offenes Pairing und liefert die QR-Nutzlast. */
    fun startPairing(feedId: String, feedName: String): PairingPayload {
        val capId = GroupCrypto.randomToken(12)
        val capSecret = GroupCrypto.randomToken(32)
        pendingPairings[capId] = Pending(feedId, capSecret, now() + PAIR_TTL_MS)
        val host = localIpv4Addresses().firstOrNull() ?: "0.0.0.0"
        return PairingPayload(
            feedId = feedId, feedName = feedName, originGroup = identity.groupName,
            capId = capId, capSecret = capSecret, host = host, port = localPort,
            expiresAtMillis = System.currentTimeMillis() + PAIR_TTL_MS,
        )
    }

    fun cancelPairing(capId: String) { pendingPairings.remove(capId) }

    /**
     * Fremdgeraet: nach Scan/Code-Eingabe beim Eigentuemer melden (Gruppenname uebertragen) und
     * den Fremdfeed lokal abonnieren. Blockierend -> auf IO-Thread aufrufen. @return true bei Erfolg.
     */
    fun pairAndSubscribe(payload: PairingPayload, calendar: Boolean = false): Boolean {
        val addr = InetSocketAddress(payload.host, payload.port)
        val ok = runCatching {
            connect(addr)?.use {
                it.soTimeout = SOCKET_TIMEOUT_MS
                writeLine(it.getOutputStream(), "$MODE_PAIR ${payload.feedId} ${payload.capId}")
                val ch = SecureChannel(it.getInputStream(), it.getOutputStream(), GroupCrypto.keyFromToken(payload.capSecret))
                ch.writeText(identity.groupName)
                ch.readText() == "OK"
            } ?: false
        }.onFailure { Log.w(TAG, "Pairing fehlgeschlagen: ${it.javaClass.simpleName}: ${it.message}") }.getOrDefault(false)
        if (ok) {
            repo.registerForeignFeed(
                ForeignFeedRef(payload.feedId, payload.originGroup, payload.capId, payload.capSecret, FeedRight.READ),
                payload.feedName, calendar,
            )
            connectFeed(addr, repo.foreignFeedRef(payload.feedId) ?: return true) // sofort einmal ziehen
        }
        return ok
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
                val addr = InetSocketAddress(pkt.address, beacon.port)
                if (beacon.group == identity.groupName) {
                    knownPeers[beacon.deviceId] = addr
                    connectAndSync(addr)
                } else {
                    // Fremde Gruppe: halte ich Fremdfeeds von dieser Gruppe? -> cross-group ziehen.
                    foreignGroupPeers[beacon.group] = addr
                    for (ref in repo.listForeignFeeds()) if (ref.originGroup == beacon.group) connectFeed(addr, ref)
                }
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
        // #10: eigene Fremdfeeds bei bekannten Original-Gruppen-Geraeten abgleichen.
        for (ref in repo.listForeignFeeds()) foreignGroupPeers[ref.originGroup]?.let { connectFeed(it, ref) }
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
        private const val DEFAULT_PASSPHRASE = "homeshare-default"
        private const val SERVICE_TYPE = "_clipfeed._tcp."
        private const val ATTR_GROUP = "grp"
        private const val ATTR_DEVICE = "dev"
        private const val SYNC_PORT = 47600
        private const val BEACON_PORT = 47601
        // Klartext-Modus-Praeambel (vor der Verschluesselung) – die Gegenseiten teilen ggf.
        // keinen Gruppen-Schluessel (Cross-Group), daher Modus zuerst im Klartext.
        private const val MODE_GROUP = "GROUP"
        private const val MODE_FEED = "FEED"
        private const val MODE_PAIR = "PAIR"
        private const val PAIR_TTL_MS = 120_000L // QR/Pairing 2 Minuten gueltig
        private const val BEACON_INTERVAL_MS = 2_000L
        private const val MANUAL_INTERVAL_MS = 20_000L
        // Kurzer Handshake-Timeout: ein haengender Peer gibt seinen Thread schnell
        // wieder frei (frueher 15 s -> Pool lief voll). LAN-Handshake ist <1 s.
        private const val SOCKET_TIMEOUT_MS = 6_000
        private const val CONNECT_TIMEOUT_MS = 3_000
        // Hoechstens so viele Handshakes gleichzeitig – schuetzt den IO-Pool/Accept-Loop.
        private const val SYNC_PARALLELISM = 6
    }
}
