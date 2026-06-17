package de.beardedskunk.clipsharing.sync

/**
 * Reine, testbare Bausteine für die zusätzlichen Peer-Discovery-Wege neben NSD/mDNS:
 *  - [BeaconCodec]: Wire-Format des UDP-Broadcast-Beacons (mDNS-unabhängiges Finden
 *    im gesamten Broadcast-Segment, robust gegen die NSD-„has no client mapping"-Macken).
 *  - [PeerList]: Parser für die manuelle Fallback-Liste (einzelne IPs und Bereiche),
 *    z. B. "192.168.178.4, 192.168.178.1-192.168.178.10" oder Kurzform "…1-10".
 */

/** Inhalt eines Discovery-Beacons: Gruppe, Geräte-Id und TCP-Port des Sync-Servers. */
data class Beacon(val group: String, val deviceId: String, val port: Int)

object BeaconCodec {
    private const val MAGIC = "CLIPB1"
    private const val SEP = '|'

    // Reihenfolge: MAGIC|port|deviceId|group. Die ersten drei Felder enthalten nie '|'
    // (deviceId ist eine UUID, port numerisch). Die Gruppe steht zuletzt und wird per
    // split-Limit komplett erfasst -> ein '|' im Gruppennamen kann nichts zerstören.
    fun encode(b: Beacon): String = "$MAGIC$SEP${b.port}$SEP${b.deviceId}$SEP${b.group}"

    fun parse(s: String): Beacon? {
        val p = s.split(SEP, limit = 4)
        if (p.size != 4 || p[0] != MAGIC) return null
        val port = p[1].toIntOrNull() ?: return null
        if (port !in 1..65535 || p[2].isEmpty() || p[3].isEmpty()) return null
        return Beacon(group = p[3], deviceId = p[2], port = port)
    }
}

object PeerList {
    /** Bereiche über diese Größe werden ignoriert (Schutz vor versehentlichem Riesen-Scan). */
    private const val MAX_EXPAND = 512
    private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    /**
     * Zerlegt die Nutzereingabe in eine geordnete, duplikatfreie IP-Liste.
     * Trenner: Komma, Semikolon, Whitespace, Zeilenumbruch. Ungültige/zu große
     * Bereiche werden still übersprungen.
     */
    fun parse(input: String): List<String> {
        val out = LinkedHashSet<String>()
        for (raw in input.split(',', ';', '\n', '\r', ' ', '\t')) {
            val tok = raw.trim()
            if (tok.isEmpty()) continue
            val dash = tok.indexOf('-')
            if (dash < 0) {
                if (isIpv4(tok)) out.add(tok)
                continue
            }
            val startStr = tok.substring(0, dash).trim()
            var endStr = tok.substring(dash + 1).trim()
            if (!isIpv4(startStr)) continue
            // Kurzform "a.b.c.d-e": nur letztes Oktett.
            if (!endStr.contains('.')) endStr = startStr.substringBeforeLast('.') + "." + endStr
            if (!isIpv4(endStr)) continue
            val s = ipToLong(startStr)
            val e = ipToLong(endStr)
            if (e < s || e - s + 1 > MAX_EXPAND) continue
            var ip = s
            while (ip <= e) { out.add(longToIp(ip)); ip++ }
        }
        return out.toList()
    }

    fun isIpv4(s: String): Boolean {
        val m = IPV4.matchEntire(s) ?: return false
        return (1..4).all { m.groupValues[it].toInt() in 0..255 }
    }

    private fun ipToLong(s: String): Long {
        val m = IPV4.matchEntire(s)!!
        var v = 0L
        for (i in 1..4) v = (v shl 8) or m.groupValues[i].toLong()
        return v
    }

    private fun longToIp(v: Long): String =
        "${(v ushr 24) and 0xff}.${(v ushr 16) and 0xff}.${(v ushr 8) and 0xff}.${v and 0xff}"
}
