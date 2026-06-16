package de.beardedskunk.clipsharing.core

/**
 * Aggregiert alle bekannten Versionen eines Posts und leitet daraus den
 * aktuellen Zustand ab.
 *
 * - [ingest] fuegt einen Versionsknoten hinzu (idempotent, reihenfolge-unabhaengig).
 * - [heads] sind die Blatt-Versionen (von keiner anderen als Elternteil referenziert).
 * - Genau ein Head  -> linearer Verlauf, alle Geraete uebernehmen blind.
 * - Mehrere Heads   -> nebenlaeufige Bearbeitung = Konflikt, manuelle Aufloesung noetig.
 *
 * Eine Aufloesung ist selbst wieder eine [PostVersion], deren Eltern die
 * konkurrierenden Heads sind ([resolveConflict]). Sobald irgendein Geraet diese
 * Merge-Version kennt, ist der Konflikt fuer alle erledigt -- es entsteht wieder
 * ein einziger Head, also wird nirgends erneut gefragt.
 */
class Post(val postId: String) {

    private val versions = LinkedHashMap<String, PostVersion>()

    /** @return true, wenn die Version neu war. */
    fun ingest(version: PostVersion): Boolean {
        require(version.postId == postId) { "Version gehoert zu Post ${version.postId}, nicht $postId" }
        if (versions.containsKey(version.versionId)) return false
        versions[version.versionId] = version
        return true
    }

    fun allVersions(): Collection<PostVersion> = versions.values

    operator fun get(versionId: String): PostVersion? = versions[versionId]

    /** Blatt-Versionen: von keinem anderen Knoten als Elternteil referenziert. */
    fun heads(): List<PostVersion> {
        val referenced = HashSet<String>()
        for (v in versions.values) referenced.addAll(v.parents)
        return versions.values
            .filter { it.versionId !in referenced }
            .sortedWith(headOrder)
    }

    fun isConflicted(): Boolean = heads().size > 1

    /** Der aktuelle Stand bei genau einem Head, sonst null (Konflikt oder leer). */
    fun current(): PostVersion? = heads().singleOrNull()

    /**
     * Loest einen Konflikt auf, indem eine Merge-Version mit dem gewaehlten
     * Inhalt erzeugt und eingespeist wird; ihre Eltern sind die aktuellen Heads.
     */
    fun resolveConflict(
        chosen: PostContent,
        deviceId: String,
        hlc: Hlc,
    ): PostVersion {
        val parents = heads().map { it.versionId }.toSet()
        val merge = PostVersion(postId, parents, deviceId, hlc, chosen)
        ingest(merge)
        return merge
    }

    /** Alle Vorfahren von [versionId] (ohne den Knoten selbst). */
    fun ancestors(versionId: String): Set<String> {
        val seen = HashSet<String>()
        val stack = ArrayDeque<String>()
        versions[versionId]?.parents?.let { stack.addAll(it) }
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (seen.add(id)) {
                versions[id]?.parents?.let { stack.addAll(it) }
            }
        }
        return seen
    }

    /**
     * Niedrigster gemeinsamer Vorfahr zweier Versionen -- die Basis fuer den Diff
     * in der Konflikt-Anzeige. Bei mehreren gleichrangigen wird der mit der
     * hoechsten Uhr gewaehlt.
     */
    fun lowestCommonAncestor(a: String, b: String): PostVersion? {
        val ancA = ancestors(a).toHashSet().apply { add(a) }
        val ancB = ancestors(b).toHashSet().apply { add(b) }
        val common = ancA.intersect(ancB)
        if (common.isEmpty()) return null
        // "Niedrigster": ein gemeinsamer Vorfahr, der nicht selbst Vorfahr eines
        // anderen gemeinsamen Vorfahren ist.
        val ancestorsOfCommon = HashSet<String>()
        for (c in common) ancestorsOfCommon.addAll(ancestors(c))
        val lowest = common.filter { it !in ancestorsOfCommon }
        return lowest.mapNotNull { versions[it] }.maxWithOrNull(headOrder)
    }

    companion object {
        /** Deterministische Reihenfolge fuer Heads/Versionen: Uhr, dann Geraet, dann Id. */
        val headOrder: Comparator<PostVersion> = compareBy({ it.hlc }, { it.deviceId }, { it.versionId })
    }
}
