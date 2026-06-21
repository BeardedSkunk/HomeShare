package de.beardedskunk.homeshare.core

/**
 * Aggregiert alle bekannten Versionen EINES Knotens und leitet daraus den aktuellen Zustand ab.
 *
 * - [ingest] fügt eine Versionsknoten hinzu (idempotent, reihenfolge-unabhängig).
 * - [heads] sind die Blatt-Versionen. Genau ein Head -> linear; mehrere -> nebenläufig = Konflikt.
 * - Eine Auflösung ist selbst wieder eine [NodeVersion] mit den Heads als Eltern ([resolveConflict]);
 *   sobald irgendein Gerät sie kennt, ist der Konflikt für alle erledigt.
 *
 * Die Logik ist identisch zur früheren `Post`-Klasse, nur generisch über [NodeContent].
 */
class Node(val nodeId: String) {

    private val versions = LinkedHashMap<String, NodeVersion>()

    /** @return true, wenn die Version neu war. */
    fun ingest(version: NodeVersion): Boolean {
        require(version.nodeId == nodeId) { "Version gehört zu Knoten ${version.nodeId}, nicht $nodeId" }
        if (versions.containsKey(version.versionId)) return false
        versions[version.versionId] = version
        return true
    }

    fun allVersions(): Collection<NodeVersion> = versions.values

    operator fun get(versionId: String): NodeVersion? = versions[versionId]

    /** Blatt-Versionen: von keinem anderen Knoten als Elternteil referenziert. */
    fun heads(): List<NodeVersion> {
        val referenced = HashSet<String>()
        for (v in versions.values) referenced.addAll(v.parents)
        return versions.values
            .filter { it.versionId !in referenced }
            .sortedWith(headOrder)
    }

    fun isConflicted(): Boolean = heads().size > 1

    /** Der aktuelle Stand bei genau einem Head, sonst null (Konflikt oder leer). */
    fun current(): NodeVersion? = heads().singleOrNull()

    /** Anzuzeigender Head bei mehreren: höchste Uhr (siehe [headOrder]). */
    fun shownHead(): NodeVersion? = heads().lastOrNull()

    /**
     * Echter, manuell aufzulösender Konflikt: mehrere Heads mit UNTERSCHIEDLICHEM Inhalt.
     * Mehrere inhaltsgleiche Heads sind KEIN Konflikt (nichts zu entscheiden).
     */
    fun hasContentConflict(): Boolean {
        val h = heads()
        if (h.size <= 1) return false
        val shownContent = h.last().content
        return h.any { it.content != shownContent }
    }

    /** Unvollständige Historie: irgendeine Version verweist auf einen Elternteil, den wir nicht haben. */
    fun hasMissingAncestors(): Boolean =
        versions.values.any { v -> v.parents.any { it !in versions } }

    /** Löst einen Konflikt durch eine Merge-Version mit dem gewählten Inhalt; Eltern = aktuelle Heads. */
    fun resolveConflict(chosen: NodeContent, deviceId: String, hlc: Hlc): NodeVersion {
        val parents = heads().map { it.versionId }.toSet()
        val merge = NodeVersion(nodeId, parents, deviceId, hlc, chosen)
        ingest(merge)
        return merge
    }

    /**
     * Versucht, einen Konflikt **automatisch** zusammenzuführen (3-Wege gegen den gemeinsamen
     * Vorfahren) – wie git/kdiff3 im Hintergrund. Liefert den gemergten Inhalt, wenn sich JEDES Feld
     * sauber auflösen lässt; sonst null (-> bleibt manueller Konflikt). Nur 2-Kopf-Fall; gelöschte
     * Fassungen (Löschen-vs-Edit) bleiben manuell. Reihenfolge-unabhängig, deterministisch.
     */
    fun autoMergeContent(): NodeContent? {
        val h = heads()
        if (h.size != 2) return null
        if (!hasContentConflict() || hasMissingAncestors()) return null
        val (x, y) = h.sortedBy { it.versionId }
        val a = x.content; val b = y.content
        if (a.deleted || b.deleted) return null // Löschen-vs-Edit -> Mensch entscheidet
        val base = lowestCommonAncestor(x.versionId, y.versionId)?.content ?: NodeContent()

        // 3-Wege-Auswahl für ein Strukturfeld: nur eine Seite geändert -> übernehmen; beide gleich -> ok;
        // beide unterschiedlich -> Konflikt (null im second).
        fun <T> pick(ba: T, av: T, bv: T): Pair<Boolean, T> = when {
            av == bv -> true to av
            av == ba -> true to bv
            bv == ba -> true to av
            else -> false to av
        }
        val (okType, type) = pick(base.type, a.type, b.type); if (!okType) return null
        val (okParent, parent) = pick(base.parentId, a.parentId, b.parentId); if (!okParent) return null
        val (okOrder, order) = pick(base.orderKey, a.orderKey, b.orderKey); if (!okOrder) return null
        val (okBlob, blob) = pick(base.blobHash, a.blobHash, b.blobHash); if (!okBlob) return null
        val (okMime, mime) = pick(base.mime, a.mime, b.mime); if (!okMime) return null
        val (okFile, file) = pick(base.fileName, a.fileName, b.fileName); if (!okFile) return null
        val (okColor, color) = pick(base.color, a.color, b.color); if (!okColor) return null
        val (okCd, childDefault) = pick(base.childDefault, a.childDefault, b.childDefault); if (!okCd) return null
        val (okDone, done) = pick(base.done, a.done, b.done); if (!okDone) return null
        val text = ThreeWayMerge.text(base.text, a.text, b.text) ?: return null
        val tags = ThreeWayMerge.list(base.tags, a.tags, b.tags) ?: return null

        return NodeContent(
            parentId = parent, type = type, orderKey = order, text = text, done = done,
            blobHash = blob, fileName = file, mime = mime, color = color,
            childDefault = childDefault, tags = tags, deleted = false,
        )
    }

    /** Alle Vorfahren von [versionId] (ohne den Knoten selbst). */
    fun ancestors(versionId: String): Set<String> {
        val seen = HashSet<String>()
        val stack = ArrayDeque<String>()
        versions[versionId]?.parents?.let { stack.addAll(it) }
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (seen.add(id)) versions[id]?.parents?.let { stack.addAll(it) }
        }
        return seen
    }

    /** Niedrigster gemeinsamer Vorfahr zweier Versionen – Basis für den 3-Wege-Merge/Diff. */
    fun lowestCommonAncestor(a: String, b: String): NodeVersion? {
        val ancA = ancestors(a).toHashSet().apply { add(a) }
        val ancB = ancestors(b).toHashSet().apply { add(b) }
        val common = ancA.intersect(ancB)
        if (common.isEmpty()) return null
        val ancestorsOfCommon = HashSet<String>()
        for (c in common) ancestorsOfCommon.addAll(ancestors(c))
        val lowest = common.filter { it !in ancestorsOfCommon }
        return lowest.mapNotNull { versions[it] }.maxWithOrNull(headOrder)
    }

    companion object {
        /** Deterministische Reihenfolge für Heads/Versionen: Uhr, dann Gerät, dann Id. */
        val headOrder: Comparator<NodeVersion> = compareBy({ it.hlc }, { it.deviceId }, { it.versionId })
    }
}
