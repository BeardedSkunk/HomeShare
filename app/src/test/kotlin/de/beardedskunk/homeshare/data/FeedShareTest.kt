package de.beardedskunk.homeshare.data

import de.beardedskunk.homeshare.crypto.GroupCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fundament des Feed-Sharings (#10): Rechte, Freigabe-Codec (Variante A), QR-Pairing, Krypto. */
class FeedShareTest {

    @Test fun rights_writeAndMergeImplications() {
        assertFalse(FeedRight.READ.canWrite()); assertFalse(FeedRight.READ.canMerge())
        assertTrue(FeedRight.WRITE.canWrite()); assertFalse(FeedRight.WRITE.canMerge())
        assertTrue(FeedRight.MERGE.canWrite()); assertTrue(FeedRight.MERGE.canMerge())
        assertEquals(FeedRight.MERGE, FeedRight.from("merge"))
        assertEquals(FeedRight.READ, FeedRight.from("quatsch"))
    }

    @Test fun shareGrants_roundTrip_inFeedText_keepingName() {
        val grants = listOf(
            ShareGrant("cap1", FeedRight.READ, "Familie Müller", "ENCSECRETA=="),
            ShareGrant("cap2", FeedRight.MERGE, "WG | Berlin :: 4", "ENCSECRETB=="),
        )
        val text = FeedShareCodec.feedText("Urlaub", grants = grants)
        // Name (1. Zeile) muss unveraendert dekodieren; das Kalender-Flag ist jetzt ein
        // eigenes Knotenfeld (childDefault), nicht mehr im Text.
        assertEquals("Urlaub", FeedShareCodec.nameOf(text))
        assertTrue(FeedShareCodec.isShared(text))
        // Freigaben unveraendert zurueck (auch Label mit Sonderzeichen).
        assertEquals(grants, FeedShareCodec.decode(text))
    }

    @Test fun noGrants_isNotShared() {
        val text = FeedShareCodec.feedText("Notizen", grants = emptyList())
        assertEquals("Notizen", text)
        assertFalse(FeedShareCodec.isShared(text))
        assertTrue(FeedShareCodec.decode(text).isEmpty())
    }

    @Test fun pairingPayload_roundTrips() {
        val p = PairingPayload(
            feedId = "85df9ecc-b086-4d1e-b0cd-e22a7e9eb490",
            feedName = "Urlaub 2026",
            originGroup = "meine-gruppe",
            capId = GroupCrypto.randomToken(12),
            capSecret = GroupCrypto.randomToken(32),
            host = "192.168.178.4",
            port = 47600,
            expiresAtMillis = 1781776800000L,
        )
        val back = PairingPayload.parse(PairingPayload.encode(p))!!
        assertEquals(p, back)
        assertNull(PairingPayload.parse("kein pairing code"))
    }

    @Test fun capSecret_isUsableAsChannelKey_andEncryptsSecretsForSync() {
        // capSecret (32 zufaellige Bytes, base64) dient direkt als AES-Kanal-Schluessel.
        val capSecret = GroupCrypto.randomToken(32)
        val key = GroupCrypto.keyFromToken(capSecret)
        assertEquals("hallo", GroupCrypto.decryptString(key, GroupCrypto.encryptString(key, "hallo")))

        // Das capSecret selbst wird mit dem Gruppen-Schluessel verschluesselt im Feed-Text abgelegt.
        val groupKey = GroupCrypto.deriveKey("pw", "grp".toByteArray())
        val enc = GroupCrypto.encryptString(groupKey, capSecret)
        assertEquals(capSecret, GroupCrypto.decryptString(groupKey, enc))
        // Mit falschem Gruppen-Schluessel scheitert die Entschluesselung.
        val wrong = GroupCrypto.deriveKey("falsch", "grp".toByteArray())
        assertNull(runCatching { GroupCrypto.decryptString(wrong, enc) }.getOrNull())
    }
}
