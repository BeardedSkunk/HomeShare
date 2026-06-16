package de.beardedskunk.clipsharing.sync

import de.beardedskunk.clipsharing.crypto.GroupCrypto
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.SecretKey

/**
 * Verschluesselter Nachrichtenkanal ueber einen Socket: jede Nachricht wird als
 * laengen-praefixierter, AES-GCM-verschluesselter Frame uebertragen
 * (siehe [GroupCrypto]). Wird vom [PeerProtocol] genutzt.
 */
class SecureChannel(
    input: InputStream,
    output: OutputStream,
    private val key: SecretKey,
) {
    private val din = DataInputStream(BufferedInputStream(input))
    private val dout = DataOutputStream(BufferedOutputStream(output))

    fun writeText(s: String) = writeMessage(s.toByteArray(Charsets.UTF_8))
    fun readText(): String = String(readMessage(), Charsets.UTF_8)

    private fun writeMessage(plain: ByteArray) {
        val enc = GroupCrypto.encrypt(key, plain)
        dout.writeInt(enc.size)
        dout.write(enc)
        dout.flush()
    }

    private fun readMessage(): ByteArray {
        val size = din.readInt()
        require(size in 1..MAX_FRAME) { "Ungueltige Framegroesse: $size" }
        val buf = ByteArray(size)
        din.readFully(buf)
        return GroupCrypto.decrypt(key, buf)
    }

    companion object {
        private const val MAX_FRAME = 16 * 1024 * 1024
    }
}
