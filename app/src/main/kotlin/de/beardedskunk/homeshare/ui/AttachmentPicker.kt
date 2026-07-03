package de.beardedskunk.homeshare.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import de.beardedskunk.homeshare.core.NodeContent
import de.beardedskunk.homeshare.core.NodeType
import de.beardedskunk.homeshare.data.BlobStore
import de.beardedskunk.homeshare.data.FeedRepository

/**
 * Gemeinsame Anlege-Logik für Anhänge (Bild/Datei): Blob speichern, Knoten + leeres
 * Beschreibungs-Kind erzeugen. Genutzt vom Beitrags-Editor (Anhänge eines Beitrags) und
 * vom Listen-FAB (eigenständige Anhang-Einträge). IO-Arbeit – auf Dispatchers.IO aufrufen.
 */
object AttachmentPicker {

    data class Added(val nodeId: String, val sha: String, val captionId: String)

    /** Bild von [uri] als IMAGE-Knoten unter [parentId] ablegen. Null wenn nicht lesbar. */
    fun addImage(context: Context, repo: FeedRepository, blobStore: BlobStore, parentId: String, uri: Uri): Added? {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val sha = blobStore.put(bytes)
        val imgId = repo.createNode(NodeContent(parentId = parentId, type = NodeType.IMAGE, blobHash = sha)).nodeId
        val capId = repo.createNode(NodeContent(parentId = imgId, type = NodeType.TEXT, text = "")).nodeId
        return Added(imgId, sha, capId)
    }

    /** Beliebige Datei von [uri] als FILE-Knoten unter [parentId] ablegen (Name+MIME vom Provider). */
    fun addFile(context: Context, repo: FeedRepository, blobStore: BlobStore, parentId: String, uri: Uri): Added? {
        val resolver = context.contentResolver
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val sha = blobStore.put(bytes)
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "Datei"
        val mime = resolver.getType(uri)
        val fileId = repo.createNode(
            NodeContent(parentId = parentId, type = NodeType.FILE, text = name, blobHash = sha, fileName = name, mime = mime),
        ).nodeId
        val capId = repo.createNode(NodeContent(parentId = fileId, type = NodeType.TEXT, text = "")).nodeId
        return Added(fileId, sha, capId)
    }
}
