package de.beardedskunk.homeshare.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Lokale Persistenz (Framework-SQLite, kein Room).
 *
 * Quelle der Wahrheit ist der **Op-Log** (`ops`): unveränderliche Versionsknoten des git-artigen DAG
 * für einen **Knoten-Baum**. `node_current` ist der materialisierte aktuelle Stand je Knoten (Cache),
 * `node_fts` (FTS4) indiziert Text (+ Tags). `foreign_refs` hält abonnierte Fremd-Knoten (Cross-Group),
 * `calendar_link` die geräte-lokale Verknüpfung Knoten→Android-Kalender-Event.
 *
 * Optionale/erweiterbare Felder liegen als **eine `meta`-Spalte** (Klartext-Key→Wert, [de.beardedskunk.homeshare.core.MetaCodec]),
 * damit neue Meta-Keys KEINE Schema-Migration brauchen. `fmt` = Kanonik-/Kompatibilitätsversion der Op.
 * `root_id` = oberster Vorfahr-Knoten (Feed) → feed-/subtree-bezogener Sync ohne Baumtraversierung.
 */
class Db(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    private val appContext = context.applicationContext

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE ops(
              version_id TEXT PRIMARY KEY NOT NULL,
              node_id TEXT NOT NULL,
              parent_id TEXT NOT NULL,
              root_id TEXT NOT NULL,
              device_id TEXT NOT NULL,
              seq INTEGER NOT NULL,
              hlc_wall INTEGER NOT NULL,
              hlc_counter INTEGER NOT NULL,
              parents TEXT NOT NULL,
              deleted INTEGER NOT NULL,
              type TEXT NOT NULL,
              order_key TEXT NOT NULL DEFAULT '',
              text TEXT NOT NULL,
              meta TEXT NOT NULL DEFAULT '',
              fmt INTEGER NOT NULL DEFAULT 1,
              device_name TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_ops_node ON ops(node_id)")
        db.execSQL("CREATE INDEX idx_ops_root ON ops(root_id)")
        db.execSQL("CREATE INDEX idx_ops_device_seq ON ops(device_id, seq)")
        db.execSQL(
            """
            CREATE TABLE node_current(
              node_id TEXT PRIMARY KEY NOT NULL,
              parent_id TEXT NOT NULL,
              root_id TEXT NOT NULL,
              type TEXT NOT NULL,
              head_version_id TEXT NOT NULL,
              order_key TEXT NOT NULL DEFAULT '',
              text TEXT NOT NULL,
              meta TEXT NOT NULL DEFAULT '',
              fmt INTEGER NOT NULL DEFAULT 1,
              deleted INTEGER NOT NULL,
              conflicted INTEGER NOT NULL,
              created_wall INTEGER NOT NULL,
              created_counter INTEGER NOT NULL,
              updated_wall INTEGER NOT NULL,
              updated_counter INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_node_current_parent ON node_current(parent_id)")
        db.execSQL("CREATE INDEX idx_node_current_root ON node_current(root_id)")
        db.execSQL("CREATE VIRTUAL TABLE node_fts USING fts4(node_id, text, notindexed=node_id)")
        db.execSQL(
            """
            CREATE TABLE foreign_refs(
              node_id TEXT PRIMARY KEY NOT NULL,
              origin_group TEXT NOT NULL,
              cap_id TEXT NOT NULL,
              cap_secret TEXT NOT NULL,
              foreign_right TEXT NOT NULL
            )
            """.trimIndent(),
        )
        createCalendarLink(db)
    }

    /** Lokale Verknüpfung App-Knoten -> Android-Kalender-Event (geräte-lokal, synct NICHT). */
    private fun createCalendarLink(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS calendar_link(
              node_id TEXT PRIMARY KEY NOT NULL,
              event_id INTEGER NOT NULL,
              calendar_id INTEGER NOT NULL,
              synced_hash TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // <6 = Knoten-Baum-Umbau; <7 = erweiterbare Meta-Map + formatVersion. Beide bewusst INKOMPATIBEL
        // (Geräte re-syncen aus der frischen Gruppe; alter Stand wird nicht migriert).
        if (oldVersion < 7) {
            for (t in listOf("post_fts", "post_current", "ops", "feeds", "node_fts", "node_current", "foreign_refs", "calendar_link")) {
                db.execSQL("DROP TABLE IF EXISTS $t")
            }
            onCreate(db)
            // Beim inkompatiblen Wipe auch die Bild-/Datei-Blobs des alten Schemas mitleeren.
            BlobStore.purgeAll(appContext.filesDir)
        }
    }

    companion object {
        const val DB_NAME = "homeshare.db"
        const val DB_VERSION = 7
    }
}
