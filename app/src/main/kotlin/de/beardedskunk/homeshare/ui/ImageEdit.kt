package de.beardedskunk.homeshare.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Ein externer Bild-Editor, der [Intent.ACTION_EDIT] für Bilder beantwortet
 * (z. B. „Markup"/Screenshot-Editor, Google Fotos, Pixlr …).
 */
data class EditTarget(val label: String, val pkg: String, val cls: String)

/**
 * Alle installierten Apps, die ein Bild über [Intent.ACTION_EDIT] bearbeiten können.
 *
 * Hinweis zur Erkennung (vom Nutzer gewünscht): Ob es einen *eigenständigen*
 * Screenshot-/Markup-Editor zusätzlich zum Foto-Editor gibt, zeigt sich genau hier –
 * dann erscheint ein weiteres ACTION_EDIT-Ziel (auf dem Pixel z. B.
 * `com.google.android.markup/.AnnotateActivity`). Gibt es nur eines, reicht ein
 * „Bearbeiten"-Eintrag.
 *
 * Achtung Paket-Sichtbarkeit (Android 11+): Damit diese Liste vollständig ist, muss
 * das Manifest einen <queries>-Block für ACTION_EDIT/image enthalten. Fehlt er, ist
 * die Liste evtl. leer/unvollständig – dann fällt die UI auf einen einzelnen
 * „Bearbeiten"-Knopf zurück (System-Default bzw. Chooser), was ohnehin korrekt ist,
 * sobald es mehr als drei Ziele gäbe.
 */
fun imageEditTargets(ctx: Context): List<EditTarget> {
    val pm = ctx.packageManager
    val probe = Intent(Intent.ACTION_EDIT)
        .setDataAndType(Uri.parse("content://de.beardedskunk.homeshare.probe/x.png"), "image/png")
    return runCatching {
        pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { ri ->
                val ai = ri.activityInfo ?: return@mapNotNull null
                if (ai.packageName == ctx.packageName) return@mapNotNull null
                EditTarget(ri.loadLabel(pm).toString(), ai.packageName, ai.name)
            }
            .distinctBy { "${it.pkg}/${it.cls}" }
    }.getOrDefault(emptyList())
}
