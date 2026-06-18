package de.beardedskunk.clipsharing.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import de.beardedskunk.clipsharing.core.Hlc
import de.beardedskunk.clipsharing.core.Post
import de.beardedskunk.clipsharing.core.PostContent
import de.beardedskunk.clipsharing.core.PostVersion
import de.beardedskunk.clipsharing.sync.FeedScopedSource
import de.beardedskunk.clipsharing.sync.OpCodec
import de.beardedskunk.clipsharing.sync.OpDto
import de.beardedskunk.clipsharing.sync.OpSource
import de.beardedskunk.clipsharing.sync.PeerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
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
) : OpSource, FeedScopedSource {

    /** Wird nach jeder LOKALEN Aenderung aufgerufen (nicht beim Sync-Ingest) -> Auto-Sync. */
    var onLocalChange: (() -> Unit)? = null

    /** Wird nach JEDER Aenderung aufgerufen (lokal UND Sync-Ingest) -> Kalender-Sync. */
    var onAnyChange: (() -> Unit)? = null

    private val _revision = MutableStateFlow(0)

    /**
     * Erhoeht sich bei JEDER Aenderung (lokale Aktion UND Sync-Ingest). Die UI beobachtet
     * das und laedt neu – damit aktualisieren sich Feed-/Eintragslisten automatisch beim
     * Sync, statt erst beim Verlassen/Neubetreten des Screens.
     */
    val revision: StateFlow<Int> = _revision

    private fun bumpRevision() = _revision.update { it + 1 }

    @Volatile
    private var migrated = false

    private companion object {
        /** Reservierte Feed-Id: darunter liegen die Feed-Metadaten als Ops. */
        const val FEEDS_FEED = "__feeds__"

        /**
         * Version der MATERIALISIERUNGS-Logik (Ableitung von feeds/post_current aus dem
         * Op-Log). Hochzaehlen, wenn sich die Dekodierung aendert (z. B. Kalender-Marker im
         * Feed-Namen). Bei einem Anstieg werden feeds + post_current EINMALIG komplett neu
         * aus dem Log aufgebaut – sonst bleiben alt-materialisierte Zeilen stehen (Symptom:
         * Kalender-Feed nach Update als „cal\n::kalender::" / nicht als Kalender erkannt).
         */
        const val MATERIALIZATION_VERSION = 2 // v2: shared-Flag (#10) in feeds materialisieren
    }

    // ---------------------------------------------------------------- Feeds

    // Feeds reisen als Ops im selben Log (feedId == FEEDS_FEED, postId == die echte
    // Feed-Id, text == Feed-Name) -> sie synchronisieren ueber Box UND Peers ohne
    // zusaetzliche Transport-Logik.

    fun createFeed(name: String, calendar: Boolean = false): Feed {
        ensureMigrated()
        val feedId = UUID.randomUUID().toString()
        val v = author(FEEDS_FEED, feedId, emptySet(), PostContent(text = FeedMeta.encode(name, calendar)))
        return Feed(feedId, name.trim(), v.hlc, deleted = false, calendar = calendar)
    }

    fun renameFeed(feedId: String, name: String) {
        // Kalender-Flag beim Umbenennen erhalten.
        val cal = listFeeds().firstOrNull { it.id == feedId }?.calendar ?: false
        author(FEEDS_FEED, feedId, currentHeads(feedId), PostContent(text = FeedMeta.encode(name, cal)))
    }

    fun deleteFeed(feedId: String) {
        author(FEEDS_FEED, feedId, currentHeads(feedId), PostContent(text = "", deleted = true))
    }

    fun listFeeds(): List<Feed> {
        ensureMigrated()
        val out = ArrayList<Feed>()
        db.rawQuery(
            "SELECT feed_id, name, created_wall, created_counter, deleted, calendar, shared, foreign_origin, foreign_right " +
                "FROM feeds WHERE deleted = 0 ORDER BY created_wall, created_counter",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                out += Feed(
                    id = c.getString(0), name = c.getString(1), created = Hlc(c.getLong(2), c.getInt(3)),
                    deleted = c.getInt(4) != 0, calendar = c.getInt(5) != 0, shared = c.getInt(6) != 0,
                    foreignOrigin = c.getString(7), foreignRight = FeedRight.from(c.getString(8)),
                )
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
            persistOp(version, feedId, seq, identity.deviceName)
            if (feedId == FEEDS_FEED) rebuildFeedState(postId) else rebuildPostState(feedId, postId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        bumpRevision()
        onLocalChange?.invoke()
        onAnyChange?.invoke()
        return version
    }

    // ------------------------------------------------------------ Sync ingest

    /** Speist eine beim Sync empfangene Version ein. @return true, wenn neu. */
    fun ingest(version: PostVersion, feedId: String, seq: Long, deviceName: String = ""): Boolean {
        if (opExists(version.versionId)) return false
        identity.observe(version.hlc)
        db.beginTransaction()
        try {
            persistOp(version, feedId, seq, deviceName)
            if (feedId == FEEDS_FEED) rebuildFeedState(version.postId) else rebuildPostState(feedId, version.postId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        bumpRevision()
        onAnyChange?.invoke()
        return true
    }

    /** Wissensstand dieses Geraets: hoechste Seq + Luecken je Autor-Geraet. */
    override fun versionVector(): Map<String, PeerState> {
        ensureMigrated()
        val seqs = HashMap<String, MutableList<Long>>()
        db.rawQuery("SELECT device_id, seq FROM ops", null).use { c ->
            while (c.moveToNext()) seqs.getOrPut(c.getString(0)) { ArrayList() }.add(c.getLong(1))
        }
        val out = HashMap<String, PeerState>()
        for ((device, list) in seqs) {
            val present = list.toHashSet()
            val max = list.max()
            // Luecken (fehlende Seqs unter max) – damit der Gegenpart sie nachliefern kann.
            val gaps = (1L..max).filter { it !in present }
            out[device] = PeerState(max, gaps)
        }
        return out
    }

    /** Alle lokalen Ops, die der Gegenseite (gegeben deren Wissensstand inkl. Luecken) fehlen. */
    override fun missingFor(remote: Map<String, PeerState>): List<OpDto> {
        ensureMigrated()
        val remoteGaps = remote.mapValues { it.value.gaps.toHashSet() }
        val out = ArrayList<OpDto>()
        db.rawQuery(
            "SELECT version_id, feed_id, post_id, device_id, seq, hlc_wall, hlc_counter, parents, deleted, text, image_hashes, image_titles, device_name " +
                "FROM ops ORDER BY device_id, seq",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val device = c.getString(3)
                val seq = c.getLong(4)
                val st = remote[device]
                // Senden, wenn der Gegenpart das Geraet gar nicht kennt, die Seq ueber
                // seinem Maximum liegt ODER genau eine seiner Luecken fuellt.
                if (st != null && seq <= st.maxSeq && seq !in (remoteGaps[device] ?: emptySet())) continue
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
                    deviceName = c.getString(12) ?: "",
                )
            }
        }
        return out
    }

    /** Speist eine beim Sync empfangene Op (Wire-DTO) ein; verwirft inkonsistente. */
    override fun ingestOp(op: OpDto): Boolean {
        if (!op.isConsistent()) return false
        return ingest(op.toVersion(), op.feedId, op.seq, op.deviceName)
    }

    // ------------------------------ Gruppenuebergreifender Sync (#10) ------------------------------

    private fun seqStates(seqs: Map<String, MutableList<Long>>): Map<String, PeerState> {
        val out = HashMap<String, PeerState>()
        for ((device, list) in seqs) {
            val present = list.toHashSet()
            val max = list.max()
            out[device] = PeerState(max, (1L..max).filter { it !in present })
        }
        return out
    }

    override fun feedVersionVector(feedId: String): Map<String, PeerState> {
        val seqs = HashMap<String, MutableList<Long>>()
        db.rawQuery("SELECT device_id, seq FROM ops WHERE feed_id = ?", arrayOf(feedId)).use { c ->
            while (c.moveToNext()) seqs.getOrPut(c.getString(0)) { ArrayList() }.add(c.getLong(1))
        }
        return seqStates(seqs)
    }

    override fun feedMissingFor(feedId: String, remote: Map<String, PeerState>): List<OpDto> {
        val remoteGaps = remote.mapValues { it.value.gaps.toHashSet() }
        val out = ArrayList<OpDto>()
        db.rawQuery(
            "SELECT version_id, feed_id, post_id, device_id, seq, hlc_wall, hlc_counter, parents, deleted, text, image_hashes, image_titles, device_name " +
                "FROM ops WHERE feed_id = ? ORDER BY device_id, seq",
            arrayOf(feedId),
        ).use { c ->
            while (c.moveToNext()) {
                val device = c.getString(3)
                val seq = c.getLong(4)
                val st = remote[device]
                if (st != null && seq <= st.maxSeq && seq !in (remoteGaps[device] ?: emptySet())) continue
                out += readOpDto(c)
            }
        }
        return out
    }

    override fun acceptIncomingOp(op: OpDto, feedId: String): Boolean {
        if (op.feedId != feedId || !op.isConsistent()) return false
        return ingest(op.toVersion(), op.feedId, op.seq, op.deviceName)
    }

    override fun acceptForeignOp(op: OpDto, feedId: String, right: FeedRight): Boolean {
        if (op.feedId != feedId || !op.isConsistent()) return false
        if (!right.canWrite()) return false
        if (op.parents.size > 1 && !right.canMerge()) return false // Merge-Op nur mit merge-Recht
        return ingest(op.toVersion(), op.feedId, op.seq, op.deviceName)
    }

    /** Liest eine OpDto aus dem Standard-Spaltenlayout (siehe missingFor/feedMissingFor). */
    private fun readOpDto(c: Cursor): OpDto = OpDto(
        versionId = c.getString(0),
        feedId = c.getString(1),
        postId = c.getString(2),
        deviceId = c.getString(3),
        seq = c.getLong(4),
        hlcWall = c.getLong(5),
        hlcCounter = c.getInt(6),
        deleted = c.getInt(8) != 0,
        text = c.getString(9),
        parents = splitCsv(c.getString(7)),
        imageHashes = splitCsv(c.getString(10)),
        imageTitles = OpCodec.decodeTitles(c.getString(11)),
        deviceName = c.getString(12) ?: "",
    )

    // ------------------------------ Freigaben (Original-Gruppe) ------------------------------

    private fun feedText(feedId: String): String = loadPost(feedId).shownHead()?.content?.text ?: ""

    /** Aktuelle Freigaben dieses (Original-)Feeds. */
    fun feedShares(feedId: String): List<ShareGrant> = FeedShareCodec.decode(feedText(feedId))

    /** Schreibt die Freigabeliste als neue Feed-Op (Name + Kalender-Flag bleiben erhalten); synct in der Gruppe. */
    fun setFeedShares(feedId: String, grants: List<ShareGrant>) {
        val text = feedText(feedId)
        author(
            FEEDS_FEED, feedId, currentHeads(feedId),
            PostContent(text = FeedShareCodec.feedText(FeedMeta.decodeName(text), FeedMeta.decodeCalendar(text), grants)),
        )
    }

    fun addShare(feedId: String, grant: ShareGrant) =
        setFeedShares(feedId, feedShares(feedId).filter { it.capId != grant.capId } + grant)

    fun setShareRight(feedId: String, capId: String, right: FeedRight) =
        setFeedShares(feedId, feedShares(feedId).map { if (it.capId == capId) it.copy(right = right) else it })

    fun revokeShare(feedId: String, capId: String) =
        setFeedShares(feedId, feedShares(feedId).filter { it.capId != capId })

    /** Recht einer Freigabe (für die Original-Seite beim Cross-Group-Sync). null = unbekannt/widerrufen. */
    fun grantFor(feedId: String, capId: String): ShareGrant? = feedShares(feedId).firstOrNull { it.capId == capId }

    // ------------------------------ Fremdfeeds (Fremdgerät) ------------------------------

    /** Registriert/aktualisiert einen abonnierten Fremdfeed lokal (nicht als Feed-Op – fremde Gruppe). */
    fun registerForeignFeed(ref: ForeignFeedRef, name: String, calendar: Boolean) {
        ensureMigrated()
        val h = identity.nextHlc()
        db.insertWithOnConflict(
            "feeds", null,
            ContentValues().apply {
                put("feed_id", ref.feedId)
                put("name", name)
                put("created_wall", h.wallMillis)
                put("created_counter", h.counter)
                put("deleted", 0)
                put("calendar", if (calendar) 1 else 0)
                put("shared", 0)
                put("foreign_origin", ref.originGroup)
                put("cap_id", ref.capId)
                put("cap_secret", ref.capSecret)
                put("foreign_right", ref.right.name)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        bumpRevision()
        onAnyChange?.invoke()
    }

    fun listForeignFeeds(): List<ForeignFeedRef> {
        ensureMigrated()
        val out = ArrayList<ForeignFeedRef>()
        db.rawQuery(
            "SELECT feed_id, foreign_origin, cap_id, cap_secret, foreign_right FROM feeds WHERE foreign_origin <> '' AND deleted = 0",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                out += ForeignFeedRef(c.getString(0), c.getString(1), c.getString(2), c.getString(3), FeedRight.from(c.getString(4)))
            }
        }
        return out
    }

    fun foreignFeedRef(feedId: String): ForeignFeedRef? = listForeignFeeds().firstOrNull { it.feedId == feedId }

    /** Aktualisiert das vom Original mitgeteilte Recht eines Fremdfeeds (für UI-Gating). */
    fun updateForeignRight(feedId: String, right: FeedRight) {
        db.execSQL("UPDATE feeds SET foreign_right = ? WHERE feed_id = ? AND foreign_origin <> ''", arrayOf(right.name, feedId))
        bumpRevision()
    }

    /** „Freigabe verlassen": Fremdfeed lokal komplett entfernen (kein Upstream-Eingriff). */
    fun leaveForeignFeed(feedId: String) {
        db.beginTransaction()
        try {
            db.delete("post_fts", "post_id IN (SELECT DISTINCT post_id FROM ops WHERE feed_id = ?)", arrayOf(feedId))
            db.delete("post_current", "feed_id = ?", arrayOf(feedId))
            db.delete("ops", "feed_id = ?", arrayOf(feedId))
            db.delete("feeds", "feed_id = ? AND foreign_origin <> ''", arrayOf(feedId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        bumpRevision()
        onAnyChange?.invoke()
    }

    // -------------------------------------------------------------- Reading

    fun listPosts(feedId: String): List<PostState> =
        queryPostStates("feed_id = ? AND (deleted = 0 OR conflicted = 1)", arrayOf(feedId))

    /**
     * Alle Einträge in Kalender-Feeds – inkl. gelöschter (damit der Kalender-Sync sie
     * im Android-Kalender wieder entfernen kann). Für [de.beardedskunk.clipsharing.calendar.CalendarSync].
     */
    fun calendarEntries(): List<PostState> =
        queryPostStates("feed_id IN (SELECT feed_id FROM feeds WHERE calendar = 1)", emptyArray())

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

    private fun persistOp(v: PostVersion, feedId: String, seq: Long, deviceName: String) {
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
            put("device_name", deviceName)
        }
        db.insertWithOnConflict("ops", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    /**
     * Karte deviceId -> menschlicher Name (juengster bekannter Name je Geraet).
     * Fuer die Konflikt-Ansicht, damit dort "F101"/"Pixel" statt Id-Stummel stehen.
     */
    /** Id dieses Geraets – fuer die Detail-Merge-Ansicht (zuletzt lokal aktive Fassung). */
    fun localDeviceId(): String = identity.deviceId

    fun deviceNames(): Map<String, String> {
        val out = HashMap<String, String>()
        db.rawQuery(
            "SELECT device_id, device_name FROM ops WHERE device_name <> '' ORDER BY seq",
            null,
        ).use { c -> while (c.moveToNext()) out[c.getString(0)] = c.getString(1) }
        return out
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
        // Echter Konflikt nur bei inhaltlich unterschiedlichen Heads – inhaltsgleiche Heads
        // (gleiche Aenderung offline doppelt gemacht / ueber zwei Sync-Pfade) sind keiner.
        // Einzige Quelle der Wahrheit: Post.hasContentConflict() (per Unit-Test abgesichert).
        val realConflict = post.hasContentConflict()
        val cv = ContentValues().apply {
            put("post_id", postId)
            put("feed_id", feedId)
            put("head_version_id", shown.versionId)
            put("text", shown.content.text)
            put("image_hashes", shown.content.imageHashes.joinToString(","))
            put("image_titles", OpCodec.encodeTitles(shown.content.imageTitles))
            put("deleted", if (shown.content.deleted) 1 else 0)
            put("conflicted", if (realConflict) 1 else 0)
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
                // Bildtitel mit indexieren -> Suche findet auch Titel.
                put("text", (shown.content.text + " " + shown.content.imageTitles.joinToString(" ")).trim())
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
            put("name", FeedMeta.decodeName(shown.content.text))
            put("created_wall", root.hlc.wallMillis)
            put("created_counter", root.hlc.counter)
            put("deleted", if (shown.content.deleted) 1 else 0)
            put("calendar", if (FeedMeta.decodeCalendar(shown.content.text)) 1 else 0)
            put("shared", if (FeedShareCodec.isShared(shown.content.text)) 1 else 0)
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
        rematerializeIfStale()
    }

    /**
     * Baut feeds + post_current EINMALIG komplett aus dem Op-Log neu auf, wenn sich die
     * Materialisierungs-Logik geaendert hat ([MATERIALIZATION_VERSION]). Die Cache-Tabellen
     * werden beim Ingest mit der DAMALIGEN Code-Version befuellt; aendert sich spaeter die
     * Dekodierung (z. B. Kalender-Marker), bleiben Altzeilen sonst falsch stehen. Idempotent
     * (rein aus dem unveraenderlichen Log abgeleitet).
     */
    private fun rematerializeIfStale() {
        db.execSQL("CREATE TABLE IF NOT EXISTS app_meta(key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
        val current = db.rawQuery("SELECT value FROM app_meta WHERE key = 'mat_version'", null).use {
            if (it.moveToFirst()) it.getString(0).toIntOrNull() ?: 0 else 0
        }
        if (current >= MATERIALIZATION_VERSION) return
        db.beginTransaction()
        try {
            val feedIds = ArrayList<String>()
            db.rawQuery("SELECT DISTINCT post_id FROM ops WHERE feed_id = ?", arrayOf(FEEDS_FEED)).use {
                while (it.moveToNext()) feedIds.add(it.getString(0))
            }
            for (fid in feedIds) rebuildFeedState(fid)

            val posts = ArrayList<Pair<String, String>>()
            db.rawQuery("SELECT DISTINCT feed_id, post_id FROM ops WHERE feed_id <> ?", arrayOf(FEEDS_FEED)).use {
                while (it.moveToNext()) posts.add(it.getString(0) to it.getString(1))
            }
            for ((feed, post) in posts) rebuildPostState(feed, post)

            db.execSQL(
                "INSERT OR REPLACE INTO app_meta(key, value) VALUES('mat_version', ?)",
                arrayOf(MATERIALIZATION_VERSION.toString()),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        // Nach dem Neuaufbau koennen Feeds erst JETZT als Kalender-Feeds erkannt sein
        // (calendar=1). Einen Kalender-Sync anstossen, sonst landen deren Termine erst
        // beim naechsten Neustart/Edit im Android-Kalender.
        bumpRevision()
        onAnyChange?.invoke()
    }

    /** Alle aktuell angezeigten Bild-Hashes (fuer gezielten Blob-Pull). */
    override fun displayedImageHashes(): Set<String> {
        val out = HashSet<String>()
        db.rawQuery("SELECT image_hashes FROM post_current WHERE deleted = 0", null).use { c ->
            while (c.moveToNext()) out += splitCsv(c.getString(0))
        }
        // Bei Konflikten ALLE Bilder aller Fassungen holen, damit die Konflikt-Ansicht
        // beide Bilder anzeigen kann (nicht nur die des materialisierten Heads).
        db.rawQuery(
            "SELECT image_hashes FROM ops WHERE post_id IN (SELECT post_id FROM post_current WHERE conflicted = 1)",
            null,
        ).use { c -> while (c.moveToNext()) out += splitCsv(c.getString(0)) }
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
