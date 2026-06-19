package de.beardedskunk.homeshare.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Lokale Persistenz auf Basis der Framework-SQLite (kein Room/KSP).
 *
 * Quelle der Wahrheit ist der **Op-Log** (`ops`): unveraenderliche Versionsknoten
 * des git-artigen DAG. `post_current` ist nur ein aus dem Log abgeleiteter,
 * materialisierter Cache des aktuellen Stands fuer schnelle Listen/Suche.
 * `post_fts` (FTS4) indiziert den Text des aktuellen Stands fuer die Suche.
 */
class Db(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE feeds(
              feed_id TEXT PRIMARY KEY NOT NULL,
              name TEXT NOT NULL,
              created_wall INTEGER NOT NULL,
              created_counter INTEGER NOT NULL,
              deleted INTEGER NOT NULL DEFAULT 0,
              calendar INTEGER NOT NULL DEFAULT 0,
              shared INTEGER NOT NULL DEFAULT 0,
              foreign_origin TEXT NOT NULL DEFAULT '',
              cap_id TEXT NOT NULL DEFAULT '',
              cap_secret TEXT NOT NULL DEFAULT '',
              foreign_right TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE ops(
              version_id TEXT PRIMARY KEY NOT NULL,
              feed_id TEXT NOT NULL,
              post_id TEXT NOT NULL,
              device_id TEXT NOT NULL,
              seq INTEGER NOT NULL,
              hlc_wall INTEGER NOT NULL,
              hlc_counter INTEGER NOT NULL,
              parents TEXT NOT NULL,
              deleted INTEGER NOT NULL,
              text TEXT NOT NULL,
              image_hashes TEXT NOT NULL,
              image_titles TEXT NOT NULL DEFAULT '',
              device_name TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_ops_post ON ops(post_id)")
        db.execSQL("CREATE INDEX idx_ops_feed ON ops(feed_id)")
        db.execSQL("CREATE INDEX idx_ops_device_seq ON ops(device_id, seq)")
        db.execSQL(
            """
            CREATE TABLE post_current(
              post_id TEXT PRIMARY KEY NOT NULL,
              feed_id TEXT NOT NULL,
              head_version_id TEXT NOT NULL,
              text TEXT NOT NULL,
              image_hashes TEXT NOT NULL,
              image_titles TEXT NOT NULL DEFAULT '',
              deleted INTEGER NOT NULL,
              conflicted INTEGER NOT NULL,
              created_wall INTEGER NOT NULL,
              created_counter INTEGER NOT NULL,
              updated_wall INTEGER NOT NULL,
              updated_counter INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_post_current_feed ON post_current(feed_id)")
        db.execSQL("CREATE VIRTUAL TABLE post_fts USING fts4(post_id, text, notindexed=post_id)")
        createCalendarLink(db)
    }

    /** Lokale Verknüpfung App-Post -> Android-Kalender-Event (geräte-lokal, synct NICHT). */
    private fun createCalendarLink(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS calendar_link(
              post_id TEXT PRIMARY KEY NOT NULL,
              event_id INTEGER NOT NULL,
              calendar_id INTEGER NOT NULL,
              synced_hash TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Inkrementelle, nicht-destruktive Migrationen (der Op-Log bleibt erhalten).
        if (oldVersion < 3) {
            // Vor v3: altes Schema -> einmalig neu aufbauen (Daten re-syncen via Peers/Box).
            db.execSQL("DROP TABLE IF EXISTS post_fts")
            db.execSQL("DROP TABLE IF EXISTS post_current")
            db.execSQL("DROP TABLE IF EXISTS ops")
            db.execSQL("DROP TABLE IF EXISTS feeds")
            onCreate(db)
            return
        }
        if (oldVersion < 4) {
            // v3 -> v4: Kalender-Feature. Spalte + lokale Verknüpfungstabelle ergänzen.
            db.execSQL("ALTER TABLE feeds ADD COLUMN calendar INTEGER NOT NULL DEFAULT 0")
            createCalendarLink(db)
        }
        if (oldVersion < 5) {
            // v4 -> v5: Feed-Sharing (#10). Markierungen + Fremdfeed-Ablage.
            db.execSQL("ALTER TABLE feeds ADD COLUMN shared INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE feeds ADD COLUMN foreign_origin TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE feeds ADD COLUMN cap_id TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE feeds ADD COLUMN cap_secret TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE feeds ADD COLUMN foreign_right TEXT NOT NULL DEFAULT ''")
        }
    }

    companion object {
        const val DB_NAME = "homeshare.db"
        const val DB_VERSION = 5
    }
}
