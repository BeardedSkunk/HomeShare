package de.beardedskunk.homeshare.core

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

    /** Anzuzeigender Head bei mehreren: hoechste Uhr (siehe [headOrder]). */
    fun shownHead(): PostVersion? = heads().lastOrNull()

    /**
     * Echter, manuell aufzuloesender Konflikt: mehrere Heads mit UNTERSCHIEDLICHEM
     * Inhalt. Mehrere inhaltsgleiche Heads sind KEIN Konflikt – es gibt nichts zu
     * entscheiden, also wird nirgends gefragt. Das tritt auf, wenn
     *  - zwei Geraete offline exakt dieselbe Aenderung gemacht haben (z. B. denselben
     *    Tippfehler korrigiert), oder
     *  - dieselbe Aenderung ueber zwei Sync-Pfade ankam.
     * Es wird bewusst KEINE neue Merge-Version erzeugt (das wuerde je Geraet eine andere
     * Merge-Op erzeugen -> Ping-Pong); die mehreren Heads bleiben bestehen und kollabieren
     * spaeter bei der naechsten echten Bearbeitung (deren Eltern dann alle Heads sind).
     */
    fun hasContentConflict(): Boolean {
        val h = heads()
        if (h.size <= 1) return false
        val shownContent = h.last().content
        return h.any { it.content != shownContent }
    }

    /**
     * Unvollstaendige Historie: irgendeine bekannte Version verweist auf einen Elternteil,
     * den wir (noch) nicht haben. Dann sind mehrere Heads womoeglich nur ein Artefakt der
     * fehlenden Op – das ist KEIN echter, manuell aufzuloesender Konflikt, sondern „synct noch".
     * Sobald die fehlende Op ankommt, loest sich das von selbst.
     */
    fun hasMissingAncestors(): Boolean =
        versions.values.any { v -> v.parents.any { it !in versions } }

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

    /**
     * Versucht, einen Konflikt **automatisch** zusammenzuführen (3-Wege-Merge gegen den
     * gemeinsamen Vorfahren) – wie git/kdiff3 im Hintergrund. Liefert den gemergten Inhalt,
     * wenn sich Text UND Bild-Beschreibungen UND die Bild-Referenzliste sauber (ohne
     * überlappende Konflikte) zusammenführen lassen; sonst `null` -> dann bleibt es ein
     * manuell aufzulösender Konflikt.
     *
     * Bewusst NICHT auto-gemergt: gelöschte Fassungen (Löschen-vs-Edit) und die Bild-Blobs
     * selbst – nur deren Referenzen/Beschreibungen. Nur der 2-Kopf-Fall; bei mehr Köpfen
     * (selten) bleibt es manuell.
     *
     * Reihenfolge-unabhängig und ohne Uhr/Zufall -> jedes Gerät berechnet denselben Inhalt.
     */
    fun autoMergeContent(): PostContent? {
        val h = heads()
        if (h.size != 2) return null
        if (!hasContentConflict() || hasMissingAncestors()) return null
        // Kanonische Reihenfolge (für Stabilität); das Ergebnis ist ohnehin symmetrisch.
        val (x, y) = h.sortedBy { it.versionId }
        val a = x.content; val b = y.content
        if (a.deleted || b.deleted) return null // Löschen-vs-Edit -> Mensch entscheidet
        val base = lowestCommonAncestor(x.versionId, y.versionId)?.content ?: PostContent()

        val text = ThreeWayMerge.text(base.text, a.text, b.text) ?: return null
        val hashes = ThreeWayMerge.list(base.imageHashes, a.imageHashes, b.imageHashes) ?: return null

        // Beschreibungen je Bild (über den Hash zugeordnet) einzeln mergen.
        fun titleMap(c: PostContent): Map<String, String> =
            c.imageHashes.indices.associate { c.imageHashes[it] to (c.imageTitles.getOrNull(it) ?: "") }
        val bt = titleMap(base); val at = titleMap(a); val bbt = titleMap(b)
        val titles = ArrayList<String>(hashes.size)
        for (hash in hashes) {
            val merged = ThreeWayMerge.text(bt[hash] ?: "", at[hash] ?: "", bbt[hash] ?: "") ?: return null
            titles.add(merged)
        }
        return PostContent(text = text, imageHashes = hashes, imageTitles = titles, deleted = false)
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
