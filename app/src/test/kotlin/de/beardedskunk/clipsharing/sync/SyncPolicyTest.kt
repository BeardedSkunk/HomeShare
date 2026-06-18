package de.beardedskunk.clipsharing.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SyncPolicyTest {

    @Test fun noWifi_noSyncAtAll() {
        // Ohne WLAN: weder Peers noch FRITZ!Box – egal wie die Box konfiguriert ist.
        val p = SyncPolicy.plan(wifiConnected = false, fritzConfigured = true, fritzBusy = false)
        assertFalse(p.syncPeers)
        assertFalse(p.syncFritz)
    }

    @Test fun wifi_peersAlways_evenWithoutFritz() {
        // Kern der Anforderung: Handy-zu-Handy greift, auch wenn die FRITZ!Box nicht
        // konfiguriert ist (bzw. falsche Zugangsdaten -> fritzConfigured=false).
        val p = SyncPolicy.plan(wifiConnected = true, fritzConfigured = false, fritzBusy = false)
        assertEquals(true, p.syncPeers)
        assertFalse(p.syncFritz)
    }

    @Test fun syncDisabled_noSyncAtAll_evenWithWifiAndFritz() {
        // Schalter aus: dieses Gerät ist komplett aus dem Abgleich – egal ob WLAN/FRITZ!Box da sind.
        val p = SyncPolicy.plan(wifiConnected = true, fritzConfigured = true, fritzBusy = false, syncEnabled = false)
        assertFalse(p.syncPeers)
        assertFalse(p.syncFritz)
    }

    @Test fun wifi_fritzOnlyWhenConfiguredAndNotBusy() {
        assertEquals(true, SyncPolicy.plan(true, fritzConfigured = true, fritzBusy = false).syncFritz)
        assertFalse(SyncPolicy.plan(true, fritzConfigured = true, fritzBusy = true).syncFritz)
        assertFalse(SyncPolicy.plan(true, fritzConfigured = false, fritzBusy = false).syncFritz)
        // Peers laufen in all diesen Faellen trotzdem.
        assertEquals(true, SyncPolicy.plan(true, fritzConfigured = true, fritzBusy = true).syncPeers)
    }
}
