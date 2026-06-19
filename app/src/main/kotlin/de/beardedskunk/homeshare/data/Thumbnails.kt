package de.beardedskunk.homeshare.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

private const val THUMB_MAX_DIM = 256

/**
 * Erzeugt ein kleines JPEG-Thumbnail (max. [THUMB_MAX_DIM] px Kantenlaenge) aus
 * den Bild-Bytes. Wird in [BlobStore] als `thumbnailer` injiziert.
 */
fun androidThumbnailer(bytes: ByteArray): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val w = bounds.outWidth
    val h = bounds.outHeight
    if (w <= 0 || h <= 0) return null

    var sample = 1
    while (w / (sample * 2) >= THUMB_MAX_DIM && h / (sample * 2) >= THUMB_MAX_DIM) sample *= 2

    val decoded = BitmapFactory.decodeByteArray(
        bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    ) ?: return null

    val scale = THUMB_MAX_DIM.toFloat() / maxOf(decoded.width, decoded.height)
    val thumb = if (scale < 1f) {
        Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    } else {
        decoded
    }

    return ByteArrayOutputStream().use { out ->
        thumb.compress(Bitmap.CompressFormat.JPEG, 80, out)
        out.toByteArray()
    }
}
