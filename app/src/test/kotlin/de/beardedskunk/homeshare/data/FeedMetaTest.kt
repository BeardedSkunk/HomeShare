package de.beardedskunk.homeshare.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sichert die Dekodierung der Feed-Metadaten (Name + Kalender-Marker), auf die die
 * Materialisierung (feeds-Tabelle) baut. Regression: ein Kalender-Feed wurde nach einem
 * Update als roher Name „cal\n::kalender::" mit calendar=0 angezeigt, weil die
 * feeds-Zeile von einer alten Code-Version stammte — Fix = einmaliges Re-Materialisieren;
 * dieser Test hält wenigstens die Dekodier-Regel fest.
 */
class FeedMetaTest {

    @Test fun calendarFeed_roundTrips_andDecodesCleanName() {
        val encoded = FeedMeta.encode("cal", calendar = true)
        assertEquals("cal\n::kalender::", encoded)
        assertEquals("cal", FeedMeta.decodeName(encoded))
        assertTrue(FeedMeta.decodeCalendar(encoded))
    }

    @Test fun plainFeed_isNotCalendar() {
        val encoded = FeedMeta.encode("Austausch", calendar = false)
        assertEquals("Austausch", encoded)
        assertEquals("Austausch", FeedMeta.decodeName(encoded))
        assertFalse(FeedMeta.decodeCalendar(encoded))
    }

    @Test fun decodingRawStoredMarker_recoversNameAndFlag() {
        // Exakt der Stand, der auf dem Pixel falsch (roh) materialisiert war.
        val raw = "cal\n::kalender::"
        assertEquals("cal", FeedMeta.decodeName(raw))
        assertTrue(FeedMeta.decodeCalendar(raw))
    }
}
