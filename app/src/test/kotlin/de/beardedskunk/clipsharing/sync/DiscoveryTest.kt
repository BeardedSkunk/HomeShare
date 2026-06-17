package de.beardedskunk.clipsharing.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryTest {

    @Test fun beacon_roundTrips() {
        val b = Beacon(group = "meine-gruppe", deviceId = "06afa9cb-e47c-4d1e-8415-f8c1c1357c0f", port = 47600)
        assertEquals(b, BeaconCodec.parse(BeaconCodec.encode(b)))
    }

    @Test fun beacon_groupWithPipeSurvives() {
        // Die Gruppe steht zuletzt und wird per split-Limit komplett erfasst.
        val b = Beacon(group = "a|b|c", deviceId = "dev-1", port = 1234)
        assertEquals(b, BeaconCodec.parse(BeaconCodec.encode(b)))
    }

    @Test fun beacon_rejectsForeignOrBroken() {
        assertNull(BeaconCodec.parse("ganz was anderes"))
        assertNull(BeaconCodec.parse("CLIPB1|notaport|dev|grp"))
        assertNull(BeaconCodec.parse("CLIPB1|47600|dev")) // zu wenig Felder
        assertNull(BeaconCodec.parse("CLIPB1|70000|dev|grp")) // Port außerhalb 1..65535
    }

    @Test fun peerList_singleIps() {
        assertEquals(listOf("192.168.178.4", "192.168.178.9"), PeerList.parse("192.168.178.4, 192.168.178.9"))
        // Trenner gemischt + Whitespace, Duplikate raus, Reihenfolge bleibt.
        assertEquals(listOf("10.0.0.1", "10.0.0.2"), PeerList.parse(" 10.0.0.1\n10.0.0.2 ; 10.0.0.1 "))
    }

    @Test fun peerList_fullRange() {
        assertEquals(
            listOf("192.168.178.1", "192.168.178.2", "192.168.178.3"),
            PeerList.parse("192.168.178.1-192.168.178.3"),
        )
    }

    @Test fun peerList_shorthandRange() {
        assertEquals(
            listOf("192.168.178.8", "192.168.178.9", "192.168.178.10"),
            PeerList.parse("192.168.178.8-10"),
        )
    }

    @Test fun peerList_ignoresGarbageAndOversizedOrReversed() {
        assertTrue(PeerList.parse("nonsense, 999.1.1.1, 1.2.3").isEmpty())
        assertTrue(PeerList.parse("192.168.0.0-192.168.255.255").isEmpty()) // zu groß -> ignoriert
        assertTrue(PeerList.parse("192.168.178.10-192.168.178.1").isEmpty()) // rückwärts -> ignoriert
    }

    @Test fun peerList_mixedListAndRange() {
        assertEquals(
            listOf("192.168.178.4", "192.168.178.20", "192.168.178.21"),
            PeerList.parse("192.168.178.4, 192.168.178.20-21"),
        )
    }
}
