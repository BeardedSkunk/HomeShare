package de.beardedskunk.homeshare.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import de.beardedskunk.homeshare.core.NodeContent
import de.beardedskunk.homeshare.core.NodeType
import de.beardedskunk.homeshare.data.BlobStore
import de.beardedskunk.homeshare.data.FeedRepository
import de.beardedskunk.homeshare.data.NodeState

/**
 * Gemeinsame Anlege-Logik für Anhänge (Bild/Datei): Blob speichern, Knoten + leeres
 * Beschreibungs-Kind erzeugen. Genutzt vom Beitrags-Editor (Anhänge eines Beitrags) und
 * vom Listen-FAB (eigenständige Anhang-Einträge). IO-Arbeit – auf Dispatchers.IO aufrufen.
 */
object AttachmentPicker {

    data class Added(val nodeId: String, val sha: String, val captionId: String)

    /** Anzeigename einer Content-Uri (DISPLAY_NAME), Fallback letztes Pfadsegment. */
    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        } ?: uri.lastPathSegment

    /**
     * Bild von [uri] als IMAGE-Knoten unter [parentId] ablegen. Null wenn nicht lesbar.
     * Das Beschreibungs-Kind (die "Anhang-Notiz") startet mit dem Dateinamen als Titel.
     */
    fun addImage(context: Context, repo: FeedRepository, blobStore: BlobStore, parentId: String, uri: Uri): Added? {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val sha = blobStore.put(bytes)
        val name = displayName(context, uri).orEmpty()
        val imgId = repo.createNode(NodeContent(parentId = parentId, type = NodeType.IMAGE, blobHash = sha)).nodeId
        val capId = repo.createNode(NodeContent(parentId = imgId, type = NodeType.TEXT, text = name)).nodeId
        return Added(imgId, sha, capId)
    }

    /** Beliebige Datei von [uri] als FILE-Knoten unter [parentId] ablegen (Name+MIME vom Provider). */
    fun addFile(context: Context, repo: FeedRepository, blobStore: BlobStore, parentId: String, uri: Uri): Added? {
        val resolver = context.contentResolver
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val sha = blobStore.put(bytes)
        val name = displayName(context, uri) ?: "Datei"
        val mime = resolver.getType(uri)
        val fileId = repo.createNode(
            NodeContent(parentId = parentId, type = NodeType.FILE, text = name, blobHash = sha, fileName = name, mime = mime),
        ).nodeId
        val capId = repo.createNode(NodeContent(parentId = fileId, type = NodeType.TEXT, text = name)).nodeId
        return Added(fileId, sha, capId)
    }

    /** Anhang (Bild oder Datei) über den System-Share-Dialog teilen. */
    fun shareExternally(context: Context, blobStore: BlobStore, node: NodeState) {
        val sha = node.blobHash ?: return toast(context, "Kein Inhalt zum Teilen.")
        val file = when {
            blobStore.hasFull(sha) -> blobStore.fullFile(sha)
            node.type == NodeType.IMAGE && blobStore.hasThumb(sha) -> blobStore.thumbFile(sha)
            else -> return toast(context, "Inhalt nicht lokal – erst syncen.")
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = node.mime ?: if (node.type == NodeType.IMAGE) "image/*" else "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(send, "Teilen")) }
    }

    /** MIME, der sich gefahrlos als Text öffnen lässt (fürs „Als Text öffnen"-Menü). */
    fun isTextLike(mime: String?): Boolean =
        mime != null && (mime.startsWith("text/") || mime.endsWith("/json") || mime.endsWith("/xml") || mime.endsWith("+json") || mime.endsWith("+xml"))

    /** Wie [openExternally], aber erzwungen als text/plain (Text-Editor/Viewer). */
    fun openAsText(context: Context, blobStore: BlobStore, node: NodeState) {
        val sha = node.blobHash ?: return toast(context, "Datei ohne Inhalt.")
        if (!blobStore.hasFull(sha)) return toast(context, "Datei nicht lokal – erst syncen.")
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", blobStore.fullFile(sha))
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "text/plain"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(view) }.onFailure { toast(context, "Kein Text-Betrachter gefunden.") }
    }

    /** FILE-/IMAGE-Knoten mit externer App öffnen (ACTION_VIEW über den FileProvider). */
    fun openExternally(context: Context, blobStore: BlobStore, node: NodeState) {
        val sha = node.blobHash ?: return toast(context, "Datei ohne Inhalt.")
        if (!blobStore.hasFull(sha)) return toast(context, "Datei nicht lokal – erst syncen.")
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", blobStore.fullFile(sha))
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, node.mime ?: "*/*"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(view) }.onFailure { toast(context, "Keine App zum Öffnen gefunden.") }
    }
}
