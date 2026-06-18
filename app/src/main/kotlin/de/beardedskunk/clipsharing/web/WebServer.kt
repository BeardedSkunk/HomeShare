package de.beardedskunk.clipsharing.web

import de.beardedskunk.clipsharing.core.PostContent
import de.beardedskunk.clipsharing.data.BlobStore
import de.beardedskunk.clipsharing.data.FeedRepository
import de.beardedskunk.clipsharing.data.PostState
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream
import java.util.Base64

/**
 * Eingebetteter HTTP-Server (NanoHTTPD) fuer den PC-Browser-Zugriff: liefert eine
 * kleine Single-Page-UI und eine JSON-API auf dasselbe Repository wie die App.
 * Wird nur manuell aus der App gestartet (siehe WebServerController).
 *
 * Hinweis: NanoHTTPD statt Ktor bewusst gewaehlt (leichtgewichtig, keine schwere
 * Abhaengigkeitskette). Transport unverschluesselt im vertrauten WLAN.
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
                val images = decodeImages(b)
                repo.createPost(b.getString("feed"), b.optString("text"), images, strList(b.optJSONArray("imageTitles")))
                ok()
            }
            uri == "/api/post/edit" && post -> {
                val b = JSONObject(readBody(session))
                repo.editPost(
                    b.getString("feed"),
                    b.getString("postId"),
                    b.optString("text"),
                    strList(b.optJSONArray("images")),
                    strList(b.optJSONArray("imageTitles")),
                )
                ok()
            }
            uri == "/api/post/delete" && post -> {
                val b = JSONObject(readBody(session))
                repo.deletePost(b.getString("feed"), b.getString("postId"))
                ok()
            }
            uri == "/api/conflict" && get -> json(conflictJson(param(session, "post")).toString())
            uri == "/api/post/resolve" && post -> {
                val b = JSONObject(readBody(session))
                val content = if (b.optBoolean("deleted")) {
                    PostContent(deleted = true)
                } else {
                    PostContent(
                        text = b.optString("text"),
                        imageHashes = strList(b.optJSONArray("images")),
                        imageTitles = strList(b.optJSONArray("imageTitles")),
                    )
                }
                repo.resolveConflict(b.getString("feed"), b.getString("postId"), content)
                ok()
            }
            uri.startsWith("/thumb/") && get -> serveImage(blobStore.thumbFile(uri.removePrefix("/thumb/")))
            uri.startsWith("/blob/") && get -> serveImage(blobStore.fullFile(uri.removePrefix("/blob/")))
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "nicht gefunden")
        }
    }

    // -------------------------------------------------------------- JSON

    private fun feedsJson(): JSONArray {
        val arr = JSONArray()
        for (f in repo.listFeeds()) arr.put(JSONObject().put("id", f.id).put("name", f.name))
        return arr
    }

    private fun postsJson(posts: List<PostState>): JSONArray {
        val arr = JSONArray()
        for (p in posts) {
            arr.put(
                JSONObject()
                    .put("postId", p.postId)
                    .put("text", p.text)
                    .put("images", JSONArray(p.imageHashes))
                    .put("imageTitles", JSONArray(p.imageTitles))
                    .put("deleted", p.deleted)
                    .put("conflicted", p.conflicted)
                    .put("created", p.created.wallMillis),
            )
        }
        return arr
    }

    private fun conflictJson(postId: String): JSONObject {
        val postHistory = repo.history(postId)
        val heads = postHistory.heads()
        val base = if (heads.size >= 2) {
            postHistory.lowestCommonAncestor(heads[0].versionId, heads[1].versionId)?.content?.text ?: ""
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
                    .put("images", JSONArray(h.content.imageHashes)),
            )
        }
        return JSONObject().put("base", base).put("heads", headArr)
    }

    // -------------------------------------------------------------- Helpers

    private fun decodeImages(b: JSONObject): List<String> {
        val arr = b.optJSONArray("imagesB64") ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val bytes = Base64.getDecoder().decode(arr.getString(i))
            out += blobStore.put(bytes)
        }
        return out
    }

    private fun strList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    /**
     * Liest den POST-Body roh und dekodiert ihn IMMER als UTF-8. NanoHTTPDs parseBody
     * nimmt ohne charset im Content-Type einen falschen Zeichensatz an -> Umlaute wurden
     * dabei zu U+FFFD zerstört. Daher die Bytes selbst lesen (Content-Length).
     */
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

    private fun serveImage(file: java.io.File): Response =
        if (file.exists()) {
            newChunkedResponse(Response.Status.OK, "image/jpeg", FileInputStream(file))
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "kein Bild")
        }

    private fun html(body: String): Response = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body)
    private fun json(body: String): Response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", body)
    private fun ok(): Response = json("{\"ok\":true}")
}
