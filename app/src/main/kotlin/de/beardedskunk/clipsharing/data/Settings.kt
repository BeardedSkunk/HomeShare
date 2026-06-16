package de.beardedskunk.clipsharing.data

import android.content.Context

/** Geraete-lokale Einstellungen (FRITZ!Box-Zugang, Bild-Speicherbudget). */
class Settings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("clip_settings", Context.MODE_PRIVATE)

    var fritzHost: String
        get() = prefs.getString(K_HOST, "fritz.box") ?: "fritz.box"
        set(v) = prefs.edit().putString(K_HOST, v).apply()

    var fritzPort: Int
        get() = prefs.getInt(K_PORT, 21)
        set(v) = prefs.edit().putInt(K_PORT, v).apply()

    var fritzUser: String
        get() = prefs.getString(K_USER, "") ?: ""
        set(v) = prefs.edit().putString(K_USER, v).apply()

    var fritzPassword: String
        get() = prefs.getString(K_PASS, "") ?: ""
        set(v) = prefs.edit().putString(K_PASS, v).apply()

    /** Basisordner auf der Box; Gruppe wird als Unterordner angehaengt. */
    var fritzBaseDir: String
        get() = prefs.getString(K_BASE, "/clipsharing") ?: "/clipsharing"
        set(v) = prefs.edit().putString(K_BASE, v).apply()

    /** Lokales Speicherbudget fuer Voll-Bilder in MB (0 = unbegrenzt). */
    var imageBudgetMb: Int
        get() = prefs.getInt(K_BUDGET_MB, 0)
        set(v) = prefs.edit().putInt(K_BUDGET_MB, v).apply()

    val imageBudgetBytes: Long get() = imageBudgetMb.toLong() * 1024L * 1024L

    fun fritzConfigured(): Boolean = fritzUser.isNotBlank() && fritzHost.isNotBlank()

    companion object {
        private const val K_HOST = "fritz_host"
        private const val K_PORT = "fritz_port"
        private const val K_USER = "fritz_user"
        private const val K_PASS = "fritz_pass"
        private const val K_BASE = "fritz_base"
        private const val K_BUDGET_MB = "image_budget_mb"
    }
}
