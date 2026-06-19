package de.beardedskunk.homeshare.core

import java.security.MessageDigest

/** Inhaltsadressierung: SHA-256 als Hex-String, fuer Versions-Ids und Bild-Blobs. */
object Hashing {
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xff
            sb.append(Character.forDigit(v ushr 4, 16))
            sb.append(Character.forDigit(v and 0x0f, 16))
        }
        return sb.toString()
    }

    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))
}
