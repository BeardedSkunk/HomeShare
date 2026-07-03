package de.beardedskunk.homeshare.core

/**
 * Fraktionale Sortierschlüssel über dem Hex-Alphabet 0-9a-f (lexikografisch vergleichbar).
 *
 * Knoten OHNE gesetzten orderKey sortieren über einen **virtuellen Seed-Key** aus ihrer
 * Erzeugungs-HLC – das ist exakt die bisherige Erzeugungs-Reihenfolge. Beim Umsortieren
 * bekommt NUR der gezogene Knoten einen Schlüssel zwischen seinen Nachbarn:
 * 1 Op pro Drag, unbeteiligte Geschwister werden nie umgeschrieben.
 */
object OrderKeys {

    private const val DIGITS = "0123456789abcdef"

    private fun dig(c: Char): Int = DIGITS.indexOf(c).also {
        require(it >= 0) { "Ungültiges orderKey-Zeichen '$c'" }
    }

    /** Virtueller Schlüssel für Knoten ohne orderKey: monoton in der Erzeugungs-HLC. */
    fun seed(created: Hlc): String = "%016x%08x".format(created.wallMillis, created.counter)

    /** Effektiver Sortierschlüssel: gesetzter orderKey, sonst Seed aus der Erzeugungs-HLC. */
    fun effective(orderKey: String, created: Hlc): String = orderKey.ifEmpty { seed(created) }

    /**
     * Schlüssel strikt zwischen [a] und [b] (lexikografisch). null = offene Grenze
     * (Anfang bzw. Ende). Endet nie auf '0', damit vor jedem Schlüssel Platz bleibt.
     * Deterministisch: zwei Geräte, die zwischen dieselben Nachbarn einfügen, berechnen
     * denselben Schlüssel (gleiche Keys sind kein Konflikt – Sekundärsortierung entscheidet).
     */
    fun between(a: String?, b: String?): String {
        val lo = a.orEmpty()
        require(b == null || (b.isNotEmpty() && lo < b)) { "between: '$a' < '$b' verletzt" }
        val sb = StringBuilder()
        var i = 0
        while (true) {
            val dl = if (i < lo.length) dig(lo[i]) else 0
            val dh = if (b == null) DIGITS.length else dig(b[i]) // lo<hi ⇒ hi endet nie vor der ersten Differenz
            if (dh - dl >= 2) {
                sb.append(DIGITS[(dl + dh) / 2])
                return sb.toString()
            }
            if (dh == dl) { // gemeinsamer Präfix
                sb.append(DIGITS[dl]); i++; continue
            }
            // dh == dl+1: untere Grenze übernehmen und oberhalb des lo-Rests einschieben.
            sb.append(DIGITS[dl]); i++
            while (true) {
                val d2 = if (i < lo.length) dig(lo[i]) else 0
                if (d2 == DIGITS.length - 1) { sb.append(DIGITS.last()); i++; continue }
                sb.append(DIGITS[(d2 + DIGITS.length) / 2]) // > d2, nie '0'
                return sb.toString()
            }
        }
    }
}
