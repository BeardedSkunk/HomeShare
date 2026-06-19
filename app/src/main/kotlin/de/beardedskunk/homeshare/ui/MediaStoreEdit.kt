package de.beardedskunk.homeshare.ui

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/**
 * Temporärer Galerie-Eintrag für die externe Bild-Bearbeitung.
 *
 * Hintergrund: Editoren wie „Markup" liefern ihr Ergebnis **nicht** über eine
 * Result-URI zurück, sondern überschreiben die übergebene Bild-URI **in-place** –
 * aber nur, wenn sie über den MediaStore (Galerie) kommt, nicht bei einer
 * FileProvider-URI (die wird beim Öffnen im Schreibmodus auf 0 Byte gekürzt).
 *
 * Lösung: Wir legen das zu bearbeitende Bild als **vorläufigen** Galerie-Eintrag
 * an (IS_PENDING, in einem eigenen Unterordner), lassen den Editor darauf arbeiten
 * und lesen das Ergebnis danach aus demselben Eintrag zurück. Anschließend wird der
 * Eintrag wieder gelöscht – die Galerie bleibt sauber (kurzes Aufblitzen während
 * der Bearbeitung in Kauf genommen).
 */
object MediaStoreEdit {

    /**
     * Legt einen vorläufigen Galerie-Eintrag mit [bytes] an und gibt dessen URI zurück.
     * Der Editor erhält darauf Lese- **und** Schreibrecht; er kann in-place speichern.
     */
    fun createPending(ctx: Context, bytes: ByteArray, displayName: String): Uri? {
        val resolver = ctx.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/HomeShare")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return null
        val ok = runCatching {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return@runCatching false
            true
        }.getOrDefault(false)
        if (!ok) { runCatching { resolver.delete(uri, null, null) }; return null }
        // Freigeben, damit Galerie-Editoren den Eintrag sehen und bearbeiten können.
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        runCatching { resolver.update(uri, values, null, null) }
        return uri
    }

    /** Liest die (ggf. vom Editor überschriebenen) Bytes des Eintrags. */
    fun read(ctx: Context, uri: Uri): ByteArray? =
        runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()

    /** Entfernt den vorläufigen Galerie-Eintrag wieder. */
    fun delete(ctx: Context, uri: Uri) {
        runCatching { ctx.contentResolver.delete(uri, null, null) }
    }
}
