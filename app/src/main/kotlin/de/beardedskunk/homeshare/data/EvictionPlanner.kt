package de.beardedskunk.homeshare.data

/** Metadaten eines lokal vorgehaltenen Voll-Bildes fuer die Eviction-Entscheidung. */
data class BlobInfo(
    val sha: String,
    val bytes: Long,
    /** Sortierschluessel "Alter" (z. B. Erstell-Uhr des Posts); kleiner = aelter. */
    val ageKey: Long,
    /** Bekannt, dass das Bild anderswo (Peer/FRITZ!Box) gesichert ist? */
    val backedUpElsewhere: Boolean,
)

/**
 * [toEvict] = lokal zu entfernende Voll-Bilder (Thumbnails bleiben).
 * [unconfirmed] ⊆ [toEvict]: davon ist nicht bekannt, ob sie anderswo gesichert sind
 * -> die UI zeigt eine Warnung und laesst den Nutzer trotzdem fortfahren.
 */
data class EvictionPlan(val toEvict: List<String>, val unconfirmed: List<String>)

/**
 * Reine Eviction-Strategie: entfernt die aeltesten Voll-Bilder, bis das lokale
 * Speicherbudget eingehalten ist. Bewusst frei von Android-/IO-Abhaengigkeiten,
 * damit per Unit-Test verifizierbar.
 */
object EvictionPlanner {
    fun plan(blobs: List<BlobInfo>, maxBytes: Long): EvictionPlan {
        val total = blobs.sumOf { it.bytes }
        if (total <= maxBytes) return EvictionPlan(emptyList(), emptyList())

        var over = total - maxBytes
        val toEvict = ArrayList<String>()
        val unconfirmed = ArrayList<String>()
        for (b in blobs.sortedBy { it.ageKey }) { // aelteste zuerst
            if (over <= 0) break
            toEvict += b.sha
            if (!b.backedUpElsewhere) unconfirmed += b.sha
            over -= b.bytes
        }
        return EvictionPlan(toEvict, unconfirmed)
    }
}
