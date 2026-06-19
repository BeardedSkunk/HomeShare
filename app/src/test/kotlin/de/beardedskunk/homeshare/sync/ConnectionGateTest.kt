package de.beardedskunk.homeshare.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sichert die Drossel-Logik gegen den Verbindungs-Sturm ab, der den
 * Gerät-zu-Gerät-Sync lahmgelegt hatte (jeder Beacon alle 2 s -> neue, lange
 * blockierende Verbindung -> IO-Pool voll -> Server nimmt nichts mehr an).
 */
class ConnectionGateTest {

    @Test fun noTwoConcurrentConnectionsToSamePeer() {
        val g = ConnectionGate(cooldownMs = 1_000)
        assertTrue(g.tryAcquire("a:1", now = 0))
        assertFalse("zweite gleichzeitige Verbindung zum selben Peer wird abgelehnt", g.tryAcquire("a:1", now = 10))
        assertEquals(1, g.inFlightCount())
    }

    @Test fun differentPeersAreIndependent() {
        val g = ConnectionGate(cooldownMs = 1_000)
        assertTrue(g.tryAcquire("a:1", now = 0))
        assertTrue(g.tryAcquire("b:1", now = 0))
        assertEquals(2, g.inFlightCount())
    }

    @Test fun cooldownBlocksRapidReconnect_thenAllowsAfterwards() {
        val g = ConnectionGate(cooldownMs = 1_000)
        assertTrue(g.tryAcquire("a:1", now = 0))
        g.release("a:1", now = 100)
        assertEquals(0, g.inFlightCount())
        assertFalse("innerhalb des Cooldowns kein neuer Verbindungsversuch", g.tryAcquire("a:1", now = 500))
        assertTrue("nach Ablauf des Cooldowns wieder erlaubt", g.tryAcquire("a:1", now = 1_200))
    }

    @Test fun releaseFreesTheSlotImmediately() {
        val g = ConnectionGate(cooldownMs = 0)
        assertTrue(g.tryAcquire("a:1", now = 0))
        g.release("a:1", now = 1)
        assertTrue("ohne Cooldown sofort wieder verbindbar", g.tryAcquire("a:1", now = 2))
    }

    @Test fun clearResetsEverything() {
        val g = ConnectionGate(cooldownMs = 10_000)
        g.tryAcquire("a:1", now = 0)
        g.release("a:1", now = 0)
        g.clear()
        assertEquals(0, g.inFlightCount())
        assertTrue("nach clear ignoriert der Cooldown alte Zeiten", g.tryAcquire("a:1", now = 5))
    }
}
