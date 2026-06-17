package de.beardedskunk.clipsharing.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import de.beardedskunk.clipsharing.core.Hlc
import de.beardedskunk.clipsharing.core.Post
import de.beardedskunk.clipsharing.core.PostContent
import de.beardedskunk.clipsharing.core.PostVersion
import de.beardedskunk.clipsharing.sync.OpCodec
import de.beardedskunk.clipsharing.sync.OpDto
import de.beardedskunk.clipsharing.sync.OpSource
import java.util.UUID

/**
 * Verbindet die persistente DB mit der reinen Konflikt-Engine ([Post]).
 *
 * Schreibpfad: lokale Aktionen ([createPost]/[editPost]/[deletePost]/[resolveConflict])
 * erzeugen eine [PostVersion] (Eltern = aktuelle Heads), schreiben sie in den Op-Log
 * und materialisieren den Post-Stand neu. [ingest] ist derselbe Pfad fuer beim Sync
 * empfangene Fremd-Operationen (idempotent).
 */
class FeedRepository(
    private val db: SQLiteDatabase,
    private val identity: DeviceIdentity,
) : OpSource {

    /** Wird nach jeder LOKALEN Aenderung aufgerufen (nicht beim Sync-Ingest) -> Auto-Sync. */
    var onLocalChange: (() -> Unit)? = null

    @Volatile
    private var migrated = false

    private companion object {
        /** Reservierte Feed-Id: darunter liegen die Feed-Metadaten als Ops. */
        const val FEEDS_FEED = "__feeds__"
    }

    // ---------------------------------------------------------------- Feeds

    // Feeds reisen als Ops im selben Log (feedId == FEEDS_FEED, postId == die echte
    // Feed-Id, text == Feed-Name) -> sie synchronisieren ueber Box UND Peers ohne
    // zusaetzliche Transport-Logik.

    fun createFeed(name: String): Feed {
        ensureMigrated()
        val feedId = UUID.randomUUID().toString()
        val v = author(FEEDS_FEED, feedId, emptySet(), PostContent(text = name.trim()))
        return Feed(feedId, name.trim(), v.hlc, deleted = false)
    }

    fun renameFeed(feedId: String, name: String) {
        author(FEEDS_FEED, feedId, currentHeads(feedId), PostContent(text = name.trim()))
    }

    fun deleteFeed(feedId: String) {
        author(FEEDS_FEED, feedId, currentHeads(feedId), PostContent(text = "", deleted = true))
    }

    fun listFeeds(): List<Feed> {
        ensureMigrated()
        val out = ArrayList<Feed>()
        db.rawQuery(
            "SELECT feed_id, name, created_wall, created_counter, deleted FROM feeds WHERE deleted = 0 ORDER BY created_wall, created_counter",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                out += Feed(c.getString(0), c.getString(1), Hlc(c.getLong(2), c.getInt(3)), c.getInt(4) != 0)
            }
        }
        return out
    }

    // ------------------------------------------------------- Post authoring

    fun createPost(
        feedId: String,
        text: String,
        imageHashes: List<String> = emptyList(),
        imageTitles: List<String> = emptyList(),
    ): PostVersion =
        author(feedId, UUID.randomUUID().toString(), emptySet(), PostContent(text, imageHashes, imageTitles, deleted = false))

    fun editPost(
        feedId: String,
        postId: String,
        text: String,
        imageHashes: List<String> = emptyList(),
        imageTitles: List<String> = emptyList(),
    ): PostVersion =
        author(feedId, postId, currentHeads(postId), PostContent(text, imageHashes, imageTitles, deleted = false))

    fun deletePost(feedId: String, postId: String): PostVersion =
        author(feedId, postId, currentHeads(postId), PostContent(deleted = true))

    fun resolveConflict(feedId: String, postId: String, chosen: PostContent): PostVersion =
        author(feedId, postId, currentHeads(postId), chosen)

    private fun author(feedId: String, postId: String, parents: Set<String>, content: PostContent): PostVersion {
        val version = PostVersion(postId, parents, identity.deviceId, identity.nextHlc(), content)
        val seq = identity.nextSeq()
        db.beginTransaction()
        try {
            persistOp(version, feedId, seq)
            if (feedId == FEEDS_FEED) rebuildFeedState(postId) else rebuildPostState(feedId, postId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        onLocalChange?.invoke()
        return version
    }

    // ------------------------------------------------------------ Sync ingest

    /** Speist eine beim Sync empfangene Version ein. @return true, wenn neu. */
    fun ingest(version: PostVersion, feedId: String, seq: Long): Boolean {
        if (opExists(version.versionId)) return false
        identity.observe(version.hlc)
        db.beginTransaction()
        try {
            persistOp(version, feedId, seq)
            if (feedId == FEEDS_FEED) rebuildFeedState(version.postId) else rebuildPostState(feedId, version.postId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return true
    }

    /** Versions-Vektor dieses Geraets: hoechste bekannte Seq je Autor-Geraet. */
    override fun versionVector(): Map<String, Long> {
        ensureMigrated()
        val out = HashMap<String, Long>()
        db.rawQuery("SELECT device_id, MAX(seq) FROM ops GROUP BY device_id", null).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getLong(1)
        }
        return out
    }

    /** Alle lokalen Ops, die der Gegenseite (gegeben deren VV) fehlen. */
    override fun missingFor(remote: Map<String, Long>): List<OpDto> {
        ensureMigrated()
        val out = ArrayList<OpDto>()
        db.rawQuery(
            "SELECT version_id, feed_id, post_id, device_id, seq, hlc_wall, hlc_counter, parents, deleted, text, image_hashes, image_titles " +
                "FROM ops ORDER BY device_id, seq",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val device = c.getString(3)
                val seq = c.getLong(4)
                if (seq <= (remote[device] ?: 0L)) continue
                out += OpDto(
                    versionId = c.getString(0),
                    feedId = c.getString(1),
                    postId = c.getString(2),
                    deviceId = device,
                    seq = seq,
                    hlcWall = c.getLong(5),
                    hlcCounter = c.getInt(6),
                    deleted = c.getInt(8) != 0,
                    text = c.getString(9),
                    parents = splitCsv(c.getString(7)),
                    imageHashes = splitCsv(c.getString(10)),
                    imageTitles = OpCodec.decodeTitles(c.getString(11)),
                )
            }
        }
        return out
    }

    /** Speist eine beim Sync empfangene Op (Wire-DTO) ein; verwirft inkonsistente. */
    override fun ingestOp(op: OpDto): Boolean {
        if (!op.isConsistent()) return false
        return ingest(op.toVersion(), op.feedId, op.seq)
    }

    // -------------------------------------------------------------- Reading

    fun listPosts(feedId: String): List<PostState> =
        queryPostStates("feed_id = ? AND (deleted = 0 OR conflicted = 1)", arrayOf(feedId))

    fun getPostState(postId: String): PostState? =
        queryPostStates("post_id = ?", arrayOf(postId)).firstOrNull()

    /** Vollstaendige Historie eines Posts als core-Aggregat (fuer Diff/Aufloesung). */
    fun history(postId: String): Post = loadPost(postId)

    fun search(feedId: String, query: String): List<PostState> {
        val ids = ArrayList<String>()
        db.rawQuery(
            "SELECT post_id FROM post_fts WHERE text MATCH ?",
            arrayOf(ftsQuery(query)),
        ).use { c -> while (c.moveToNext()) ids += c.getString(0) }
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        return queryPostStates(
            "feed_id = ? AND deleted = 0 AND post_id IN ($placeholders)",
            (listOf(feedId) + ids).toTypedArray(),
        )
    }

    // -------------------------------------------------------------- Internals

    private fun currentHeads(postId: String): Set<String> =
        loadPost(postId).heads().map { it.versionId }.toSet()

    private fun opExists(versionId: String): Boolean =
        db.rawQuery("SELECT 1 FROM ops WHERE version_id = ? LIMIT 1", arrayOf(versionId)).use { it.moveToFirst() }

    private fun persistOp(v: PostVersion, feedId: String, seq: Long) {
        val cv = ContentValues().apply {
            put("version_id", v.versionId)
            put("feed_id", feedId)
            put("post_id", v.postId)
            put("device_id", v.deviceId)
            put("seq", seq)
            put("hlc_wall", v.hlc.wallMillis)
            put("hlc_counter", v.hlc.counter)
            put("parents", v.parents.joinToString(","))
            put("deleted", if (v.content.deleted) 1 else 0)
            put("text", v.content.text)
            put("image_hashes", v.content.imageHashes.joinToString(","))
            put("image_titles", OpCodec.encodeTitles(v.content.imageTitles))
        }
        db.insertWithOnConflict("ops", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun loadPost(postId: String): Post {
        val post = Post(postId)
        db.rawQuery(
            "SELECT post_id, device_id, hlc_wall, hlc_counter, parents, deleted, text, image_hashes, image_titles FROM ops WHERE post_id = ?",
            arrayOf(postId),
        ).use { c ->
            while (c.moveToNext()) {
                val parents = splitCsv(c.getString(4)).toSet()
                val content = PostContent(
                    text = c.getString(6),
                    imageHashes = splitCsv(c.getString(7)),
                    imageTitles = OpCodec.decodeTitles(c.getString(8)),
                    deleted = c.getInt(5) != 0,
                )
                post.ingest(PostVersion(c.getString(0), parents, c.getString(1), Hlc(c.getLong(2), c.getInt(3)), content))
            }
        }
        return post
    }

    private fun rebuildPostState(feedId: String, postId: String) {
        val post = loadPost(postId)
        val heads = post.heads()
        if (heads.isEmpty()) return
        val shown = heads.last() // hoechste Uhr (siehe Post.headOrder)
        val root = post.allVersions().firstOrNull { it.parents.isEmpty() } ?: shown
        val cv = ContentValues().apply {
            put("post_id", postId)
            put("feed_id", feedId)
            put("head_version_id", shown.versionId)
            put("text", shown.content.text)
            put("image_hashes", shown.content.imageHashes.joinToString(","))
            put("image_titles", OpCodec.encodeTitles(shown.content.imageTitles))
            put("deleted", if (shown.content.deleted) 1 else 0)
            put("conflicted", if (heads.size > 1) 1 else 0)
            put("created_wall", root.hlc.wallMillis)
            put("created_counter", root.hlc.counter)
            put("updated_wall", shown.hlc.wallMillis)
            put("updated_counter", shown.hlc.counter)
        }
        db.insertWithOnConflict("post_current", null, cv, SQLiteDatabase.CONFLICT_REPLACE)

        db.delete("post_fts", "post_id = ?", arrayOf(postId))
        if (!shown.content.deleted) {
            db.insert("post_fts", null, ContentValues().apply {
                put("post_id", postId)
                put("text", shown.content.text)
            })
        }
    }

    /** Materialisiert den Feed-Stand aus dem Feed-Op-DAG in die feeds-Tabelle. */
    private fun rebuildFeedState(feedId: String) {
        val post = loadPost(feedId)
        val heads = post.heads()
        if (heads.isEmpty()) return
        val shown = heads.last()
        val root = post.allVersions().firstOrNull { it.parents.isEmpty() } ?: shown
        val cv = ContentValues().apply {
            put("feed_id", feedId)
            put("name", shown.content.text)
            put("created_wall", root.hlc.wallMillis)
            put("created_counter", root.hlc.counter)
            put("deleted", if (shown.content.deleted) 1 else 0)
        }
        db.insertWithOnConflict("feeds", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Einmalige Migration: bestehende (alt angelegte) Feeds bekommen einen Feed-Op, damit sie syncen. */
    @Synchronized
    private fun ensureMigrated() {
        if (migrated) return
        migrated = true
        val feeds = ArrayList<Pair<String, String>>()
        db.rawQuery("SELECT feed_id, name FROM feeds", null).use { c ->
            while (c.moveToNext()) feeds += c.getString(0) to c.getString(1)
        }
        for ((fid, name) in feeds) {
            val hasOp = db.rawQuery(
                "SELECT 1 FROM ops WHERE feed_id = ? AND post_id = ? LIMIT 1",
                arrayOf(FEEDS_FEED, fid),
            ).use { it.moveToFirst() }
            if (!hasOp) author(FEEDS_FEED, fid, emptySet(), PostContent(text = name))
        }
    }

    /** Alle aktuell angezeigten Bild-Hashes (fuer gezielten Blob-Pull). */
    override fun displayedImageHashes(): Set<String> {
        val out = HashSet<String>()
        db.rawQuery("SELECT image_hashes FROM post_current WHERE deleted = 0", null).use { c ->
            while (c.moveToNext()) out += splitCsv(c.getString(0))
        }
        return out
    }

    private fun queryPostStates(where: String, args: Array<String>): List<PostState> {
        val out = ArrayList<PostState>()
        db.rawQuery(
            "SELECT post_id, feed_id, head_version_id, text, image_hashes, deleted, conflicted, created_wall, created_counter, updated_wall, updated_counter, image_titles " +
                "FROM post_current WHERE $where ORDER BY created_wall, created_counter",
            args,
        ).use { c -> while (c.moveToNext()) out += readPostState(c) }
        return out
    }

    private fun readPostState(c: Cursor): PostState = PostState(
        postId = c.getString(0),
        feedId = c.getString(1),
        headVersionId = c.getString(2),
        text = c.getString(3),
        imageHashes = splitCsv(c.getString(4)),
        imageTitles = OpCodec.decodeTitles(c.getString(11)),
        deleted = c.getInt(5) != 0,
        conflicted = c.getInt(6) != 0,
        created = Hlc(c.getLong(7), c.getInt(8)),
        updated = Hlc(c.getLong(9), c.getInt(10)),
    )

    private fun splitCsv(s: String?): List<String> =
        if (s.isNullOrEmpty()) emptyList() else s.split(',').filter { it.isNotEmpty() }

    /** Macht aus Nutzereingabe ein einfaches FTS4-Praefix-MATCH ("foo bar" -> "foo* bar*"). */
    private fun ftsQuery(raw: String): String =
        raw.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            .joinToString(" ") { it.replace("\"", "") + "*" }
            .ifBlank { "\"\"" }
}
