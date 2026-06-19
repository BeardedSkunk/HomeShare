package de.beardedskunk.homeshare.core

/**
 * Deterministischer 3-Wege-Merge (diff3-artig), wie hinter git/kdiff3 – aber rein als
 * **Hintergrund-Mechanik**: er liefert entweder ein sauberes Merge-Ergebnis oder `null`
 * (= es bleiben echte, überlappende Konflikte, die ein Mensch entscheiden muss). Eine
 * Merge-UI gibt es bewusst nicht.
 *
 * Zeilenbasiert (wie git): nicht-überlappende Änderungen auf verschiedenen Zeilen werden
 * automatisch zusammengeführt; nur wenn DIESELBE Stelle unterschiedlich geändert wurde,
 * gilt es als Konflikt.
 *
 * **Determinismus ist hier kritisch**: mehrere Geräte können denselben Konflikt unabhängig
 * mergen. Der Algorithmus benutzt keine Zeitstempel/Locale/Zufall und ist
 * reihenfolge-unabhängig (merge(o,a,b) == merge(o,b,a)), damit alle Geräte dasselbe
 * Ergebnis berechnen und der DAG konvergiert.
 */
object ThreeWayMerge {

    /** @return sauber gemergter Text, oder null bei echtem (überlappendem) Konflikt. */
    fun text(base: String, a: String, b: String): String? {
        if (a == b) return a          // identische Änderung / kein Unterschied
        if (a == base) return b        // nur B hat geändert
        if (b == base) return a        // nur A hat geändert
        val merged = merge(base.lines(), a.lines(), b.lines()) ?: return null
        return merged.joinToString("\n")
    }

    /** Wie [text], aber für Listen (z. B. Bild-Hash-Referenzen). */
    fun list(base: List<String>, a: List<String>, b: List<String>): List<String>? {
        if (a == b) return a
        if (a == base) return b
        if (b == base) return a
        return merge(base, a, b)
    }

    /**
     * Kern: synchronisiert über Zeilen, die in ALLEN dreien vorkommen (Schnittmenge der
     * beiden LCS gegen die Basis), und klassifiziert die Stücke dazwischen.
     */
    private fun merge(o: List<String>, a: List<String>, b: List<String>): List<String>? {
        val ma = lcsMatches(o, a) // o-Index -> a-Index (gleiche Zeilen)
        val mb = lcsMatches(o, b) // o-Index -> b-Index
        val syncOs = ma.keys.filter { it in mb.keys } // in o-Reihenfolge, in beiden gematcht

        val out = ArrayList<String>()
        var oPrev = 0; var aPrev = 0; var bPrev = 0
        for (k in syncOs) {
            val oa = ma.getValue(k); val ob = mb.getValue(k)
            val chunk = resolveChunk(o.subList(oPrev, k), a.subList(aPrev, oa), b.subList(bPrev, ob))
                ?: return null
            out.addAll(chunk)
            out.add(o[k]) // Synchronpunkt: in allen dreien identisch
            oPrev = k + 1; aPrev = oa + 1; bPrev = ob + 1
        }
        val tail = resolveChunk(o.subList(oPrev, o.size), a.subList(aPrev, a.size), b.subList(bPrev, b.size))
            ?: return null
        out.addAll(tail)
        return out
    }

    /** Ein Stück zwischen zwei Synchronpunkten zusammenführen. null = Konflikt. */
    private fun resolveChunk(o: List<String>, a: List<String>, b: List<String>): List<String>? = when {
        a == o -> b      // nur B hat dieses Stück geändert
        b == o -> a      // nur A hat dieses Stück geändert
        a == b -> a      // beide gleich geändert
        else -> null     // beide unterschiedlich -> echter Konflikt
    }

    /**
     * LCS (longest common subsequence) als Index-Zuordnung o-Index -> x-Index, in
     * aufsteigender Reihenfolge. Deterministischer Tie-Break (i bevorzugt).
     */
    private fun lcsMatches(o: List<String>, x: List<String>): LinkedHashMap<Int, Int> {
        val n = o.size; val m = x.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            dp[i][j] = if (o[i] == x[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        val map = LinkedHashMap<Int, Int>()
        var i = 0; var j = 0
        while (i < n && j < m) {
            when {
                o[i] == x[j] -> { map[i] = j; i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        return map
    }
}
