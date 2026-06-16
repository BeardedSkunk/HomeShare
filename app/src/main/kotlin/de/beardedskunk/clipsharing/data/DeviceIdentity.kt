package de.beardedskunk.clipsharing.data

import android.content.Context
import de.beardedskunk.clipsharing.core.Hlc
import java.util.UUID

/**
 * Stabile Geraete-Identitaet + lokale Uhren, in SharedPreferences gehalten.
 *
 * - [deviceId]: zufaellige, dauerhafte Id dieses Geraets.
 * - [nextSeq]: monoton steigende Op-Sequenznummer (Basis fuer den Versions-Vektor beim Sync).
 * - [nextHlc]: naechste Hybrid Logical Clock fuer eine lokal erzeugte Operation.
 * - [observe]: HLC nach Empfang einer Fremd-Operation vorziehen.
 */
class DeviceIdentity(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("clip_identity", Context.MODE_PRIVATE)

    val deviceId: String =
        prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }

    /**
     * Name der Geraetegruppe. Aktuell eine feste Standardgruppe; spaeter per
     * QR-/Passphrase-Pairing (siehe Plan). Nur Geraete derselben Gruppe syncen.
     */
    var groupName: String
        get() = prefs.getString(KEY_GROUP, DEFAULT_GROUP) ?: DEFAULT_GROUP
        set(value) { prefs.edit().putString(KEY_GROUP, value).apply() }

    @Synchronized
    fun nextSeq(): Long {
        val next = prefs.getLong(KEY_SEQ, 0L) + 1L
        prefs.edit().putLong(KEY_SEQ, next).apply()
        return next
    }

    @Synchronized
    fun nextHlc(now: Long = System.currentTimeMillis()): Hlc {
        val last = readHlc()
        val next = Hlc.next(now, last)
        writeHlc(next)
        return next
    }

    @Synchronized
    fun observe(remote: Hlc, now: Long = System.currentTimeMillis()) {
        val last = readHlc()
        val maxWall = maxOf(now, remote.wallMillis, last?.wallMillis ?: Long.MIN_VALUE)
        val counter = when {
            last != null && remote.wallMillis == last.wallMillis && maxWall == last.wallMillis ->
                maxOf(last.counter, remote.counter) + 1
            maxWall == remote.wallMillis -> remote.counter + 1
            else -> 0
        }
        writeHlc(Hlc(maxWall, counter))
    }

    private fun readHlc(): Hlc? {
        if (!prefs.contains(KEY_HLC_WALL)) return null
        return Hlc(prefs.getLong(KEY_HLC_WALL, 0L), prefs.getInt(KEY_HLC_COUNTER, 0))
    }

    private fun writeHlc(hlc: Hlc) {
        prefs.edit().putLong(KEY_HLC_WALL, hlc.wallMillis).putInt(KEY_HLC_COUNTER, hlc.counter).apply()
    }

    companion object {
        private const val DEFAULT_GROUP = "meine-gruppe"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_GROUP = "group_name"
        private const val KEY_SEQ = "seq"
        private const val KEY_HLC_WALL = "hlc_wall"
        private const val KEY_HLC_COUNTER = "hlc_counter"
    }
}
