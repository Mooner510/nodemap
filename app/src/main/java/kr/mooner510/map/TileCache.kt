package kr.mooner510.map

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.webkit.WebResourceResponse
import kr.mooner510.BuildConfig
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** A vector/raster tile, addressed independently of the server's dataset version. */
data class TileKey(val source: String, val z: Int, val x: Int, val y: Int)

/**
 * On-device copy of everything the web map loads from OpenFreeMap (styles, TileJSON, sprites, glyphs, tiles).
 *
 * The WebView routes those requests through [intercept], so saved regions — and whatever was viewed while
 * online — keep rendering without a network. Tiles that belong to a saved region are kept until the region is
 * deleted; other tiles form a size-capped LRU cache.
 */
class TileCache(context: Context) {
    private val helper = Helper(context.applicationContext)
    private val db: SQLiteDatabase get() = helper.writableDatabase
    private val background = Executors.newSingleThreadExecutor()
    private val ambientInserts = AtomicInteger()

    /** Serves an OpenFreeMap request from the cache (or the network, storing the result). Null = not ours. */
    fun intercept(url: Uri, method: String): WebResourceResponse? {
        if (method != "GET" || url.scheme != "https" || url.host != HOST) return null
        val raw = url.toString()
        val key = tileKey(raw)
        return if (key != null) serveTile(raw, key) else serveResource(raw, cacheFirst = isImmutable(url))
    }

    private fun serveTile(url: String, key: TileKey): WebResourceResponse {
        val cached = readTile(key)
        if (cached != null) {
            if (System.currentTimeMillis() - cached.fetchedAt > TILE_REFRESH_MS) {
                background.execute { runCatching { fetch(url) }.getOrNull()?.let { storeTile(key, it, ambient = true) } }
            }
            return response(cached.mime, cached.data)
        }
        val fetched = runCatching { fetch(url) }.getOrNull() ?: return unavailable()
        storeTile(key, fetched, ambient = true)
        return response(fetched.mime, fetched.data)
    }

    private fun serveResource(url: String, cacheFirst: Boolean): WebResourceResponse {
        if (cacheFirst) readResource(url)?.let { return response(it.mime, it.data) }
        // Styles and TileJSON are fetched fresh so a new dataset version is picked up; the cache is the fallback.
        val fetched = runCatching { fetch(url) }.getOrNull()
        if (fetched != null) {
            if (fetched.data.isNotEmpty()) storeResource(url, fetched)
            return response(fetched.mime, fetched.data)
        }
        return readResource(url)?.let { response(it.mime, it.data) } ?: unavailable()
    }

    /** Downloads [url] into the cache unless it is already there. Used to prepare offline regions. */
    fun prefetchResource(url: String, force: Boolean = false): ByteArray {
        if (!force) readResource(url)?.let { return it.data }
        val fetched = fetch(url)
        storeResource(url, fetched)
        return fetched.data
    }

    /** Downloads one tile of a saved region. Returns the stored size in bytes. */
    fun downloadTile(template: String, key: TileKey): Int {
        val fetched = fetch(tileUrl(template, key))
        storeTile(key, fetched, ambient = false)
        return fetched.data.size
    }

    // ---- Regions ----------------------------------------------------------------------

    fun createRegion(name: String, tiles: Collection<TileKey>): Long {
        val db = db
        db.beginTransaction()
        try {
            val id = db.insertOrThrow(
                "regions",
                null,
                ContentValues().apply {
                    put("name", name)
                    put("created_at", System.currentTimeMillis())
                    put("required", tiles.size)
                },
            )
            val stmt = db.compileStatement("INSERT OR IGNORE INTO region_tiles(region_id,source,z,x,y) VALUES(?,?,?,?,?)")
            tiles.forEach {
                stmt.bindLong(1, id)
                stmt.bindString(2, it.source)
                stmt.bindLong(3, it.z.toLong())
                stmt.bindLong(4, it.x.toLong())
                stmt.bindLong(5, it.y.toLong())
                stmt.executeInsert()
            }
            db.setTransactionSuccessful()
            return id
        } finally {
            db.endTransaction()
        }
    }

    fun regionName(id: Long): String? =
        db.rawQuery("SELECT name FROM regions WHERE id=?", arrayOf(id.toString())).use { if (it.moveToFirst()) it.getString(0) else null }

    /** Tiles of the region that are not in the cache yet. */
    fun missingTiles(regionId: Long): List<TileKey> = db.rawQuery(
        "SELECT rt.source,rt.z,rt.x,rt.y FROM region_tiles rt LEFT JOIN tiles t " +
            "ON t.source=rt.source AND t.z=rt.z AND t.x=rt.x AND t.y=rt.y WHERE rt.region_id=? AND t.z IS NULL",
        arrayOf(regionId.toString()),
    ).use { c -> buildList { while (c.moveToNext()) add(TileKey(c.getString(0), c.getInt(1), c.getInt(2), c.getInt(3))) } }

    fun regions(): List<OfflineRegionInfo> = db.rawQuery(
        "SELECT r.id,r.name,r.required,COUNT(t.z),COALESCE(SUM(LENGTH(t.data)),0) FROM regions r " +
            "LEFT JOIN region_tiles rt ON rt.region_id=r.id " +
            "LEFT JOIN tiles t ON t.source=rt.source AND t.z=rt.z AND t.x=rt.x AND t.y=rt.y " +
            "GROUP BY r.id ORDER BY r.created_at DESC",
        null,
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(OfflineRegionInfo(c.getLong(0), c.getString(1), c.getLong(3), c.getLong(2), c.getLong(4)))
        }
    }

    /** Removes the region and the tiles no other region needs, then gives the space back to the OS. */
    fun deleteRegion(id: Long): Boolean {
        val db = db
        db.beginTransaction()
        val deleted = try {
            db.execSQL(
                "DELETE FROM tiles WHERE (source,z,x,y) IN (" +
                    "SELECT source,z,x,y FROM region_tiles WHERE region_id=? " +
                    "EXCEPT SELECT source,z,x,y FROM region_tiles WHERE region_id<>?)",
                arrayOf(id, id),
            )
            db.delete("region_tiles", "region_id=?", arrayOf(id.toString()))
            val rows = db.delete("regions", "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
            rows > 0
        } finally {
            db.endTransaction()
        }
        reclaimSpace()
        return deleted
    }

    // ---- Storage ----------------------------------------------------------------------

    private class Entry(val mime: String, val data: ByteArray, val fetchedAt: Long)

    private class Fetched(val mime: String, val data: ByteArray)

    private fun readTile(key: TileKey): Entry? = db.rawQuery(
        "SELECT mime,data,fetched_at,used_at FROM tiles WHERE source=? AND z=? AND x=? AND y=?",
        arrayOf(key.source, key.z.toString(), key.x.toString(), key.y.toString()),
    ).use { c ->
        if (!c.moveToFirst()) return null
        val now = System.currentTimeMillis()
        if (now - c.getLong(3) > TOUCH_INTERVAL_MS) {
            background.execute {
                db.execSQL(
                    "UPDATE tiles SET used_at=? WHERE source=? AND z=? AND x=? AND y=?",
                    arrayOf<Any>(now, key.source, key.z, key.x, key.y),
                )
            }
        }
        Entry(c.getString(0), c.getBlob(1), c.getLong(2))
    }

    private fun storeTile(key: TileKey, fetched: Fetched, ambient: Boolean) {
        val now = System.currentTimeMillis()
        db.insertWithOnConflict(
            "tiles",
            null,
            ContentValues().apply {
                put("source", key.source)
                put("z", key.z)
                put("x", key.x)
                put("y", key.y)
                put("mime", fetched.mime)
                put("data", fetched.data)
                put("fetched_at", now)
                put("used_at", now)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        if (ambient && ambientInserts.incrementAndGet() % TRIM_EVERY == 0) background.execute { trimAmbient() }
    }

    private fun readResource(url: String): Entry? =
        db.rawQuery("SELECT mime,data,fetched_at FROM resources WHERE url=?", arrayOf(url)).use { c ->
            if (c.moveToFirst()) Entry(c.getString(0), c.getBlob(1), c.getLong(2)) else null
        }

    private fun storeResource(url: String, fetched: Fetched) {
        db.insertWithOnConflict(
            "resources",
            null,
            ContentValues().apply {
                put("url", url)
                put("mime", fetched.mime)
                put("data", fetched.data)
                put("fetched_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** Drops the least recently used tiles that no saved region owns once they exceed [AMBIENT_LIMIT_BYTES]. */
    private fun trimAmbient() = runCatching {
        val unowned = "NOT EXISTS (SELECT 1 FROM region_tiles rt WHERE rt.source=t.source AND rt.z=t.z AND rt.x=t.x AND rt.y=t.y)"
        val rows = db.rawQuery("SELECT t.rowid,LENGTH(t.data) FROM tiles t WHERE $unowned ORDER BY t.used_at DESC", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0) to c.getLong(1)) }
        }
        var total = 0L
        val evict = rows.filter { (_, size) -> total += size; total > AMBIENT_LIMIT_BYTES }.map { it.first }
        if (evict.isEmpty()) return@runCatching
        evict.chunked(500).forEach { chunk -> db.execSQL("DELETE FROM tiles WHERE rowid IN (${chunk.joinToString(",")})") }
        reclaimSpace()
    }

    private fun reclaimSpace() {
        runCatching { db.rawQuery("PRAGMA incremental_vacuum", null).use { while (it.moveToNext()) Unit } }
    }

    // ---- Network ----------------------------------------------------------------------

    private fun fetch(url: String): Fetched {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "NodeMap/${BuildConfig.VERSION_NAME} (Android)")
        }
        try {
            val code = connection.responseCode
            val mime = connection.contentType?.substringBefore(';')?.trim().orEmpty().ifEmpty { "application/octet-stream" }
            return when (code) {
                // Tiles outside the data (open sea…) come back empty; keep them so they count as saved.
                HttpURLConnection.HTTP_NO_CONTENT, HttpURLConnection.HTTP_NOT_FOUND -> Fetched(mime, ByteArray(0))
                in 200..299 -> Fetched(mime, connection.inputStream.use { it.readBytes() })
                else -> throw IOException("HTTP $code")
            }
        } catch (e: FileNotFoundException) {
            throw IOException(e)
        } finally {
            connection.disconnect()
        }
    }

    private fun response(mime: String, data: ByteArray): WebResourceResponse {
        val text = mime.startsWith("application/json") || mime.startsWith("text/")
        return if (data.isEmpty()) {
            WebResourceResponse(mime, null, 204, "No Content", CORS_HEADERS, ByteArrayInputStream(data))
        } else {
            WebResourceResponse(mime, if (text) "utf-8" else null, 200, "OK", CORS_HEADERS, ByteArrayInputStream(data))
        }
    }

    private fun unavailable() =
        WebResourceResponse("text/plain", "utf-8", 504, "Offline", CORS_HEADERS, ByteArrayInputStream(ByteArray(0)))

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            // Only takes effect on a new, empty database; lets deletions shrink the file.
            db.execSQL("PRAGMA auto_vacuum=INCREMENTAL")
            db.enableWriteAheadLogging()
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE tiles (source TEXT NOT NULL,z INTEGER NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,mime TEXT NOT NULL,data BLOB NOT NULL,fetched_at INTEGER NOT NULL,used_at INTEGER NOT NULL,PRIMARY KEY(source,z,x,y))")
            db.execSQL("CREATE INDEX idx_tiles_used ON tiles(used_at)")
            db.execSQL("CREATE TABLE resources (url TEXT PRIMARY KEY,mime TEXT NOT NULL,data BLOB NOT NULL,fetched_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE regions (id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,created_at INTEGER NOT NULL,required INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE region_tiles (region_id INTEGER NOT NULL,source TEXT NOT NULL,z INTEGER NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,PRIMARY KEY(region_id,source,z,x,y)) WITHOUT ROWID")
            db.execSQL("CREATE INDEX idx_region_tiles_tile ON region_tiles(source,z,x,y)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    companion object {
        const val HOST = "tiles.openfreemap.org"
        private const val DB_NAME = "offline-tiles.db"
        private const val DB_VERSION = 1
        private const val AMBIENT_LIMIT_BYTES = 200L * 1024 * 1024
        private const val TRIM_EVERY = 200
        private const val TOUCH_INTERVAL_MS = 24L * 60 * 60 * 1000
        private const val TILE_REFRESH_MS = 30L * 24 * 60 * 60 * 1000
        private val CORS_HEADERS = mapOf("Access-Control-Allow-Origin" to "*")

        // e.g. /planet/20261018_080001_pt/14/13970/6344.pbf or /natural_earth/ne2sr/3/6/3.png
        private val TILE_PATH = Regex("""^/([^/]+)/([^/]+)/(\d+)/(\d+)/(\d+)\.(pbf|mvt|png|jpg|webp)$""")
        private val DATASET_VERSION = Regex("""^\d{8}_\d{6}_\w+$""")

        /**
         * The cache key of a tile URL. The dataset version in the path is dropped so tiles saved from an older
         * TileJSON still answer requests after OpenFreeMap publishes a new version (and vice versa).
         */
        fun tileKey(url: String): TileKey? {
            val uri = Uri.parse(url)
            if (uri.host != HOST) return null
            val m = TILE_PATH.matchEntire(uri.path ?: return null) ?: return null
            val (name, sub, z, x, y) = m.destructured
            val source = if (DATASET_VERSION.matches(sub)) name else "$name/$sub"
            return TileKey(source, z.toInt(), x.toInt(), y.toInt())
        }

        fun tileUrl(template: String, key: TileKey): String =
            template.replace("{z}", key.z.toString()).replace("{x}", key.x.toString()).replace("{y}", key.y.toString())

        // Sprites and glyphs live under versioned/immutable paths; styles and TileJSON can change.
        private fun isImmutable(url: Uri): Boolean {
            val path = url.path.orEmpty()
            return path.startsWith("/sprites/") || path.startsWith("/fonts/")
        }
    }
}
