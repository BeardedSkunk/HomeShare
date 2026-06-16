package de.beardedskunk.clipsharing.data

import de.beardedskunk.clipsharing.core.Hashing
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BlobStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // Fake-Thumbnailer: liefert deterministisch ein paar Bytes.
    private fun store() = BlobStore(tmp.root, thumbnailer = { byteArrayOf(1, 2, 3) })

    @Test
    fun put_isContentAddressed_andDeduplicates() {
        val store = store()
        val bytes = "hallo welt".toByteArray()
        val sha1 = store.put(bytes)
        val sha2 = store.put(bytes)
        assertEquals(Hashing.sha256Hex(bytes), sha1)
        assertEquals(sha1, sha2)
        assertTrue(store.hasFull(sha1))
        assertTrue("Thumbnail wird angelegt", store.hasThumb(sha1))
        assertArrayEquals(bytes, store.readFull(sha1))
    }

    @Test
    fun deleteFull_removesFull_keepsThumb() {
        val store = store()
        val sha = store.put("bild".toByteArray())
        assertTrue(store.deleteFull(sha))
        assertFalse(store.hasFull(sha))
        assertTrue("Thumbnail bleibt nach Eviction", store.hasThumb(sha))
        assertNull(store.readFull(sha))
    }

    @Test
    fun totalFullBytes_andSizes_reflectStoredBlobs() {
        val store = store()
        val a = store.put("aaaa".toByteArray()) // 4 bytes
        val b = store.put("bbbbbb".toByteArray()) // 6 bytes
        assertEquals(10L, store.totalFullBytes())
        val sizes = store.fullSizes()
        assertEquals(4L, sizes[a])
        assertEquals(6L, sizes[b])
    }

    @Test(expected = IllegalArgumentException::class)
    fun putWithSha_rejectsMismatchedContent() {
        store().putWithSha("deadbeef", "etwas anderes".toByteArray())
    }
}
