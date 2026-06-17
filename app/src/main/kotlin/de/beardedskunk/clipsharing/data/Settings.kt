package de.beardedskunk.clipsharing.data

import android.content.Context

/** Geraete-lokale Einstellungen (FRITZ!Box-Zugang, Bild-Speicherbudget). */
class Settings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("clip_settings", Context.MODE_PRIVATE)

    /** Gruppen-Passphrase: verschluesselt den Sync und authentifiziert die Gruppe. */
    var groupPassphrase: String
        get() = prefs.getString(K_PASSPHRASE, "") ?: ""
        set(v) = prefs.edit().putString(K_PASSPHRASE, v).apply()

    /**
     * Manuelle Fallback-Peers fuer den Gerät-zu-Gerät-Sync, falls die automatische
     * Discovery (NSD/Broadcast) im Netz nicht greift. Freitext: einzelne IPs und/oder
     * Bereiche, getrennt durch Komma/Zeilenumbruch, z. B.
     * "192.168.178.4, 192.168.178.1-192.168.178.10". Wird per [de.beardedskunk.clipsharing.sync.PeerList] geparst.
     */
    var fallbackPeers: String
        get() = prefs.getString(K_FALLBACK_PEERS, "") ?: ""
        set(v) = prefs.edit().putString(K_FALLBACK_PEERS, v).apply()

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

    /** FTPES (verschluesselt) statt Klartext-FTP. Standard aus (FRITZ!Box-Heimnetz nutzt Klartext). */
    var fritzUseFtps: Boolean
        get() = prefs.getBoolean(K_FTPS, false)
        set(v) = prefs.edit().putBoolean(K_FTPS, v).apply()

    /** Lokales Speicherbudget fuer Voll-Bilder in GB (0 = unbegrenzt). */
    var imageBudgetGb: Float
        get() = prefs.getFloat(K_BUDGET_GB, 0f)
        set(v) = prefs.edit().putFloat(K_BUDGET_GB, v).apply()

    val imageBudgetBytes: Long get() = (imageBudgetGb.toDouble() * 1024.0 * 1024.0 * 1024.0).toLong()

    fun fritzConfigured(): Boolean = fritzUser.isNotBlank() && fritzHost.isNotBlank()

    companion object {
        private const val K_PASSPHRASE = "group_passphrase"
        private const val K_FALLBACK_PEERS = "fallback_peers"
        private const val K_HOST = "fritz_host"
        private const val K_PORT = "fritz_port"
        private const val K_USER = "fritz_user"
        private const val K_PASS = "fritz_pass"
        private const val K_BASE = "fritz_base"
        private const val K_FTPS = "fritz_use_ftps"
        private const val K_BUDGET_GB = "image_budget_gb"
    }
}
