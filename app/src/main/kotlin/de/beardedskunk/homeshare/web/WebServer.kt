package de.beardedskunk.homeshare.web

import de.beardedskunk.homeshare.core.NodeContent
import de.beardedskunk.homeshare.core.NodeType
import de.beardedskunk.homeshare.data.BlobStore
import de.beardedskunk.homeshare.data.FeedRepository
import de.beardedskunk.homeshare.data.NodeState
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream
import java.util.Base64

/**
 * Eingebetteter HTTP-Server (NanoHTTPD) für den PC-Browser-Zugriff: kleine Single-Page-UI + JSON-API
 * auf dasselbe Repository (Knoten-Baum). Ein „Feed" ist ein Wurzelknoten, ein „Post" ein Text-
 * Kindknoten, dessen Bilder eigene IMAGE-Kindknoten sind. Die JSON-Feldnamen bleiben kompatibel zur
 * bestehenden Web-UI (postId/text/images/imageTitles/…). Nur manuell aus der App gestartet.
 */
class WebServer(
    port: Int,
    private val repo: FeedRepository,
    private val blobStore: BlobStore,
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response = try {
        route(session)
    } catch (e: Exception) {
        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Fehler: ${e.message}")
    }

    private fun route(session: IHTTPSession): Response {
        val uri = session.uri
        val get = session.method == Method.GET
        val post = session.method == Method.POST
        return when {
            uri == "/" && get -> html(WEB_UI_HTML)
            uri == "/api/feeds" && get -> json(feedsJson().toString())
            uri == "/api/feeds" && post -> {
                repo.createFeed(JSONObject(readBody(session)).getString("name"))
                ok()
            }
            uri == "/api/posts" && get -> json(postsJson(repo.listPosts(param(session, "feed"))).toString())
            uri == "/api/search" && get ->
                json(postsJson(repo.search(param(session, "feed"), param(session, "q"))).toString())
            uri == "/api/post" && post -> {
                val b = JSONObject(readBody(session))
                val entry = repo.createNode(NodeContent(parentId = b.getString("feed"), type = NodeType.TEXT, text = b.optString("text")))
                for (sha in decodeImages(b)) {
                    val img = repo.createNode(NodeContent(parentId = entry.nodeId, type = NodeType.IMAGE, blobHash = sha))
                    repo.createNode(NodeContent(parentId = img.nodeId, type = NodeType.TEXT, text = ""))
                }
                ok()
            }
            uri == "/api/post/edit" && post -> {
                val b = JSONObject(readBody(session))
                val id = b.getString("postId")
                val hc = repo.headContent(id) ?: NodeContent(parentId = b.getString("feed"), type = NodeType.TEXT)
                repo.editNode(id, hc.copy(text = b.optString("text")))
                ok()
            }
            uri == "/api/post/delete" && post -> {
                val b = JSONObject(readBody(session))
                repo.deleteNode(b.getString("postId"))
                ok()
            }
            uri == "/api/conflict" && get -> json(conflictJson(param(session, "post")).toString())
            uri == "/api/post/resolve" && post -> {
                val b = JSONObject(readBody(session))
                val id = b.getString("postId")
                val hc = repo.headContent(id) ?: NodeContent(parentId = b.getString("feed"), type = NodeType.TEXT)
                val content = if (b.optBoolean("deleted")) hc.copy(deleted = true)
                else hc.copy(text = b.optString("text"), deleted = false)
                repo.resolveConflict(id, content)
                ok()
            }
            uri.startsWith("/thumb/") && get -> serveBlob(blobStore.thumbFile(uri.removePrefix("/thumb/")))
            uri.startsWith("/blob/") && get -> serveBlob(blobStore.fullFile(uri.removePrefix("/blob/")))
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "nicht gefunden")
        }
    }

    // -------------------------------------------------------------- JSON

    private fun feedsJson(): JSONArray {
        val arr = JSONArray()
        for (f in repo.listFeeds()) arr.put(JSONObject().put("id", f.nodeId).put("name", f.title))
        return arr
    }

    private fun postsJson(posts: List<NodeState>): JSONArray {
        val arr = JSONArray()
        for (p in posts) {
            // Bilder eines Eintrags = seine IMAGE-Kindknoten; deren Beschreibung = je ein TEXT-Enkel.
            val imgKids = repo.children(p.nodeId).filter { it.type == NodeType.IMAGE || it.type == NodeType.FILE }
            val hashes = imgKids.mapNotNull { it.blobHash }
            val titles = imgKids.map { img -> repo.children(img.nodeId).firstOrNull { it.type == NodeType.TEXT }?.text ?: "" }
            arr.put(
                JSONObject()
                    .put("postId", p.nodeId)
                    .put("text", p.text)
                    .put("images", JSONArray(hashes))
                    .put("imageTitles", JSONArray(titles))
                    .put("deleted", p.deleted)
                    .put("conflicted", p.conflicted)
                    .put("created", p.created.wallMillis),
            )
        }
        return arr
    }

    private fun conflictJson(nodeId: String): JSONObject {
        val history = repo.history(nodeId)
        val heads = history.heads()
        val base = if (heads.size >= 2) {
            history.lowestCommonAncestor(heads[0].versionId, heads[1].versionId)?.content?.text ?: ""
        } else {
            ""
        }
        val headArr = JSONArray()
        for (h in heads) {
            headArr.put(
                JSONObject()
                    .put("versionId", h.versionId)
                    .put("device", h.deviceId)
                    .put("text", h.content.text)
                    .put("deleted", h.content.deleted)
                    .put("images", JSONArray()),
            )
        }
        return JSONObject().put("base", base).put("heads", headArr)
    }

    // -------------------------------------------------------------- Helpers

    private fun decodeImages(b: JSONObject): List<String> {
        val arr = b.optJSONArray("imagesB64") ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            out += blobStore.put(Base64.getDecoder().decode(arr.getString(i)))
        }
        return out
    }

    private fun readBody(session: IHTTPSession): String {
        val len = session.headers["content-length"]?.toIntOrNull() ?: return ""
        if (len <= 0) return ""
        val buf = ByteArray(len)
        var off = 0
        val ins = session.inputStream
        while (off < len) {
            val r = ins.read(buf, off, len - off)
            if (r <= 0) break
            off += r
        }
        return String(buf, 0, off, Charsets.UTF_8)
    }

    private fun param(session: IHTTPSession, name: String): String =
        session.parameters[name]?.firstOrNull().orEmpty()

    private fun serveBlob(file: java.io.File): Response =
        if (file.exists()) {
            newChunkedResponse(Response.Status.OK, "image/jpeg", FileInputStream(file))
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "kein Bild")
        }

    private fun html(body: String): Response = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body)
    private fun json(body: String): Response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", body)
    private fun ok(): Response = json("{\"ok\":true}")
}
