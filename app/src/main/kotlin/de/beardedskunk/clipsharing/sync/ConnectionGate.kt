package de.beardedskunk.clipsharing.sync

import java.util.concurrent.ConcurrentHashMap

/**
 * Drosselt Sync-Verbindungen pro Peer – ohne diese Begrenzung erzeugt der
 * Beacon (alle 2 s je Peer) plus Auto-Sync einen Verbindungs-Sturm: jede
 * Verbindung blockiert beim Handshake bis zu mehreren Sekunden, der IO-Thread-Pool
 * laeuft voll, der Server nimmt keine neuen Verbindungen mehr an (genau der
 * Fehler, der den Gerät-zu-Gerät-Sync lahmgelegt hat).
 *
 * Zwei Regeln, beide hier zentral und testbar:
 *  1. **Keine zwei gleichzeitigen** Verbindungen zum selben Peer (in-flight-Dedup).
 *  2. **Cooldown**: nach einer abgeschlossenen Verbindung erst nach [cooldownMs]
 *     wieder zum selben Peer verbinden (sammelt Beacon-Bursts ein).
 *
 * Reine Logik, kein Android, kein Zeitgeber: [now] wird hereingereicht.
 */
class ConnectionGate(private val cooldownMs: Long = 4_000L) {
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val lastDone = ConcurrentHashMap<String, Long>()

    /**
     * Versucht, einen Verbindungs-Slot fuer [peer] zu belegen.
     * @return true = jetzt verbinden (Slot belegt; spaeter [release] aufrufen);
     *         false = schon eine Verbindung aktiv oder Cooldown noch nicht abgelaufen.
     */
    fun tryAcquire(peer: String, now: Long): Boolean {
        if (!inFlight.add(peer)) return false // schon in-flight
        val last = lastDone[peer]
        if (last != null && now - last < cooldownMs) {
            inFlight.remove(peer)
            return false
        }
        return true
    }

    /** Slot freigeben und Cooldown-Startzeit setzen. */
    fun release(peer: String, now: Long) {
        lastDone[peer] = now
        inFlight.remove(peer)
    }

    /** Alles vergessen (z. B. beim Stoppen/Pausieren des Syncs). */
    fun clear() {
        inFlight.clear()
        lastDone.clear()
    }

    fun inFlightCount(): Int = inFlight.size
}
