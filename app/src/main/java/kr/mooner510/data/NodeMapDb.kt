package kr.mooner510.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class NodeMapDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE track_points (id INTEGER PRIMARY KEY AUTOINCREMENT,timestamp INTEGER NOT NULL,day_key TEXT NOT NULL,payload BLOB NOT NULL)")
        db.execSQL("CREATE INDEX idx_track_day_time ON track_points(day_key,timestamp)")
        db.execSQL("CREATE TABLE events (id TEXT PRIMARY KEY,timestamp INTEGER NOT NULL,day_key TEXT NOT NULL,type TEXT NOT NULL,pin_rule_id TEXT,pin_type_id TEXT,payload BLOB NOT NULL)")
        db.execSQL("CREATE INDEX idx_events_day_time ON events(day_key,timestamp)")
        db.execSQL("CREATE INDEX idx_events_rule_time ON events(pin_rule_id,timestamp)")
        db.execSQL("CREATE TABLE attachments (id TEXT PRIMARY KEY,event_id TEXT NOT NULL,kind TEXT NOT NULL,mime_type TEXT,encrypted_path TEXT NOT NULL DEFAULT '',external_uri TEXT,created_at INTEGER NOT NULL,FOREIGN KEY(event_id) REFERENCES events(id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX idx_attachments_event ON attachments(event_id)")
        db.execSQL("CREATE TABLE notification_rules (id TEXT PRIMARY KEY,created_at INTEGER NOT NULL,payload BLOB NOT NULL)")
        db.execSQL("CREATE TABLE pin_templates (id TEXT PRIMARY KEY,created_at INTEGER NOT NULL,payload BLOB NOT NULL)")
        createPinConfigurationTables(db)
        db.execSQL("CREATE TABLE geocode_cache (cache_key TEXT PRIMARY KEY,updated_at INTEGER NOT NULL,payload BLOB NOT NULL)")
        db.execSQL("CREATE TABLE state (key TEXT PRIMARY KEY,value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 1) {
            onCreate(db)
            return
        }
        if (oldVersion < 2) {
            runCatching { db.execSQL("ALTER TABLE events ADD COLUMN pin_rule_id TEXT") }
            runCatching { db.execSQL("ALTER TABLE events ADD COLUMN pin_type_id TEXT") }
            runCatching { db.execSQL("CREATE INDEX idx_events_rule_time ON events(pin_rule_id,timestamp)") }
            createPinConfigurationTables(db)
        }
    }

    private fun createPinConfigurationTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS pin_types (id TEXT PRIMARY KEY,created_at INTEGER NOT NULL,payload BLOB NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS pin_rules (id TEXT PRIMARY KEY,created_at INTEGER NOT NULL,source TEXT NOT NULL,is_system INTEGER NOT NULL,enabled INTEGER NOT NULL,hidden INTEGER NOT NULL,pin_type_id TEXT NOT NULL,priority INTEGER NOT NULL,payload BLOB NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pin_rules_source_priority ON pin_rules(source,enabled,priority)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pin_rules_type ON pin_rules(pin_type_id)")
    }

    fun <T> Cursor.mapRows(mapper: (Cursor) -> T): List<T> = buildList {
        use { c -> while (c.moveToNext()) add(mapper(c)) }
    }

    companion object {
        const val DB_NAME = "nodemap.db"
        const val DB_VERSION = 2
    }
}
