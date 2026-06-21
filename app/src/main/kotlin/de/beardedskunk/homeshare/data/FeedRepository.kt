package de.beardedskunk.homeshare.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import de.beardedskunk.homeshare.core.Hlc
import de.beardedskunk.homeshare.core.Node
import de.beardedskunk.homeshare.core.NodeContent
import de.beardedskunk.homeshare.core.NodeType
import de.beardedskunk.homeshare.core.NodeVersion
import de.beardedskunk.homeshare.core.ROOT
import de.beardedskunk.homeshare.sync.FeedScopedSource
import de.beardedskunk.homeshare.sync.OpDto
import de.beardedskunk.homeshare.sync.OpSource
import de.beardedskunk.homeshare.sync.PeerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * Verbindet die persistente DB mit der reinen Konflikt-Engine ([Node]) – jetzt für einen
 * **Knoten-Baum**. Jede lokale Aktion (create/edit/delete/move/resolve) erzeugt eine [NodeVersion]
 * (Eltern = aktuelle Heads), schreibt sie in den Op-Log und materialisiert den Knoten neu. [ingest]
 * ist derselbe Pfad für beim Sync empfangene Fremd-Ops (idempotent).
 *
 * Feeds = Wurzelknoten (parentId==[ROOT], TEXT). „Einträge" = Kindknoten; Bilder/Dateien = weitere
 * Kindknoten; deren Beschreibung = TEXT-Kindknoten. `root_id` (oberster Vorfahr) macht den
 * subtree-bezogenen Cross-Group-Sync billig.
 *
 * (Name bleibt FeedRepository, um Churn klein zu halten; verwaltet aber Knoten.)
 */
class FeedRepository(
    private val db: SQLiteDatabase,
    private val identity: DeviceIdentity,
) : OpSource, FeedScopedSource {

    var onLocalChange: (() -> Unit)? = null
    var onAnyChange: (() -> Unit)? = null

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision
    private fun bumpRevision() = _revision.update { it + 1 }

    fun localDeviceId(): String = identity.deviceId

    // ----------------------------------------------------------- Authoring (intern)

    private fun rootOfParent(parentId: String): String {
        if (parentId == ROOT) return parentId
        db.rawQuery("SELECT root_id FROM node_current WHERE node_id = ? LIMIT 1", arrayOf(parentId)).use {
            if (it.moveToFirst()) return it.getString(0)
        }
        db.rawQuery("SELECT root_id FROM ops WHERE node_id = ? LIMIT 1", arrayOf(parentId)).use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return parentId // best effort, falls Eltern noch unbekannt
    }

    /** Lokale Op erzeugen + persistieren + materialisieren. */
    private fun author(nodeId: String, parents: Set<String>, content: NodeContent): NodeVersion {
        val version = NodeVersion(nodeId, parents, identity.deviceId, identity.nextHlc(), content)
        val rootId = if (content.parentId == ROOT) nodeId else rootOfParent(content.parentId)
        val seq = identity.nextSeq()
        db.beginTransaction()
        try {
            persistOp(version, rootId, seq, identity.deviceName)
            rebuildNodeState(nodeId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        bumpRevision()
        onLocalChange?.invoke()
        onAnyChange?.invoke()
        return version
    }

    private fun currentHeads(nodeId: String): Set<String> =
        loadNode(nodeId).heads().map { it.versionId }.toSet()

    /** Aktueller (angezeigter) Inhalt eines Knotens – Basis für Edits, die Felder erhalten sollen. */
    fun headContent(nodeId: String): NodeContent? = loadNode(nodeId).shownHead()?.content

    // ----------------------------------------------------------- Öffentliche Knoten-API

    fun createNode(content: NodeContent): NodeState {
        val id = UUID.randomUUID().toString()
        author(id, emptySet(), content)
        return getNode(id)!!
    }

    fun editNode(nodeId: String, content: NodeContent): NodeVersion =
        author(nodeId, currentHeads(nodeId), content)

    fun deleteNode(nodeId: String): NodeVersion {
        val hc = headContent(nodeId) ?: NodeContent()
        return author(nodeId, currentHeads(nodeId), hc.copy(deleted = true))
    }

    fun moveNode(nodeId: String, newParentId: String, orderKey: String = "") {
        val hc = headContent(nodeId) ?: return
        editNode(nodeId, hc.copy(parentId = newParentId, orderKey = orderKey))
    }

    fun resolveConflict(nodeId: String, chosen: NodeContent): NodeVersion =
        author(nodeId, currentHeads(nodeId), chosen)

    // ----------------------------------------------------------- Feeds (= Wurzelknoten)

    fun createFeed(name: String, calendar: Boolean = false): NodeState = createNode(
        NodeContent(
            parentId = ROOT, type = NodeType.TEXT, text = name.trim(),
            childDefault = if (calendar) NodeType.CALENDAR else NodeType.TEXT,
        ),
    )

    fun renameFeed(feedId: String, name: String) {
        val hc = headContent(feedId) ?: return
        val grants = FeedShareCodec.decode(hc.text)
        editNode(feedId, hc.copy(text = FeedShareCodec.feedText(name.trim(), grants)))
    }

    fun deleteFeed(feedId: String) { deleteNode(feedId) }

    /** Wurzelknoten (Feeds), inkl. abonnierte Fremd-Wurzeln. */
    fun listFeeds(): List<NodeState> = queryNodeStates("n.parent_id = ? AND n.deleted = 0", arrayOf(ROOT))

    fun children(parentId: String): List<NodeState> =
        queryNodeStates("n.parent_id = ? AND (n.deleted = 0 OR n.conflicted = 1)", arrayOf(parentId))

    fun listPosts(feedId: String): List<NodeState> = children(feedId)

    fun getNode(nodeId: String): NodeState? =
        queryNodeStates("n.node_id = ?", arrayOf(nodeId)).firstOrNull()

    fun getPostState(nodeId: String): NodeState? = getNode(nodeId)

    fun history(nodeId: String): Node = loadNode(nodeId)

    /** Alle CALENDAR-Knoten (inkl. gelöschter, damit der Kalender-Sync sie entfernen kann). */
    fun calendarEntries(): List<NodeState> = queryNodeStates("n.type = ?", arrayOf(NodeType.CALENDAR.name))

    // ----------------------------------------------------------- Suche

    fun search(feedId: String, query: String): List<NodeState> {
        val ids = ftsIds(query)
        if (ids.isEmpty()) return emptyList()
        val ph = ids.joinToString(",") { "?" }
        return queryNodeStates(
            "n.root_id = ? AND n.deleted = 0 AND n.node_id IN ($ph)",
            (listOf(feedId) + ids).toTypedArray(),
        )
    }

    /** Root-Ids (Feeds), in denen die Suche etwas findet (Text/Dateiname/Tags) ODER deren Name passt. */
    fun feedsMatching(query: String): Set<String> {
        val q = query.trim()
        if (q.isBlank()) return emptySet()
        val out = HashSet<String>()
        val ids = ftsIds(q)
        if (ids.isNotEmpty()) {
            val ph = ids.joinToString(",") { "?" }
            db.rawQuery("SELECT DISTINCT root_id FROM node_current WHERE deleted = 0 AND node_id IN ($ph)", ids.toTypedArray()).use {
                while (it.moveToNext()) out += it.getString(0)
            }
        }
        db.rawQuery("SELECT node_id FROM node_current WHERE parent_id = ? AND deleted = 0 AND text LIKE ?", arrayOf(ROOT, "%$q%")).use {
            while (it.moveToNext()) out += it.getString(0)
        }
        return out
    }

    private fun ftsIds(query: String): List<String> {
        val ids = ArrayList<String>()
        runCatching {
            db.rawQuery("SELECT node_id FROM node_fts WHERE text MATCH ?", arrayOf(ftsQuery(query))).use {
                while (it.moveToNext()) ids += it.getString(0)
            }
        }
        return ids
    }

    fun deviceNames(): Map<String, String> {
        val out = HashMap<String, String>()
        db.rawQuery("SELECT device_id, device_name FROM ops WHERE device_name <> '' ORDER BY seq", null)
            .use { c -> while (c.moveToNext()) out[c.getString(0)] = c.getString(1) }
        return out
    }

    // ----------------------------------------------------------- Sync ingest

    fun ingest(version: NodeVersion, rootId: String, seq: Long, deviceName: String = ""): Boolean {
        if (opExists(version.versionId)) return false
        identity.observe(version.hlc)
        db.beginTransaction()
        try {
            persistOp(version, rootId, seq, deviceName)
            rebuildNodeState(version.nodeId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        bumpRevision()
        onAnyChange?.invoke()
        maybeAutoResolve(version.nodeId)
        return true
    }

    /** Hintergrund-Auto-Merge wie git/kdiff3; Fremd-Subtrees NICHT (nur stromaufwärts auflösen). */
    private fun maybeAutoResolve(nodeId: String) {
        val rootId = db.rawQuery("SELECT root_id FROM node_current WHERE node_id = ? LIMIT 1", arrayOf(nodeId)).use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: return
        if (isForeignRoot(rootId)) return
        val merged = runCatching { loadNode(nodeId).autoMergeContent() }.getOrNull() ?: return
        author(nodeId, currentHeads(nodeId), merged)
    }

    override fun versionVector(): Map<String, PeerState> {
        val seqs = HashMap<String, MutableList<Long>>()
        db.rawQuery("SELECT device_id, seq FROM ops", null).use { c ->
            while (c.moveToNext()) seqs.getOrPut(c.getString(0)) { ArrayList() }.add(c.getLong(1))
        }
        return seqStates(seqs)
    }

    override fun missingFor(remote: Map<String, PeerState>): List<OpDto> {
        val remoteGaps = remote.mapValues { it.value.gaps.toHashSet() }
        val out = ArrayList<OpDto>()
        db.rawQuery("$OP_SELECT ORDER BY device_id, seq", null).use { c ->
            while (c.moveToNext()) {
                val device = c.getString(IDX_DEVICE)
                val seq = c.getLong(IDX_SEQ)
                val st = remote[device]
                if (st != null && seq <= st.maxSeq && seq !in (remoteGaps[device] ?: emptySet())) continue
                out += readOpDto(c)
            }
        }
        return out
    }

    override fun ingestOp(op: OpDto): Boolean {
        if (!op.isConsistent()) return false
        return ingest(op.toVersion(), op.rootId, op.seq, op.deviceName)
    }

    override fun displayedBlobHashes(): Set<String> {
        val out = HashSet<String>()
        db.rawQuery("SELECT blob_hash FROM node_current WHERE deleted = 0 AND blob_hash <> ''", null).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        db.rawQuery(
            "SELECT blob_hash FROM ops WHERE blob_hash <> '' AND node_id IN (SELECT node_id FROM node_current WHERE conflicted = 1)",
            null,
        ).use { c -> while (c.moveToNext()) out += c.getString(0) }
        return out
    }

    // ------------------------------ Cross-Group-Sync (#10, root-/subtree-bezogen) ------------------------------

    override fun feedVersionVector(rootId: String): Map<String, PeerState> {
        val seqs = HashMap<String, MutableList<Long>>()
        db.rawQuery("SELECT device_id, seq FROM ops WHERE root_id = ?", arrayOf(rootId)).use { c ->
            while (c.moveToNext()) seqs.getOrPut(c.getString(0)) { ArrayList() }.add(c.getLong(1))
        }
        return seqStates(seqs)
    }

    override fun feedMissingFor(rootId: String, remote: Map<String, PeerState>): List<OpDto> {
        val remoteGaps = remote.mapValues { it.value.gaps.toHashSet() }
        val out = ArrayList<OpDto>()
        db.rawQuery("$OP_SELECT WHERE root_id = ? ORDER BY device_id, seq", arrayOf(rootId)).use { c ->
            while (c.moveToNext()) {
                val device = c.getString(IDX_DEVICE)
                val seq = c.getLong(IDX_SEQ)
                val st = remote[device]
                if (st != null && seq <= st.maxSeq && seq !in (remoteGaps[device] ?: emptySet())) continue
                out += readOpDto(c)
            }
        }
        return out
    }

    override fun acceptIncomingOp(op: OpDto, rootId: String): Boolean {
        if (op.rootId != rootId || !op.isConsistent()) return false
        return ingest(op.toVersion(), op.rootId, op.seq, op.deviceName)
    }

    override fun acceptForeignOp(op: OpDto, rootId: String, right: FeedRight): Boolean {
        if (op.rootId != rootId || !op.isConsistent()) return false
        if (!right.canWrite()) return false
        if (op.parents.size > 1 && !right.canMerge()) return false
        return ingest(op.toVersion(), op.rootId, op.seq, op.deviceName)
    }

    // ------------------------------ Freigaben (Original-Gruppe) ------------------------------

    private fun feedText(rootId: String): String = headContent(rootId)?.text ?: ""

    fun feedShares(rootId: String): List<ShareGrant> = FeedShareCodec.decode(feedText(rootId))

    fun setFeedShares(rootId: String, grants: List<ShareGrant>) {
        val hc = headContent(rootId) ?: return
        val name = FeedShareCodec.nameOf(hc.text)
        editNode(rootId, hc.copy(text = FeedShareCodec.feedText(name, grants)))
    }

    fun addShare(rootId: String, grant: ShareGrant) =
        setFeedShares(rootId, feedShares(rootId).filter { it.capId != grant.capId } + grant)

    fun setShareRight(rootId: String, capId: String, right: FeedRight) =
        setFeedShares(rootId, feedShares(rootId).map { if (it.capId == capId) it.copy(right = right) else it })

    fun revokeShare(rootId: String, capId: String) =
        setFeedShares(rootId, feedShares(rootId).filter { it.capId != capId })

    fun grantFor(rootId: String, capId: String): ShareGrant? = feedShares(rootId).firstOrNull { it.capId == capId }

    // ------------------------------ Fremd-Wurzeln (Fremdgerät) ------------------------------

    private fun isForeignRoot(rootId: String): Boolean =
        db.rawQuery("SELECT 1 FROM foreign_refs WHERE node_id = ? LIMIT 1", arrayOf(rootId)).use { it.moveToFirst() }

    fun registerForeignFeed(ref: ForeignFeedRef, name: String, calendar: Boolean) {
        db.insertWithOnConflict(
            "foreign_refs", null,
            ContentValues().apply {
                put("node_id", ref.nodeId)
                put("origin_group", ref.originGroup)
                put("cap_id", ref.capId)
                put("cap_secret", ref.capSecret)
                put("foreign_right", ref.right.name)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        // Platzhalter-Knoten, damit der Feed sofort sichtbar ist; echte Ops überschreiben beim Sync.
        if (getNode(ref.nodeId) == null) {
            val h = identity.nextHlc()
            db.insertWithOnConflict(
                "node_current", null,
                ContentValues().apply {
                    put("node_id", ref.nodeId); put("parent_id", ROOT); put("root_id", ref.nodeId)
                    put("type", NodeType.TEXT.name); put("head_version_id", ""); put("order_key", "")
                    put("text", name); put("done", 0); put("blob_hash", ""); put("file_name", ""); put("mime", "")
                    putNull("color"); put("child_default", if (calendar) NodeType.CALENDAR.name else NodeType.TEXT.name)
                    put("tags", ""); put("deleted", 0); put("conflicted", 0)
                    put("created_wall", h.wallMillis); put("created_counter", h.counter)
                    put("updated_wall", h.wallMillis); put("updated_counter", h.counter)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
        }
        bumpRevision(); onAnyChange?.invoke()
    }

    fun listForeignFeeds(): List<ForeignFeedRef> {
        val out = ArrayList<ForeignFeedRef>()
        db.rawQuery("SELECT node_id, origin_group, cap_id, cap_secret, foreign_right FROM foreign_refs", null).use { c ->
            while (c.moveToNext()) out += ForeignFeedRef(c.getString(0), c.getString(1), c.getString(2), c.getString(3), FeedRight.from(c.getString(4)))
        }
        return out
    }

    fun foreignFeedRef(nodeId: String): ForeignFeedRef? = listForeignFeeds().firstOrNull { it.nodeId == nodeId }

    fun updateForeignRight(nodeId: String, right: FeedRight) {
        db.execSQL("UPDATE foreign_refs SET foreign_right = ? WHERE node_id = ?", arrayOf(right.name, nodeId))
        bumpRevision()
    }

    fun leaveForeignFeed(nodeId: String) {
        db.beginTransaction()
        try {
            db.delete("node_fts", "node_id IN (SELECT node_id FROM node_current WHERE root_id = ?)", arrayOf(nodeId))
            db.delete("node_current", "root_id = ?", arrayOf(nodeId))
            db.delete("ops", "root_id = ?", arrayOf(nodeId))
            db.delete("foreign_refs", "node_id = ?", arrayOf(nodeId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        bumpRevision(); onAnyChange?.invoke()
    }

    // ----------------------------------------------------------- Internals

    private fun seqStates(seqs: Map<String, MutableList<Long>>): Map<String, PeerState> {
        val out = HashMap<String, PeerState>()
        for ((device, list) in seqs) {
            val present = list.toHashSet()
            val max = list.max()
            out[device] = PeerState(max, (1L..max).filter { it !in present })
        }
        return out
    }

    private fun opExists(versionId: String): Boolean =
        db.rawQuery("SELECT 1 FROM ops WHERE version_id = ? LIMIT 1", arrayOf(versionId)).use { it.moveToFirst() }

    private fun persistOp(v: NodeVersion, rootId: String, seq: Long, deviceName: String) {
        val c = v.content
        val cv = ContentValues().apply {
            put("version_id", v.versionId)
            put("node_id", v.nodeId)
            put("parent_id", c.parentId)
            put("root_id", rootId)
            put("device_id", v.deviceId)
            put("seq", seq)
            put("hlc_wall", v.hlc.wallMillis)
            put("hlc_counter", v.hlc.counter)
            put("parents", v.parents.joinToString(","))
            put("deleted", if (c.deleted) 1 else 0)
            put("type", c.type.name)
            put("order_key", c.orderKey)
            if (c.color != null) put("color", c.color) else putNull("color")
            put("child_default", c.childDefault?.name ?: "")
            put("tags", OpListCodec.encode(c.tags))
            put("blob_hash", c.blobHash ?: "")
            put("file_name", c.fileName ?: "")
            put("mime", c.mime ?: "")
            put("done", if (c.done) 1 else 0)
            put("text", c.text)
            put("device_name", deviceName)
        }
        db.insertWithOnConflict("ops", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun loadNode(nodeId: String): Node {
        val node = Node(nodeId)
        db.rawQuery(
            "SELECT node_id, device_id, hlc_wall, hlc_counter, parents, deleted, type, parent_id, order_key, color, child_default, tags, blob_hash, file_name, mime, done, text " +
                "FROM ops WHERE node_id = ?",
            arrayOf(nodeId),
        ).use { c ->
            while (c.moveToNext()) {
                val parents = splitCsv(c.getString(4)).toSet()
                val content = NodeContent(
                    parentId = c.getString(7),
                    type = runCatching { NodeType.valueOf(c.getString(6)) }.getOrDefault(NodeType.TEXT),
                    orderKey = c.getString(8),
                    text = c.getString(16),
                    done = c.getInt(15) != 0,
                    blobHash = c.getString(12).takeIf { it.isNotEmpty() },
                    fileName = c.getString(13).takeIf { it.isNotEmpty() },
                    mime = c.getString(14).takeIf { it.isNotEmpty() },
                    color = if (c.isNull(9)) null else c.getInt(9),
                    childDefault = c.getString(10).takeIf { it.isNotEmpty() }?.let { runCatching { NodeType.valueOf(it) }.getOrNull() },
                    tags = OpListCodec.decode(c.getString(11)),
                    deleted = c.getInt(5) != 0,
                )
                node.ingest(NodeVersion(c.getString(0), parents, c.getString(1), Hlc(c.getLong(2), c.getInt(3)), content))
            }
        }
        return node
    }

    private fun rebuildNodeState(nodeId: String) {
        val node = loadNode(nodeId)
        val heads = node.heads()
        if (heads.isEmpty()) return
        val shown = heads.last()
        val root = node.allVersions().firstOrNull { it.parents.isEmpty() } ?: shown
        val realConflict = node.hasContentConflict() && !node.hasMissingAncestors()
        val rootId = db.rawQuery("SELECT root_id FROM ops WHERE version_id = ? LIMIT 1", arrayOf(shown.versionId)).use {
            if (it.moveToFirst()) it.getString(0) else if (shown.content.parentId == ROOT) nodeId else rootOfParent(shown.content.parentId)
        }
        val c = shown.content
        val cv = ContentValues().apply {
            put("node_id", nodeId)
            put("parent_id", c.parentId)
            put("root_id", rootId)
            put("type", c.type.name)
            put("head_version_id", shown.versionId)
            put("order_key", c.orderKey)
            put("text", c.text)
            put("done", if (c.done) 1 else 0)
            put("blob_hash", c.blobHash ?: "")
            put("file_name", c.fileName ?: "")
            put("mime", c.mime ?: "")
            if (c.color != null) put("color", c.color) else putNull("color")
            put("child_default", c.childDefault?.name ?: "")
            put("tags", OpListCodec.encode(c.tags))
            put("deleted", if (c.deleted) 1 else 0)
            put("conflicted", if (realConflict) 1 else 0)
            put("created_wall", root.hlc.wallMillis)
            put("created_counter", root.hlc.counter)
            put("updated_wall", shown.hlc.wallMillis)
            put("updated_counter", shown.hlc.counter)
        }
        db.insertWithOnConflict("node_current", null, cv, SQLiteDatabase.CONFLICT_REPLACE)

        db.delete("node_fts", "node_id = ?", arrayOf(nodeId))
        if (!c.deleted) {
            val indexed = (c.text + " " + (c.fileName ?: "") + " " + c.tags.joinToString(" ")).trim()
            db.insert("node_fts", null, ContentValues().apply {
                put("node_id", nodeId)
                put("text", indexed)
            })
        }
    }

    private fun queryNodeStates(where: String, args: Array<String>): List<NodeState> {
        val out = ArrayList<NodeState>()
        db.rawQuery("$NODE_SELECT WHERE $where ORDER BY n.created_wall, n.created_counter", args)
            .use { c -> while (c.moveToNext()) out += readNodeState(c) }
        return out
    }

    private fun readNodeState(c: Cursor): NodeState = NodeState(
        nodeId = c.getString(0),
        parentId = c.getString(1),
        rootId = c.getString(2),
        type = runCatching { NodeType.valueOf(c.getString(3)) }.getOrDefault(NodeType.TEXT),
        headVersionId = c.getString(4),
        orderKey = c.getString(5),
        text = c.getString(6),
        done = c.getInt(7) != 0,
        blobHash = c.getString(8).takeIf { it.isNotEmpty() },
        fileName = c.getString(9).takeIf { it.isNotEmpty() },
        mime = c.getString(10).takeIf { it.isNotEmpty() },
        color = if (c.isNull(11)) null else c.getInt(11),
        childDefault = c.getString(12).takeIf { it.isNotEmpty() }?.let { runCatching { NodeType.valueOf(it) }.getOrNull() },
        tags = OpListCodec.decode(c.getString(13)),
        deleted = c.getInt(14) != 0,
        conflicted = c.getInt(15) != 0,
        created = Hlc(c.getLong(16), c.getInt(17)),
        updated = Hlc(c.getLong(18), c.getInt(19)),
        foreignOrigin = c.getString(20) ?: "",
        foreignRight = FeedRight.from(c.getString(21) ?: ""),
    )

    private fun readOpDto(c: Cursor): OpDto = OpDto(
        versionId = c.getString(IDX_VERSION),
        nodeId = c.getString(IDX_NODE),
        parentId = c.getString(IDX_PARENT),
        rootId = c.getString(IDX_ROOT),
        deviceId = c.getString(IDX_DEVICE),
        seq = c.getLong(IDX_SEQ),
        hlcWall = c.getLong(IDX_HLCW),
        hlcCounter = c.getInt(IDX_HLCC),
        deleted = c.getInt(IDX_DELETED) != 0,
        type = runCatching { NodeType.valueOf(c.getString(IDX_TYPE)) }.getOrDefault(NodeType.TEXT),
        orderKey = c.getString(IDX_ORDER),
        color = if (c.isNull(IDX_COLOR)) null else c.getInt(IDX_COLOR),
        childDefault = c.getString(IDX_CHILDDEF).takeIf { it.isNotEmpty() }?.let { runCatching { NodeType.valueOf(it) }.getOrNull() },
        done = c.getInt(IDX_DONE) != 0,
        blobHash = c.getString(IDX_BLOB).takeIf { it.isNotEmpty() },
        fileName = c.getString(IDX_FILE).takeIf { it.isNotEmpty() },
        mime = c.getString(IDX_MIME).takeIf { it.isNotEmpty() },
        tags = OpListCodec.decode(c.getString(IDX_TAGS)),
        text = c.getString(IDX_TEXT),
        parents = splitCsv(c.getString(IDX_PARENTS)),
        deviceName = c.getString(IDX_DEVNAME) ?: "",
    )

    private fun splitCsv(s: String?): List<String> =
        if (s.isNullOrEmpty()) emptyList() else s.split(',').filter { it.isNotEmpty() }

    private fun ftsQuery(raw: String): String =
        raw.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            .joinToString(" ") { it.replace("\"", "") + "*" }
            .ifBlank { "\"\"" }

    private companion object {
        // ops-Spaltenreihenfolge für missingFor/feedMissingFor + readOpDto.
        const val OP_SELECT =
            "SELECT version_id, node_id, parent_id, root_id, device_id, seq, hlc_wall, hlc_counter, parents, deleted, type, order_key, color, child_default, tags, blob_hash, file_name, mime, done, text, device_name FROM ops"
        const val IDX_VERSION = 0; const val IDX_NODE = 1; const val IDX_PARENT = 2; const val IDX_ROOT = 3
        const val IDX_DEVICE = 4; const val IDX_SEQ = 5; const val IDX_HLCW = 6; const val IDX_HLCC = 7
        const val IDX_PARENTS = 8; const val IDX_DELETED = 9; const val IDX_TYPE = 10; const val IDX_ORDER = 11
        const val IDX_COLOR = 12; const val IDX_CHILDDEF = 13; const val IDX_TAGS = 14; const val IDX_BLOB = 15
        const val IDX_FILE = 16; const val IDX_MIME = 17; const val IDX_DONE = 18; const val IDX_TEXT = 19
        const val IDX_DEVNAME = 20

        // node_current + foreign_refs (LEFT JOIN) für readNodeState.
        const val NODE_SELECT =
            "SELECT n.node_id, n.parent_id, n.root_id, n.type, n.head_version_id, n.order_key, n.text, n.done, " +
                "n.blob_hash, n.file_name, n.mime, n.color, n.child_default, n.tags, n.deleted, n.conflicted, " +
                "n.created_wall, n.created_counter, n.updated_wall, n.updated_counter, f.origin_group, f.foreign_right " +
                "FROM node_current n LEFT JOIN foreign_refs f ON n.node_id = f.node_id"
    }
}

/** String-Liste als "count;b64,..." in einer DB-Zelle (für tags). */
internal object OpListCodec {
    private val enc = java.util.Base64.getEncoder()
    private val dec = java.util.Base64.getDecoder()
    fun encode(list: List<String>): String = "${list.size};" + list.joinToString(",") { enc.encodeToString(it.toByteArray(Charsets.UTF_8)) }
    fun decode(s: String?): List<String> {
        if (s.isNullOrBlank()) return emptyList()
        val i = s.indexOf(';'); if (i < 0) return emptyList()
        val count = s.substring(0, i).toIntOrNull() ?: 0
        if (count == 0) return emptyList()
        return s.substring(i + 1).split(',').map { String(dec.decode(it), Charsets.UTF_8) }
    }
}
