package de.beardedskunk.clipsharing.web

import de.beardedskunk.clipsharing.data.BlobStore
import de.beardedskunk.clipsharing.data.FeedRepository
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Startet/stoppt den [WebServer] auf Wunsch (nur manuell aus der App) und stellt
 * die erreichbare URL bereit. Laeuft im App-Prozess; ein Foreground-Service fuer
 * dauerhaften Hintergrundbetrieb ist als Erweiterung vorgesehen.
 */
class WebServerController(
    private val repo: FeedRepository,
    private val blobStore: BlobStore,
    private val port: Int = 8080,
) {
    private var server: WebServer? = null

    /** Aktuelle URL oder null, wenn der Server aus ist. */
    val url = MutableStateFlow<String?>(null)

    @Synchronized
    fun start() {
        if (server != null) return
        val s = WebServer(port, repo, blobStore)
        s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        server = s
        url.value = "http://${localIpv4() ?: "<geraete-ip>"}:$port"
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
        url.value = null
    }

    @Synchronized
    fun toggle() {
        if (server == null) start() else stop()
    }

    fun isRunning(): Boolean = server != null
}
