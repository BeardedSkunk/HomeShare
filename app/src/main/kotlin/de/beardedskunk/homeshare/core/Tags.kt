package de.beardedskunk.homeshare.core

/** Reine Tag-Logik: Normalisierung, case-insensitives Matching, Union-Merge. */
object Tags {

    /** Normalisiert Nutzereingabe: trimmen; leer/blank → null. Schreibweise bleibt erhalten. */
    fun normalize(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() }

    /** Case-insensitiver Contains-Test. */
    fun contains(tags: List<String>, tag: String): Boolean =
        tags.any { it.equals(tag, ignoreCase = true) }

    /**
     * Fügt [raw] normalisiert hinzu. Existiert im Vokabular [vocab] (alle Tags der App) bereits
     * eine case-insensitiv gleiche Schreibweise, wird DIESE übernommen (Anzeige = Schreibweise
     * der Erst-Anlage). Ist das Tag am Knoten bereits vorhanden oder die Eingabe leer →
     * unveränderte Liste.
     */
    fun add(tags: List<String>, raw: String, vocab: List<String>): List<String> {
        val normalized = normalize(raw) ?: return tags
        if (contains(tags, normalized)) return tags
        val canonical = vocab.firstOrNull { it.equals(normalized, ignoreCase = true) } ?: normalized
        return tags + canonical
    }

    /** Entfernt [tag] case-insensitiv. */
    fun remove(tags: List<String>, tag: String): List<String> =
        tags.filter { !it.equals(tag, ignoreCase = true) }

    /**
     * 3-Wege-Union für den Tag-Merge: Ergebnis = (a ∪ b) minus allem, was gegenüber [base] auf
     * EINER Seite entfernt wurde. Reihenfolge: erst a in seiner Reihenfolge, dann neue aus b —
     * deterministisch, weil der Aufrufer (autoMergeContent) die Seiten bereits nach versionId
     * sortiert. Duplikate (exakter String) werden vermieden.
     */
    fun mergeSets(base: List<String>, a: List<String>, b: List<String>): List<String> {
        val removedA = base.filter { !a.contains(it) }
        val removedB = base.filter { !b.contains(it) }
        return (a + b.filter { it !in a })
            .filter { it !in removedA && it !in removedB }
    }
}
