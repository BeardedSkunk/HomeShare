package de.beardedskunk.homeshare.data

import java.util.Base64

/**
 * Rechtestufe einer Fremdgruppe an einem geteilten Feed (#10).
 *  - [READ]  sehen (Minimum, immer)
 *  - [WRITE] Einträge hinzufügen/bearbeiten (pushen) – aber NICHT Konflikte lösen
 *  - [MERGE] zusätzlich Konflikte selbst lösen
 */
enum class FeedRight {
    READ, WRITE, MERGE;

    fun canWrite(): Boolean = this == WRITE || this == MERGE
    fun canMerge(): Boolean = this == MERGE

    companion object {
        fun from(s: String): FeedRight =
            entries.firstOrNull { it.name.equals(s.trim(), ignoreCase = true) } ?: READ
    }
}

/**
 * Eine Freigabe eines Originalfeeds an EINE Fremdgruppe.
 * - [capId]    öffentliche Id dieser Freigabe (identifiziert die Fremdgruppe beim Sync)
 * - [right]    aktuelle Rechtestufe
 * - [label]    angesagter Gruppenname der Fremdgruppe (nur Anzeige)
 * - [encSecret] das capSecret, verschlüsselt mit dem Originalgruppen-Schlüssel (base64);
 *               synct so innerhalb der Originalgruppe (auch über die FRITZ!Box).
 */
data class ShareGrant(
    val capId: String,
    val right: FeedRight,
    val label: String,
    val encSecret: String,
)

/**
 * Variante A: Freigaben reisen als zusätzliche Zeilen im Feed-Op-Text mit (neben Name und
 * `::kalender::`), Präfix `::share::`. Felder mit `::` getrennt; frei wählbarer Text (Label)
 * base64-kodiert. Die erste Zeile bleibt der reine Feed-Name (siehe [FeedMeta]).
 *
 * Format je Zeile: `::share::<capId>::<right>::<b64(label)>::<encSecret>`
 *
 * (Aufräum-Idee für später: in eine eigene, sauber getrennte Struktur überführen – Variante B.)
 */
object FeedShareCodec {
    private const val PREFIX = "::share::"

    fun decode(feedText: String): List<ShareGrant> =
        feedText.split('\n').mapNotNull { line ->
            if (!line.startsWith(PREFIX)) return@mapNotNull null
            val p = line.removePrefix(PREFIX).split("::")
            if (p.size < 4) return@mapNotNull null
            runCatching {
                ShareGrant(
                    capId = p[0],
                    right = FeedRight.from(p[1]),
                    label = String(Base64.getDecoder().decode(p[2]), Charsets.UTF_8),
                    encSecret = p[3],
                )
            }.getOrNull()
        }

    private fun line(g: ShareGrant): String {
        val lbl = Base64.getEncoder().encodeToString(g.label.toByteArray(Charsets.UTF_8))
        return "$PREFIX${g.capId}::${g.right.name.lowercase()}::$lbl::${g.encSecret}"
    }

    /**
     * Baut den Root-Knoten-Text: 1. Zeile = Feed-Name, danach eine Zeile je Freigabe. (Das
     * Kalender-Flag ist jetzt ein eigenes Knotenfeld [childDefault], nicht mehr im Text.)
     */
    fun feedText(name: String, grants: List<ShareGrant>): String = buildString {
        append(name.lineSequence().firstOrNull().orEmpty())
        for (g in grants) append('\n').append(line(g))
    }

    /** Nur der reine Feed-Name (1. Zeile, ohne ::share::-Zeilen). */
    fun nameOf(feedText: String): String = feedText.lineSequence().firstOrNull().orEmpty()

    fun isShared(feedText: String): Boolean = feedText.split('\n').any { it.startsWith(PREFIX) }
}

/**
 * Inhalt des Pairing-QR (#10). Eine Zeile, `|`-getrennt; Freitext base64. Enthält alles, was die
 * Fremdgruppe braucht, um sich beim anzeigenden Originalgerät zu melden und den Feed zu syncen.
 */
data class PairingPayload(
    val feedId: String,
    val feedName: String,
    val originGroup: String,
    val capId: String,
    val capSecret: String,
    val host: String,
    val port: Int,
    val expiresAtMillis: Long,
) {
    companion object {
        private const val MAGIC = "CLIPPAIR1"
        private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
        private fun unb64(s: String) = String(Base64.getDecoder().decode(s), Charsets.UTF_8)

        fun encode(p: PairingPayload): String = listOf(
            MAGIC, p.feedId, b64(p.feedName), b64(p.originGroup), p.capId, p.capSecret,
            p.host, p.port.toString(), p.expiresAtMillis.toString(),
        ).joinToString("|")

        fun parse(s: String): PairingPayload? {
            val p = s.trim().split("|")
            if (p.size < 9 || p[0] != MAGIC) return null
            return runCatching {
                PairingPayload(
                    feedId = p[1],
                    feedName = unb64(p[2]),
                    originGroup = unb64(p[3]),
                    capId = p[4],
                    capSecret = p[5],
                    host = p[6],
                    port = p[7].toInt(),
                    expiresAtMillis = p[8].toLong(),
                )
            }.getOrNull()
        }
    }
}
