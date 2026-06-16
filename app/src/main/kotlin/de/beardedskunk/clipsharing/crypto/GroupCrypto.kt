package de.beardedskunk.clipsharing.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Anwendungs-Schicht-Verschluesselung fuer den Sync. Der AES-Schluessel wird per
 * PBKDF2 aus der **Gruppen-Passphrase** abgeleitet (Salt = Gruppenname). Dadurch:
 *  - Inhalte sind auf dem Draht verschluesselt (AES-GCM, auch in fremden WLANs),
 *  - nur Geraete mit derselben Passphrase koennen entschluesseln -> implizite
 *    Gruppen-Authentifizierung (falsche Passphrase = GCM-Tag schlaegt fehl).
 *
 * Bewusst ohne Forward Secrecy (statischer Schluessel) – ausreichend fuer ein
 * privates LAN; pro Nachricht wird eine zufaellige Nonce verwendet.
 */
object GroupCrypto {
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    fun deriveKey(passphrase: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    /** Liefert nonce(12) || ciphertext+tag. */
    fun encrypt(key: SecretKey, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        val ct = cipher.doFinal(plaintext)
        return nonce + ct
    }

    /** Erwartet nonce(12) || ciphertext+tag; wirft bei falschem Schluessel/Manipulation. */
    fun decrypt(key: SecretKey, data: ByteArray): ByteArray {
        require(data.size > NONCE_BYTES) { "Daten zu kurz" }
        val nonce = data.copyOfRange(0, NONCE_BYTES)
        val ct = data.copyOfRange(NONCE_BYTES, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        return cipher.doFinal(ct)
    }
}
