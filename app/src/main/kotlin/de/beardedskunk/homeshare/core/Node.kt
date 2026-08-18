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

        // 3-Wege-Auswahl für ein Feld: nur eine Seite geändert -> übernehmen; beide gleich -> ok;
        // beide unterschiedlich -> Konflikt (false).
        fun <T> pick(ba: T, av: T, bv: T): Pair<Boolean, T> = when {
            av == bv -> true to av
            av == ba -> true to bv
            bv == ba -> true to av
            else -> false to av
        }
        val (okType, type) = pick(base.type, a.type, b.type); if (!okType) return null
        val (okParent, parent) = pick(base.parentId, a.parentId, b.parentId); if (!okParent) return null
        // Reine Umsortierung soll den Nutzer nie mit einem Konflikt behelligen: haben BEIDE
        // Seiten den orderKey (unterschiedlich) geändert, gewinnt deterministisch der spätere
        // Editor (Last-Writer-Wins über headOrder) statt manueller Auflösung.
        val (okOrder, orderPick) = pick(base.orderKey, a.orderKey, b.orderKey)
        val order = if (okOrder) orderPick else (if (headOrder.compare(x, y) >= 0) x else y).content.orderKey
        val text = ThreeWayMerge.text(base.text, a.text, b.text) ?: return null

        // Offene Meta-Map generisch pro Key 3-Wege mergen (bekannte UND zukünftige Keys).
        val bm = base.metaMap(); val am = a.metaMap(); val bbm = b.metaMap()
        val merged = sortedMapOf<String, String>()
        for (k in (am.keys + bbm.keys)) {
            val (ok, v) = pick(bm[k], am[k], bbm[k])
            if (ok) {
                if (v != null) merged[k] = v
            } else if (k == MetaKey.TAGS) {
                // Tags sind eine Menge → Vereinigung, parallel Hinzugefügtes bleibt beides erhalten.
                val t = Tags.mergeSets(
                    bm[k]?.let { MetaListCodec.decode(it) } ?: emptyList(),
                    am[k]?.let { MetaListCodec.decode(it) } ?: emptyList(),
                    bbm[k]?.let { MetaListCodec.decode(it) } ?: emptyList(),
                )
                if (t.isNotEmpty()) merged[k] = MetaListCodec.encode(t)
            } else if (k == MetaKey.REPEAT_SPAWNED) {
                // Spawn-Sperre: beide Seiten haben (unterschiedlich) gespawnt bzw. eine hat
                // den Marker entfernt -> Last-Writer-Wins wie beim orderKey; die Kette läuft
                // ohnehin über die Kopie weiter, ein manueller Konflikt wäre hier nur lästig.
                val w = (if (headOrder.compare(x, y) >= 0) am else bbm)[k]
                if (w != null) merged[k] = w
            } else {
                return null // unauflösbarer Meta-Konflikt -> Mensch entscheidet
            }
        }
        return NodeContent.fromMeta(parent, type, order, text, false, merged)
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
