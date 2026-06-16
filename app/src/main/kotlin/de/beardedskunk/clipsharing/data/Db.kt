package de.beardedskunk.clipsharing.data

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
              deleted INTEGER NOT NULL DEFAULT 0
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
              image_hashes TEXT NOT NULL
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Vor Release 1.0 noch keine Migrationen -> bei Schemaaenderung neu aufbauen.
        db.execSQL("DROP TABLE IF EXISTS post_fts")
        db.execSQL("DROP TABLE IF EXISTS post_current")
        db.execSQL("DROP TABLE IF EXISTS ops")
        db.execSQL("DROP TABLE IF EXISTS feeds")
        onCreate(db)
    }

    companion object {
        const val DB_NAME = "clipsharing.db"
        const val DB_VERSION = 1
    }
}
