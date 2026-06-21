package de.beardedskunk.homeshare.sync

import de.beardedskunk.homeshare.core.Hlc
import de.beardedskunk.homeshare.core.NodeContent
import de.beardedskunk.homeshare.core.NodeVersion
import de.beardedskunk.homeshare.crypto.GroupCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * End-to-End-Test des Gerät-zu-Gerät-Syncs über einen **echten TCP-Socket** mit
 * dem verschlüsselten [SecureChannel] – also genau der Pfad, der live zwischen zwei
 * Handys läuft (NSD findet nur die Adresse; ab hier ist alles identisch). Deckt das
 * ab, was der reine [SyncReconciler]-Test nicht prüft: Krypto + Wire-Format + Socket.
 */
class PeerProtocolSocketTest {

    private class MemSource : OpSource {
        val ops = LinkedHashMap<String, OpDto>()
        override fun versionVector(): Map<String, PeerState> =
            ops.values.groupBy { it.deviceId }.mapValues { e ->
                val seqs = e.value.map { it.seq }
                PeerState(seqs.max(), (1L..seqs.max()).filter { it !in seqs.toHashSet() })
            }
        override fun missingFor(remote: Map<String, PeerState>): List<OpDto> =
            ops.values.filter { op ->
                val st = remote[op.deviceId]
                st == null || op.seq > st.maxSeq || op.seq in st.gaps
            }
        override fun ingestOp(op: OpDto): Boolean {
            if (ops.containsKey(op.versionId)) return false
            ops[op.versionId] = op; return true
        }
        override fun displayedBlobHashes(): Set<String> = emptySet()
    }

    private fun op(device: String, seq: Long, text: String): OpDto {
        val v = NodeVersion("node-$device-$seq", emptySet(), device, Hlc(seq, 0), NodeContent(text = text))
        return OpDto.from(v, rootId = "feed1", seq = seq)
    }

    private fun key(passphrase: String) = GroupCrypto.deriveKey(passphrase, "meine-gruppe".toByteArray())

    @Test
    fun fullSocketSync_convergesOverEncryptedChannel() {
        val a = MemSource().apply { ingestOp(op("A", 1, "a1")); ingestOp(op("A", 2, "Zeile\nmit Umbruch, Komma")) }
        val b = MemSource().apply { ingestOp(op("B", 1, "b1")) }
        val server = ServerSocket(0, 0, InetAddress.getLoopbackAddress())

        val responder = thread {
            server.use {
                it.accept().use { s ->
                    PeerProtocol.runResponder(b, SecureChannel(s.getInputStream(), s.getOutputStream(), key("gleich")))
                }
            }
        }

        Socket(InetAddress.getLoopbackAddress(), server.localPort).use { s ->
            val res = PeerProtocol.runInitiator(a, SecureChannel(s.getInputStream(), s.getOutputStream(), key("gleich")))
            assertEquals(1, res.pulled) // A bekommt b1
            assertEquals(2, res.pushed) // B bekommt a1, a2
        }
        responder.join(5000)
        assertFalse("Responder-Thread muss terminieren (kein Handshake-Deadlock)", responder.isAlive)

        assertEquals(a.ops.keys, b.ops.keys)
        assertEquals(3, a.ops.size)
        // Inhalt inkl. Zeilenumbruch/Komma unverändert über den verschlüsselten Kanal angekommen.
        assertEquals("Zeile\nmit Umbruch, Komma", b.ops.values.first { it.deviceId == "A" && it.seq == 2L }.text)
    }

    /** In-Memory-Blobspeicher: prueft den SHA beim Speichern wie der echte BlobStore. */
    private class MemBlobs(have: Map<String, ByteArray> = emptyMap(), val want: Set<String> = emptySet()) : BlobSync {
        val store = HashMap<String, ByteArray>(have)
        override fun wanted(): Set<String> = want
        override fun has(sha: String): Boolean = store.containsKey(sha)
        override fun read(sha: String): ByteArray? = store[sha]
        override fun store(sha: String, bytes: ByteArray) {
            require(de.beardedskunk.homeshare.core.Hashing.sha256Hex(bytes) == sha) { "SHA passt nicht" }
            store[sha] = bytes
        }
    }

    @Test
    fun blobsTransferBothDirections_overEncryptedChannel() {
        val a = MemSource(); val b = MemSource()
        val imgA = ByteArray(5000) { (it % 251).toByte() }   // groesser als ein paar Bytes
        val imgB = "ein bild von b".toByteArray()
        val shaA = de.beardedskunk.homeshare.core.Hashing.sha256Hex(imgA)
        val shaB = de.beardedskunk.homeshare.core.Hashing.sha256Hex(imgB)
        // A hat imgA und will imgB; B hat imgB und will imgA.
        val blobsA = MemBlobs(have = mapOf(shaA to imgA), want = setOf(shaB))
        val blobsB = MemBlobs(have = mapOf(shaB to imgB), want = setOf(shaA))
        val server = ServerSocket(0, 0, InetAddress.getLoopbackAddress())

        val responder = thread {
            server.use {
                it.accept().use { s ->
                    PeerProtocol.runResponder(b, SecureChannel(s.getInputStream(), s.getOutputStream(), key("g")), blobsB)
                }
            }
        }
        Socket(InetAddress.getLoopbackAddress(), server.localPort).use { s ->
            PeerProtocol.runInitiator(a, SecureChannel(s.getInputStream(), s.getOutputStream(), key("g")), blobsA)
        }
        responder.join(5000)
        assertFalse("kein Deadlock im Blob-Austausch", responder.isAlive)

        assertTrue("A hat imgB empfangen", blobsA.store[shaB]?.contentEquals(imgB) == true)
        assertTrue("B hat imgA (5000 Byte) empfangen", blobsB.store[shaA]?.contentEquals(imgA) == true)
    }

    @Test
    fun wrongPassphrase_failsHandshake_andLeaksNothing() {
        val a = MemSource().apply { ingestOp(op("A", 1, "geheim")) }
        val b = MemSource().apply { ingestOp(op("B", 1, "b1")) }
        val server = ServerSocket(0, 0, InetAddress.getLoopbackAddress())

        val responder = thread {
            runCatching {
                server.use {
                    it.accept().use { s ->
                        PeerProtocol.runResponder(b, SecureChannel(s.getInputStream(), s.getOutputStream(), key("FALSCH")))
                    }
                }
            }
        }

        val failed = runCatching {
            Socket(InetAddress.getLoopbackAddress(), server.localPort).use { s ->
                PeerProtocol.runInitiator(a, SecureChannel(s.getInputStream(), s.getOutputStream(), key("richtig")))
            }
        }.isFailure
        responder.join(5000)

        // Falsche Passphrase -> GCM-Tag schlägt fehl -> Abgleich scheitert, kein stiller Teilerfolg.
        assertTrue("Falsche Passphrase muss den Sync scheitern lassen", failed)
        assertTrue("Ohne passende Passphrase darf nichts entschlüsselt/übernommen werden",
            b.ops.values.none { it.text == "geheim" })
    }
}
