package de.beardedskunk.homeshare.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Lokale Persistenz (Framework-SQLite, kein Room).
 *
 * Quelle der Wahrheit ist der **Op-Log** (`ops`): unveränderliche Versionsknoten des git-artigen
 * DAG – jetzt für einen **Knoten-Baum** (jeder Knoten = Feed/Eintrag/Bild/Datei/Termin/Todo).
 * `node_current` ist der materialisierte aktuelle Stand je Knoten (Cache für Listen/Suche),
 * `node_fts` (FTS4) indiziert den Text. `foreign_refs` hält abonnierte Fremd-Knoten (Cross-Group),
 * `calendar_link` die geräte-lokale Verknüpfung Knoten→Android-Kalender-Event.
 *
 * `root_id` an jeder Op = oberster Vorfahr-Knoten (Feed) → erlaubt den feed-/subtree-bezogenen
 * Sync (#10) ohne rekursive Baumtraversierung. Für Root-Knoten gilt root_id == node_id.
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
              color INTEGER,
              child_default TEXT NOT NULL DEFAULT '',
              tags TEXT NOT NULL DEFAULT '',
              blob_hash TEXT NOT NULL DEFAULT '',
              file_name TEXT NOT NULL DEFAULT '',
              mime TEXT NOT NULL DEFAULT '',
              done INTEGER NOT NULL DEFAULT 0,
              text TEXT NOT NULL,
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
              done INTEGER NOT NULL DEFAULT 0,
              blob_hash TEXT NOT NULL DEFAULT '',
              file_name TEXT NOT NULL DEFAULT '',
              mime TEXT NOT NULL DEFAULT '',
              color INTEGER,
              child_default TEXT NOT NULL DEFAULT '',
              tags TEXT NOT NULL DEFAULT '',
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
        // v6 = Knoten-Baum-Umbau: bewusst INKOMPATIBEL. Alles wegwerfen + neu (Geräte re-syncen
        // ohnehin aus der frischen Gruppe; der alte Feed/Post-Schema-Stand wird nicht migriert).
        if (oldVersion < 6) {
            db.execSQL("DROP TABLE IF EXISTS post_fts")
            db.execSQL("DROP TABLE IF EXISTS post_current")
            db.execSQL("DROP TABLE IF EXISTS ops")
            db.execSQL("DROP TABLE IF EXISTS feeds")
            db.execSQL("DROP TABLE IF EXISTS node_fts")
            db.execSQL("DROP TABLE IF EXISTS node_current")
            db.execSQL("DROP TABLE IF EXISTS foreign_refs")
            db.execSQL("DROP TABLE IF EXISTS calendar_link")
            onCreate(db)
            // Beim inkompatiblen Wipe auch die Bild-/Datei-Blobs des alten Schemas mitleeren,
            // sonst bleiben sie als verwaiste Dateien liegen (toter Speicher).
            BlobStore.purgeAll(appContext.filesDir)
        }
    }

    companion object {
        const val DB_NAME = "homeshare.db"
        const val DB_VERSION = 6
    }
}
