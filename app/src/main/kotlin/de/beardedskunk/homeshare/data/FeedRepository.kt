package de.beardedskunk.homeshare.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import de.beardedskunk.homeshare.core.FORMAT_VERSION
import de.beardedskunk.homeshare.core.Hlc
import de.beardedskunk.homeshare.core.MetaCodec
import de.beardedskunk.homeshare.core.MetaKey
import de.beardedskunk.homeshare.core.Node
import de.beardedskunk.homeshare.core.NodeContent
import de.beardedskunk.homeshare.core.NodeKind
import de.beardedskunk.homeshare.core.NodeType
import de.beardedskunk.homeshare.core.NodeVersion
import de.beardedskunk.homeshare.core.OrderKeys
import de.beardedskunk.homeshare.core.Priority
import de.beardedskunk.homeshare.core.DropPlan
import de.beardedskunk.homeshare.core.ROOT
import de.beardedskunk.homeshare.sync.FeedScopedSource
import de.beardedskunk.homeshare.sync.OpDto
import de.beardedskunk.homeshare.sync.OpSource
import de.beardedskunk.homeshare.sync.PeerState
import de.beardedskunk.homeshare.sync.subtreeOpAllowed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.util.UUID

/**
 * Verbindet die persistente DB mit der reinen Konflikt-Engine ([Node]) für einen **Knoten-Baum**.
 * Jede lokale Aktion erzeugt eine [NodeVersion] (Eltern = aktuelle Heads), schreibt sie in den Op-Log
 * und materialisiert den Knoten neu. [ingest] ist derselbe Pfad für beim Sync empfangene Fremd-Ops.
 *
 * Optionale Felder reisen als erweiterbare Meta-Map ([NodeContent.metaMap] / [MetaCodec]) in EINER
 * `meta`-Spalte. Ops mit höherem `fmt` als [FORMAT_VERSION] werden gespeichert+weitergereicht, aber
 * NICHT materialisiert (relay-but-don't-interpret). `root_id` = Feed-Wurzel für den Cross-Group-Sync.
 *
 * (Name bleibt FeedRepository, um Churn klein zu halten; verwaltet aber Knoten.)
 */
class FeedRepository(
    private val db: SQLiteDatabase,
    private val identity: DeviceIdentity,
    val undo: UndoManager = UndoManager(),
) : OpSource, FeedScopedSource {

    var onLocalChange: (() -> Unit)? = null
    var onAnyChange: (() -> Unit)? = null

    init {
        undo.executor = object : UndoExecutor {
            override fun soleHeadId(nodeId: String): String? =
                loadNode(nodeId).heads().singleOrNull()?.versionId

            override fun versionContent(nodeId: String, versionId: String): NodeContent? =
                loadNode(nodeId)[versionId]?.content

            // Bewusst OHNE Marker-Strip und OHNE Gleichheits-Guard: der Undo-Pfad liefert nie
            // No-Ops, und der restore-Marker muss die Op überleben.
            override fun authorRestore(nodeId: String, content: NodeContent): String =
                author(nodeId, currentHeads(nodeId), content).versionId
        }
    }

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
        return parentId
    }

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
        undo.onLocalOp(nodeId, parents, version.versionId)
        onLocalChange?.invoke()
        onAnyChange?.invoke()
        return version
    }

    private fun currentHeads(nodeId: String): Set<String> =
        loadNode(nodeId).heads().map { it.versionId }.toSet()

    fun headContent(nodeId: String): NodeContent? = loadNode(nodeId).shownHead()?.content

    // ----------------------------------------------------------- Öffentliche Knoten-API

    fun createNode(content: NodeContent): NodeState {
        val id = UUID.randomUUID().toString()
        // Neue Knoten ans ENDE ihrer Geschwister einsortieren. Ohne das sortiert der leere orderKey
        // über den HLC-Seed (führende Nullen) lexikografisch VOR bereits umsortierte Geschwister mit
        // echten Schlüsseln – neue Items poppten dann oben auf.
        val withKey = if (content.orderKey.isEmpty()) {
            val last = children(content.parentId).lastOrNull()
            val loKey = last?.let { OrderKeys.effective(it.orderKey, it.created) }
            content.copy(orderKey = OrderKeys.between(loKey, null))
        } else content
        author(id, emptySet(), withKey)
        return getNode(id)!!
    }

    fun editNode(nodeId: String, content: NodeContent): NodeVersion {
        // restore-Marker eines Undo-Heads nicht in normale Folge-Edits durchsickern lassen
        // (Edits basieren auf headContent().copy(...)).
        val stripped = if (UndoMeta.RESTORE in content.ext) {
            content.copy(ext = content.ext - UndoMeta.RESTORE)
        } else {
            content
        }
        val heads = loadNode(nodeId).heads()
        // Kein-Op-Guard: identischer Inhalt bei linearem Zustand -> nichts schreiben
        // (früher erzeugte jeder Save eine neue versionId, weil die HLC in den Hash einfließt).
        // Der Head-Vergleich ignoriert den restore-Marker beidseitig: sonst würde ein
        // UNVERÄNDERTER Save direkt nach einem Undo eine Op erzeugen und das Redo kappen.
        heads.singleOrNull()?.let { head ->
            val headStripped = if (UndoMeta.RESTORE in head.content.ext) {
                head.content.copy(ext = head.content.ext - UndoMeta.RESTORE)
            } else {
                head.content
            }
            if (headStripped == stripped) return head
        }
        return author(nodeId, heads.map { it.versionId }.toSet(), stripped)
    }

    fun deleteNode(nodeId: String): NodeVersion {
        val hc = headContent(nodeId) ?: NodeContent()
        return editNode(nodeId, hc.copy(deleted = true))
    }

    fun moveNode(nodeId: String, newParentId: String, orderKey: String = "") {
        val hc = headContent(nodeId) ?: return
        editNode(nodeId, hc.copy(parentId = newParentId, orderKey = orderKey))
    }

    /**
     * Setzt den orderKey von [nodeId] zwischen die Geschwister [prev] und [next]
     * (null = Anfang/Ende). Genau EIN Op pro Umsortierung; unbeteiligte Geschwister
     * behalten ihre (ggf. virtuellen) Schlüssel – siehe [OrderKeys].
     * Fremdwurzeln werden nur lokal gepinnt (kein Op, Sortierung bleibt beim Owner unberührt).
     */
    fun reorderNode(nodeId: String, prev: NodeState?, next: NodeState?) {
        val lo = prev?.let { OrderKeys.effective(it.orderKey, it.created) }
        var hi = next?.let { OrderKeys.effective(it.orderKey, it.created) }
        if (lo != null && hi != null && lo >= hi) hi = null
        val newKey = OrderKeys.between(lo, hi)
        if (isForeignRoot(nodeId)) {
            db.execSQL("UPDATE foreign_refs SET local_order_key = ? WHERE node_id = ?", arrayOf(newKey, nodeId))
            db.execSQL("UPDATE node_current SET order_key = ? WHERE node_id = ?", arrayOf(newKey, nodeId))
            bumpRevision(); onAnyChange?.invoke()
            return
        }
        val hc = headContent(nodeId) ?: return
        editNode(nodeId, hc.copy(orderKey = newKey))
    }

    // ----------------------------------------------------------- Prioritäten (Priority/PrioritySort)

    /** Hand-Priorität (Band-Level 1..3) setzen; 0 = entfernen. Nur für Aufgaben ohne Due-Date gedacht. */
    fun setPriority(nodeId: String, level: Int) {
        headContent(nodeId)?.let {
            val ext = if (level <= 0) it.ext - Priority.KEY_PRIO else it.ext + (Priority.KEY_PRIO to level.toString())
            editNode(nodeId, it.copy(ext = ext))
        }
    }

    /**
     * Auto-Sort-Flag am Container setzen/entfernen, dazu die einmalige orderKey-Materialisierung
     * ([plan] aus [de.beardedskunk.homeshare.core.rekeyPlan]) — als EIN Undo-Schritt.
     */
    fun setPrioritySort(containerId: String, enabled: Boolean, plan: List<Pair<String, String>> = emptyList()) {
        undo.group {
            headContent(containerId)?.let {
                val ext = if (enabled) it.ext + (Priority.KEY_SORT to "1") else it.ext - Priority.KEY_SORT
                editNode(containerId, it.copy(ext = ext))
            }
            for ((id, key) in plan) headContent(id)?.let { editNode(id, it.copy(orderKey = key)) }
        }
    }

    /** Drop in der auto-sortierten Liste: orderKey (+ ggf. Hand-Prio) in EINER Op. */
    fun applyPriorityDrop(nodeId: String, plan: DropPlan) {
        if (plan.skip) return
        var hi = plan.hi
        if (plan.lo != null && hi != null && plan.lo >= hi) hi = null
        headContent(nodeId)?.let { hc ->
            val ext = when {
                plan.newPrioLevel == null -> hc.ext
                plan.newPrioLevel <= 0 -> hc.ext - Priority.KEY_PRIO
                else -> hc.ext + (Priority.KEY_PRIO to plan.newPrioLevel.toString())
            }
            editNode(nodeId, hc.copy(orderKey = OrderKeys.between(plan.lo, hi), ext = ext))
        }
    }

    fun resolveConflict(nodeId: String, chosen: NodeContent): NodeVersion =
        author(nodeId, currentHeads(nodeId), chosen)

    // ----------------------------------------------------------- Wiederholende Aufgaben (TaskRepeat)

    /** Knoten mit VORGEGEBENER (deterministischer) Id anlegen — nur für Repeat-Kopien.
     *  Ein aus dem Quell-ext geerbter restore-Marker gehört nicht in die Kopie. */
    private fun createNodeAt(nodeId: String, content: NodeContent): NodeVersion {
        val c = if (UndoMeta.RESTORE in content.ext) content.copy(ext = content.ext - UndoMeta.RESTORE) else content
        return author(nodeId, currentHeads(nodeId), c)
    }

    /** Schlüssel direkt HINTER [node] unter seinen Geschwistern (Landeplatz der Repeat-Kopie). */
    private fun orderKeyAfter(node: NodeState): String {
        val sibs = children(node.parentId)
        val i = sibs.indexOfFirst { it.nodeId == node.nodeId }
        val lo = OrderKeys.effective(node.orderKey, node.created)
        var hi = if (i >= 0) sibs.getOrNull(i + 1)?.let { OrderKeys.effective(it.orderKey, it.created) } else null
        if (hi != null && lo >= hi) hi = null
        return OrderKeys.between(lo, hi)
    }

    /** Führt einen Klon-Plan aus: erst der Spawn-Marker am Original, dann die Kopie-Knoten.
     *  Als EINE Undo-Gruppe: ein Undo entfernt die Kopie UND stellt das Original zurück. */
    private fun executeSpawn(taskId: String, newContent: NodeContent, plan: TaskRepeat.ClonePlan): NodeVersion =
        undo.group {
            val version = editNode(taskId, newContent.copy(ext = newContent.ext + (TaskRepeat.KEY_SPAWNED to plan.rootId)))
            for ((id, content) in plan.nodes) createNodeAt(id, content)
            version
        }

    /**
     * Haken einer Aufgabe setzen/entfernen — zentrale Stelle, damit der Repeater greift.
     * Abhaken bei Regel mit Trigger „Erledigung": im selben Op wird [TaskRepeat.KEY_SPAWNED]
     * gesetzt (Sperre) und direkt danach die Kopie erzeugt. Enthaken nimmt eine noch frische
     * Kopie (< [TaskRepeat.UNSPAWN_WINDOW_MILLIS]) samt Marker wieder zurück; eine ältere bleibt
     * stehen (und erneutes Abhaken spawnt wegen des Markers NICHT noch einmal).
     * Spawns passieren nur hier beim lokalen Autorisieren — nie im [ingest]-Pfad, damit
     * empfangende Geräte keine Duplikate erzeugen.
     */
    fun setTaskDone(nodeId: String, done: Boolean): NodeVersion? {
        val hc = headContent(nodeId) ?: return null
        if (hc.done == done) return null
        if (!done) {
            val spawned = hc.ext[TaskRepeat.KEY_SPAWNED]?.let { getNode(it) }
            val fresh = spawned != null && !spawned.deleted &&
                System.currentTimeMillis() - spawned.created.wallMillis < TaskRepeat.UNSPAWN_WINDOW_MILLIS
            if (!fresh) return editNode(nodeId, hc.copy(done = false))
            return undo.group { // Rücknahme = Kopie löschen + Marker entfernen: EIN Undo-Schritt
                deleteNode(spawned!!.nodeId)
                editNode(nodeId, hc.copy(done = false, ext = hc.ext - TaskRepeat.KEY_SPAWNED))
            }
        }
        val wantsSpawn = TaskRepeat.rule(hc.ext) != null &&
            TaskRepeat.mode(hc.ext) == TaskRepeat.MODE_DONE &&
            hc.ext[TaskRepeat.KEY_SPAWNED] == null
        val source = if (wantsSpawn) getNode(nodeId) else null
        val plan = source?.let {
            // Vorkommens-Schlüssel = Head vor dem Abhaken: eindeutig pro Abhak-Zyklus.
            TaskRepeat.plan(it, ::children, it.headVersionId, LocalDate.now(), orderKeyAfter(it))
        } ?: return editNode(nodeId, hc.copy(done = true)) // keine/erschöpfte Regel -> nur abhaken
        return executeSpawn(nodeId, hc.copy(done = true), plan)
    }

    /**
     * Fälligkeits-Sweep (Trigger „Fälligkeit"): erzeugt für jede Aufgabe mit Regel, verstrichenem
     * Due Date (erster CALENDAR-Kindknoten, Tages-Granularität) und ohne Spawn-Marker die Kopie
     * mit dem nächsten Vorkommen NACH heute (Catch-up = genau eine Kopie). Aufruf beim
     * App-Start/-Resume; kein Alarm nötig — die Kopie muss erst sichtbar sein, wenn die App läuft.
     * @return Anzahl erzeugter Kopien.
     */
    fun rollOverdueRepeats(today: LocalDate = LocalDate.now()): Int {
        val candidates = queryNodeStates(
            "n.type = ? AND n.deleted = 0 AND n.meta LIKE ?",
            arrayOf(NodeType.TODO.name, "%${TaskRepeat.KEY_RULE}%"),
        )
        var spawned = 0
        for (task in candidates) {
            if (TaskRepeat.rule(task.ext) == null) continue
            if (TaskRepeat.mode(task.ext) != TaskRepeat.MODE_DUE) continue
            if (task.ext[TaskRepeat.KEY_SPAWNED] != null) continue
            if (task.conflicted) continue // erst auflösen, sonst spawnen wir von der falschen Seite
            val dueDay = TaskRepeat.dueChild(children(task.nodeId))?.let { TaskRepeat.dueDate(it) } ?: continue
            if (!TaskRepeat.isOverdue(dueDay, today)) continue
            // Vorkommens-Schlüssel = altes Due-Datum: geräteübergreifend dieselbe Kopie-Id.
            val plan = TaskRepeat.plan(task, ::children, dueDay.toString(), today, orderKeyAfter(task)) ?: continue
            val hc = headContent(task.nodeId) ?: continue
            executeSpawn(task.nodeId, hc, plan)
            spawned++
        }
        return spawned
    }

    // ----------------------------------------------------------- Listen (= navigierbare TEXT-Knoten)

    /** Neue Liste unter [parentId] (ROOT = oberste Ebene) mit Default-Kindtyp [childDefault]. */
    fun createList(name: String, parentId: String = ROOT, childDefault: NodeKind = NodeKind.LIST): NodeState =
        createNode(NodeContent(parentId = parentId, type = NodeType.TEXT, text = name.trim(), childDefault = childDefault))

    /** Bequemer Alt-Einstieg: Liste mit Default Notiz bzw. Kalender. */
    fun createFeed(name: String, calendar: Boolean = false): NodeState =
        createList(name, ROOT, if (calendar) NodeKind.CALENDAR else NodeKind.NOTE)

    fun renameFeed(feedId: String, name: String) {
        val hc = headContent(feedId) ?: return
        val newText = (listOf(name.trim()) + hc.text.lineSequence().drop(1).toList()).joinToString("\n")
        editNode(feedId, hc.copy(text = newText))
    }

    fun deleteFeed(feedId: String) { deleteNode(feedId) }

    /** Wurzelknoten (Feeds), inkl. abonnierte Fremd-Wurzeln. */
    fun listFeeds(): List<NodeState> =
        queryNodeStates("n.parent_id = ? AND n.deleted = 0", arrayOf(ROOT)).sortedWith(siblingOrder)

    fun children(parentId: String): List<NodeState> =
        queryNodeStates("n.parent_id = ? AND (n.deleted = 0 OR n.conflicted = 1)", arrayOf(parentId)).sortedWith(siblingOrder)

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

    /**
     * Alle existierenden Tags (lebende Knoten), case-insensitiv dedupliziert
     * (erste Schreibweise gewinnt), alphabetisch sortiert.
     */
    fun allTags(): List<String> {
        val seen = LinkedHashMap<String, String>() // lowercase → Anzeigeschreibweise
        db.rawQuery("SELECT meta FROM node_current WHERE deleted = 0 AND meta != ''", null)
            .use { c ->
                while (c.moveToNext()) {
                    val raw = c.getString(0)
                    val tags = MetaCodec.decode(raw)[MetaKey.TAGS]
                        ?.let { de.beardedskunk.homeshare.core.MetaListCodec.decode(it) }
                        ?: continue
                    for (t in tags) {
                        val key = t.lowercase()
                        if (!seen.containsKey(key)) seen[key] = t
                    }
                }
            }
        return seen.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
    }

    /**
     * Treffer + Breadcrumb in einem Rutsch: alle Knoten (alle Feeds, beliebige Tiefe),
     * die ALLE [tags] tragen (case-insensitiv, UND-verknüpft), ohne solche in gelöschten
     * Teilbäumen.
     */
    fun tagSearch(tags: List<String>): List<TagHit> {
        if (tags.isEmpty()) return emptyList()
        // Alle Knoten (inkl. gelöschter) für den Vorfahren-Check in O(1) pro Knoten.
        val all: Map<String, NodeState> = buildMap {
            db.rawQuery("$NODE_SELECT", emptyArray())
                .use { c -> while (c.moveToNext()) { val n = readNodeState(c); put(n.nodeId, n) } }
        }
        val hits = ArrayList<TagHit>()
        for (node in all.values) {
            if (node.deleted) continue
            if (!tags.all { t -> node.tags.any { it.equals(t, ignoreCase = true) } }) continue
            // Vorfahren-Kette prüfen: liegt der Knoten unter einem gelöschten Elternteil?
            val path = ArrayList<String>()
            var cur = all[node.parentId]
            var inDeletedSubtree = false
            while (cur != null) {
                if (cur.deleted) { inDeletedSubtree = true; break }
                path.add(0, cur.title)
                cur = all[cur.parentId]
            }
            if (inDeletedSubtree) continue
            // Breadcrumb: bis zu 3 nächste Elterntitel, wurzelnah zuerst.
            val more = path.size > 3
            val parentTitles = if (more) path.takeLast(3) else path
            hits += TagHit(node, parentTitles, more)
        }
        return hits
    }

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
        // Fremde Op: betroffene Undo-Einträge sind stale -> raus (fremde Ops nie in die Kette).
        undo.invalidate(version.nodeId)
        onAnyChange?.invoke()
        maybeAutoResolve(version.nodeId)
        return true
    }

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
        // node_current: aktuelle Blobs (Bild/Datei) der angezeigten Knoten.
        db.rawQuery("SELECT meta FROM node_current WHERE deleted = 0", null).use { c ->
            while (c.moveToNext()) MetaCodec.decode(c.getString(0))[MetaKey.BLOB]?.let { out += it }
        }
        // Konflikt-Köpfe: auch deren (evtl. abweichende) Blobs vorhalten.
        db.rawQuery(
            "SELECT meta FROM ops WHERE node_id IN (SELECT node_id FROM node_current WHERE conflicted = 1)",
            null,
        ).use { c -> while (c.moveToNext()) MetaCodec.decode(c.getString(0))[MetaKey.BLOB]?.let { out += it } }
        return out
    }

    // ------------------------------ Cross-Group-Sync (#10) ------------------------------

    /** Alle Knoten-Ids im Teilbaum unter [nodeId] (inkl. nodeId, inkl. gelöschter). */
    private fun subtreeIds(nodeId: String): Set<String> {
        val out = HashSet<String>()
        db.rawQuery(
            """WITH RECURSIVE sub(id) AS (
               SELECT ? UNION ALL
               SELECT n.node_id FROM node_current n JOIN sub ON n.parent_id = sub.id)
               SELECT id FROM sub""",
            arrayOf(nodeId),
        ).use { c -> while (c.moveToNext()) out += c.getString(0) }
        return out
    }

    private fun rootIdOf(nodeId: String): String =
        db.rawQuery("SELECT root_id FROM node_current WHERE node_id = ?", arrayOf(nodeId))
            .use { if (it.moveToFirst()) it.getString(0) else nodeId }

    private fun isSharedSubtreeFeed(feedId: String): Boolean = rootIdOf(feedId) != feedId


    override fun feedVersionVector(rootId: String): Map<String, PeerState> {
        val seqs = HashMap<String, MutableList<Long>>()
        if (!isSharedSubtreeFeed(rootId)) {
            db.rawQuery("SELECT device_id, seq FROM ops WHERE root_id = ?", arrayOf(rootId)).use { c ->
                while (c.moveToNext()) seqs.getOrPut(c.getString(0)) { ArrayList() }.add(c.getLong(1))
            }
        } else {
            val ids = subtreeIds(rootId)
            val placeholders = ids.joinToString(",") { "?" }
            db.rawQuery("SELECT device_id, seq FROM ops WHERE node_id IN ($placeholders)", ids.toTypedArray()).use { c ->
                while (c.moveToNext()) seqs.getOrPut(c.getString(0)) { ArrayList() }.add(c.getLong(1))
            }
        }
        return seqStates(seqs)
    }

    override fun feedMissingFor(rootId: String, remote: Map<String, PeerState>): List<OpDto> {
        val remoteGaps = remote.mapValues { it.value.gaps.toHashSet() }
        val out = ArrayList<OpDto>()
        if (!isSharedSubtreeFeed(rootId)) {
            db.rawQuery("$OP_SELECT WHERE root_id = ? ORDER BY device_id, seq", arrayOf(rootId)).use { c ->
                while (c.moveToNext()) {
                    val device = c.getString(IDX_DEVICE)
                    val seq = c.getLong(IDX_SEQ)
                    val st = remote[device]
                    if (st != null && seq <= st.maxSeq && seq !in (remoteGaps[device] ?: emptySet())) continue
                    out += readOpDto(c)
                }
            }
        } else {
            val ids = subtreeIds(rootId)
            val placeholders = ids.joinToString(",") { "?" }
            db.rawQuery("$OP_SELECT WHERE node_id IN ($placeholders) ORDER BY device_id, seq", ids.toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    val device = c.getString(IDX_DEVICE)
                    val seq = c.getLong(IDX_SEQ)
                    val st = remote[device]
                    if (st != null && seq <= st.maxSeq && seq !in (remoteGaps[device] ?: emptySet())) continue
                    // rootId auf dem Draht auf feedId umlabeln (rootId steckt nicht im Hash → kein Integritätsproblem).
                    out += readOpDto(c).copy(rootId = rootId)
                }
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
        val subtree = subtreeIds(rootId)
        if (!subtreeOpAllowed(op, rootId, subtree, right)) return false
        return ingest(op.toVersion(), rootIdOf(rootId), op.seq, op.deviceName)
    }

    // ------------------------------ Freigaben (Original-Gruppe) ------------------------------

    private fun feedText(rootId: String): String = headContent(rootId)?.text ?: ""

    fun feedShares(rootId: String): List<ShareGrant> =
        headContent(rootId)?.let { FeedShareCodec.grantsOf(it.text, it.ext) } ?: emptyList()

    fun setFeedShares(rootId: String, grants: List<ShareGrant>) {
        val hc = headContent(rootId) ?: return
        val newExt = if (grants.isEmpty()) hc.ext - FeedShareCodec.META_KEY
                     else hc.ext + (FeedShareCodec.META_KEY to FeedShareCodec.encodeMeta(grants))
        editNode(rootId, hc.copy(text = FeedShareCodec.stripShareLines(hc.text), ext = newExt))
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

    fun registerForeignFeed(ref: ForeignFeedRef, name: String, calendar: Boolean, parentId: String = ROOT) {
        // Einfüge-Schlüssel ans Ende von parentId berechnen (analog zu createNode).
        val last = children(parentId).lastOrNull()
        val loKey = last?.let { OrderKeys.effective(it.orderKey, it.created) }
        val endKey = OrderKeys.between(loKey, null)

        db.insertWithOnConflict(
            "foreign_refs", null,
            ContentValues().apply {
                put("node_id", ref.nodeId)
                put("origin_group", ref.originGroup)
                put("cap_id", ref.capId)
                put("cap_secret", ref.capSecret)
                put("foreign_right", ref.right.name)
                put("local_parent_id", parentId)
                put("local_order_key", endKey)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        if (getNode(ref.nodeId) == null) {
            val h = identity.nextHlc()
            val meta = MetaCodec.encode(mapOf(MetaKey.CHILD_DEFAULT to (if (calendar) NodeKind.CALENDAR else NodeKind.NOTE).name))
            db.insertWithOnConflict(
                "node_current", null,
                ContentValues().apply {
                    put("node_id", ref.nodeId); put("parent_id", parentId); put("root_id", ref.nodeId)
                    put("type", NodeType.TEXT.name); put("head_version_id", ""); put("order_key", endKey)
                    put("text", name); put("meta", meta); put("fmt", FORMAT_VERSION)
                    put("deleted", 0); put("conflicted", 0)
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
            put("text", c.text)
            put("meta", MetaCodec.encode(c.metaMap()))
            put("fmt", v.formatVersion)
            put("device_name", deviceName)
        }
        db.insertWithOnConflict("ops", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    /** Lädt nur INTERPRETIERBARE Versionen (fmt <= [FORMAT_VERSION]); neuere bleiben gespeichert/relayt. */
    private fun loadNode(nodeId: String): Node {
        val node = Node(nodeId)
        db.rawQuery(
            "SELECT node_id, device_id, hlc_wall, hlc_counter, parents, deleted, type, parent_id, order_key, text, meta, fmt " +
                "FROM ops WHERE node_id = ? AND fmt <= ?",
            arrayOf(nodeId, FORMAT_VERSION.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val parents = splitCsv(c.getString(4)).toSet()
                val content = NodeContent.fromMeta(
                    parentId = c.getString(7),
                    type = runCatching { NodeType.valueOf(c.getString(6)) }.getOrDefault(NodeType.TEXT),
                    orderKey = c.getString(8),
                    text = c.getString(9),
                    deleted = c.getInt(5) != 0,
                    meta = MetaCodec.decode(c.getString(10)),
                )
                node.ingest(NodeVersion(c.getString(0), parents, c.getString(1), Hlc(c.getLong(2), c.getInt(3)), content, c.getInt(11)))
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
        // Für abonnierte Fremdwurzeln: parent_id und order_key aus dem lokalen Pin übernehmen.
        val pin = db.rawQuery(
            "SELECT local_parent_id, local_order_key FROM foreign_refs WHERE node_id = ?", arrayOf(nodeId),
        ).use { if (it.moveToFirst()) (it.getString(0) to it.getString(1)) else null }
        val effectiveParent = pin?.first?.ifEmpty { ROOT } ?: c.parentId
        val effectiveOrderKey = pin?.second?.ifEmpty { c.orderKey } ?: c.orderKey
        val effectiveRootId = if (pin != null) nodeId else rootId
        val cv = ContentValues().apply {
            put("node_id", nodeId)
            put("parent_id", effectiveParent)
            put("root_id", effectiveRootId)
            put("type", c.type.name)
            put("head_version_id", shown.versionId)
            put("order_key", effectiveOrderKey)
            put("text", c.text)
            put("meta", MetaCodec.encode(c.metaMap()))
            put("fmt", shown.formatVersion)
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

    private fun readNodeState(c: Cursor): NodeState {
        val meta = MetaCodec.decode(c.getString(IDX_N_META))
        return NodeState(
            nodeId = c.getString(IDX_N_NODE),
            parentId = c.getString(IDX_N_PARENT),
            rootId = c.getString(IDX_N_ROOT),
            type = runCatching { NodeType.valueOf(c.getString(IDX_N_TYPE)) }.getOrDefault(NodeType.TEXT),
            headVersionId = c.getString(IDX_N_HEAD),
            orderKey = c.getString(IDX_N_ORDER),
            text = c.getString(IDX_N_TEXT),
            done = meta[MetaKey.DONE] == "1",
            blobHash = meta[MetaKey.BLOB],
            fileName = meta[MetaKey.FILE],
            mime = meta[MetaKey.MIME],
            color = meta[MetaKey.COLOR]?.toIntOrNull(),
            childDefault = meta[MetaKey.CHILD_DEFAULT]?.let { runCatching { NodeKind.valueOf(it) }.getOrNull() },
            tags = meta[MetaKey.TAGS]?.let { de.beardedskunk.homeshare.core.MetaListCodec.decode(it) } ?: emptyList(),
            deleted = c.getInt(IDX_N_DELETED) != 0,
            conflicted = c.getInt(IDX_N_CONFLICTED) != 0,
            created = Hlc(c.getLong(IDX_N_CWALL), c.getInt(IDX_N_CCNT)),
            updated = Hlc(c.getLong(IDX_N_UWALL), c.getInt(IDX_N_UCNT)),
            foreignOrigin = c.getString(IDX_N_ORIGIN) ?: "",
            foreignRight = FeedRight.from(c.getString(IDX_N_FRIGHT) ?: ""),
            ext = meta.filterKeys { it !in MetaKey.KNOWN },
        )
    }

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
        text = c.getString(IDX_TEXT),
        meta = MetaCodec.decode(c.getString(IDX_META)),
        formatVersion = c.getInt(IDX_FMT),
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
        const val OP_SELECT =
            "SELECT version_id, node_id, parent_id, root_id, device_id, seq, hlc_wall, hlc_counter, parents, deleted, type, order_key, text, meta, fmt, device_name FROM ops"
        const val IDX_VERSION = 0; const val IDX_NODE = 1; const val IDX_PARENT = 2; const val IDX_ROOT = 3
        const val IDX_DEVICE = 4; const val IDX_SEQ = 5; const val IDX_HLCW = 6; const val IDX_HLCC = 7
        const val IDX_PARENTS = 8; const val IDX_DELETED = 9; const val IDX_TYPE = 10; const val IDX_ORDER = 11
        const val IDX_TEXT = 12; const val IDX_META = 13; const val IDX_FMT = 14; const val IDX_DEVNAME = 15

        const val NODE_SELECT =
            "SELECT n.node_id, n.parent_id, n.root_id, n.type, n.head_version_id, n.order_key, n.text, n.meta, " +
                "n.deleted, n.conflicted, n.created_wall, n.created_counter, n.updated_wall, n.updated_counter, " +
                "f.origin_group, f.foreign_right " +
                "FROM node_current n LEFT JOIN foreign_refs f ON n.node_id = f.node_id"
        const val IDX_N_NODE = 0; const val IDX_N_PARENT = 1; const val IDX_N_ROOT = 2; const val IDX_N_TYPE = 3
        const val IDX_N_HEAD = 4; const val IDX_N_ORDER = 5; const val IDX_N_TEXT = 6; const val IDX_N_META = 7
        const val IDX_N_DELETED = 8; const val IDX_N_CONFLICTED = 9; const val IDX_N_CWALL = 10; const val IDX_N_CCNT = 11
        const val IDX_N_UWALL = 12; const val IDX_N_UCNT = 13; const val IDX_N_ORIGIN = 14; const val IDX_N_FRIGHT = 15
    }
}
