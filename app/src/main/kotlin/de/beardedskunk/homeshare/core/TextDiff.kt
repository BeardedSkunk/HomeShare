package de.beardedskunk.homeshare.core

enum class DiffOp { EQUAL, INSERT, DELETE }

/** Ein zusammenhaengendes Diff-Segment (mehrere Tokens gleicher Art zusammengefasst). */
data class DiffSegment(val op: DiffOp, val text: String)

/**
 * Wort-genauer Diff (LCS) zwischen zwei Texten, fuer die Konflikt-Anzeige.
 * Whitespace wird als eigene Tokens erhalten, damit der Originaltext aus den
 * Segmenten rekonstruierbar bleibt. Reine Logik -> per Unit-Test verifizierbar.
 */
object TextDiff {

    fun diff(a: String, b: String): List<DiffSegment> {
        val ta = tokenize(a)
        val tb = tokenize(b)
        val lcs = lcsTable(ta, tb)

        val raw = ArrayList<Pair<DiffOp, String>>()
        var i = 0
        var j = 0
        while (i < ta.size && j < tb.size) {
            if (ta[i] == tb[j]) {
                raw += DiffOp.EQUAL to ta[i]; i++; j++
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                raw += DiffOp.DELETE to ta[i]; i++
            } else {
                raw += DiffOp.INSERT to tb[j]; j++
            }
        }
        while (i < ta.size) { raw += DiffOp.DELETE to ta[i]; i++ }
        while (j < tb.size) { raw += DiffOp.INSERT to tb[j]; j++ }

        // Aufeinanderfolgende Tokens gleicher Art zusammenfassen.
        val out = ArrayList<DiffSegment>()
        for ((op, text) in raw) {
            val last = out.lastOrNull()
            if (last != null && last.op == op) {
                out[out.size - 1] = last.copy(text = last.text + text)
            } else {
                out += DiffSegment(op, text)
            }
        }
        return out
    }

    private fun tokenize(s: String): List<String> =
        if (s.isEmpty()) emptyList() else Regex("\\s+|\\S+").findAll(s).map { it.value }.toList()

    private fun lcsTable(a: List<String>, b: List<String>): Array<IntArray> {
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.size - 1 downTo 0) {
            for (j in b.size - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        return dp
    }
}
