package de.beardedskunk.clipsharing.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GroupCryptoTest {

    private val salt = "meine-gruppe".toByteArray()

    @Test
    fun roundTrip_withSamePassphrase() {
        val key = GroupCrypto.deriveKey("geheim", salt)
        val msg = "Treffen um 18 Uhr\nmit Bild".toByteArray()
        val cipher = GroupCrypto.encrypt(key, msg)
        assertArrayEquals(msg, GroupCrypto.decrypt(key, cipher))
    }

    @Test
    fun wrongPassphrase_failsToDecrypt() {
        val good = GroupCrypto.deriveKey("geheim", salt)
        val bad = GroupCrypto.deriveKey("falsch", salt)
        val cipher = GroupCrypto.encrypt(good, "daten".toByteArray())
        // Falscher Schluessel -> GCM-Tag schlaegt fehl (Exception).
        assertThrows(Exception::class.java) { GroupCrypto.decrypt(bad, cipher) }
    }

    @Test
    fun nonceMakesCiphertextsDiffer() {
        val key = GroupCrypto.deriveKey("geheim", salt)
        val a = GroupCrypto.encrypt(key, "x".toByteArray())
        val b = GroupCrypto.encrypt(key, "x".toByteArray())
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun sameInputs_deriveSameKey() {
        val k1 = GroupCrypto.deriveKey("p", salt)
        val k2 = GroupCrypto.deriveKey("p", salt)
        assertEquals(k1.encoded.toList(), k2.encoded.toList())
    }
}
