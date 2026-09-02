package kr.mooner510.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

class NodeMapRepository(
    private val db: NodeMapDb,
    private val crypto: CryptoManager,
    val attachmentStore: AttachmentStore,
) {
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    val changes = _changes.asSharedFlow()

    suspend fun insertTrackPoint(point: TrackPoint): Long = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("timestamp", point.timestamp)
            put("day_key", dayKey(point.timestamp))
            put("payload", crypto.encrypt(point.toJson().toString().toByteArray()))
        }
        db.writableDatabase.insertOrThrow("track_points", null, values).also {
            _changes.tryEmit(Unit)
        }
    }

    suspend fun trackPointsForDay(date: LocalDate): List<TrackPoint> = withContext(Dispatchers.IO) {
        val cursor = db.readableDatabase.query(
            "track_points",
            arrayOf("id", "timestamp", "payload"),
            "day_key = ?",
            arrayOf(date.toString()),
            null,
            null,
            "timestamp ASC",
        )
        db.run {
            cursor.mapRows {
                decodeTrackPoint(it.getLong(0), it.getLong(1), it.getBlob(2))
            }
        }
    }

    suspend fun allTrackPoints(): List<TrackPoint> = withContext(Dispatchers.IO) {
        val cursor = db.readableDatabase.query(
            "track_points",
            arrayOf("id", "timestamp", "payload"),
            null,
            null,
            null,
            null,
            "timestamp ASC",
        )
        db.run {
            cursor.mapRows {
                decodeTrackPoint(it.getLong(0), it.getLong(1), it.getBlob(2))
            }
        }
    }

    suspend fun latestTrackPoint(): TrackPoint? = withContext(Dispatchers.IO) {
        db.readableDatabase.query(
            "track_points",
            arrayOf("id", "timestamp", "payload"),
            null,
            null,
            null,
            null,
            "timestamp DESC",
            "1",
        ).use {
            if (!it.moveToFirst()) null
            else decodeTrackPoint(it.getLong(0), it.getLong(1), it.getBlob(2))
        }
    }

    suspend fun nearestTrackPoint(timestamp: Long, maxDeltaMs: Long = 5 * 60_000L): TrackPoint? =
        withContext(Dispatchers.IO) {
            db.readableDatabase.rawQuery(
                "SELECT id,timestamp,payload FROM track_points WHERE timestamp BETWEEN ? AND ? ORDER BY ABS(timestamp - ?) ASC LIMIT 1",
                arrayOf(
                    (timestamp - maxDeltaMs).toString(),
                    (timestamp + maxDeltaMs).toString(),
                    timestamp.toString(),
                ),
            ).use {
                if (!it.moveToFirst()) null
                else decodeTrackPoint(it.getLong(0), it.getLong(1), it.getBlob(2))
            }
        }

    suspend fun insertEvent(event: TimelineEvent) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("id", event.id)
            put("timestamp", event.timestamp)
            put("day_key", dayKey(event.timestamp))
            put("type", event.type.name)
            put("payload", crypto.encrypt(event.toJson().toString().toByteArray()))
        }
        db.writableDatabase.insertWithOnConflict(
            "events",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        _changes.tryEmit(Unit)
    }

    suspend fun updateEvent(event: TimelineEvent) = insertEvent(event)

    suspend fun eventsForDay(date: LocalDate): List<TimelineEvent> = withContext(Dispatchers.IO) {
        queryEvents("day_key = ?", arrayOf(date.toString()))
    }

    suspend fun allEvents(): List<TimelineEvent> = withContext(Dispatchers.IO) {
        queryEvents(null, null)
    }

    suspend fun getEvent(id: String): TimelineEvent? = withContext(Dispatchers.IO) {
        queryEvents("id = ?", arrayOf(id)).firstOrNull()
    }

    suspend fun addEncryptedAttachment(
        eventId: String,
        kind: String,
        mimeType: String?,
        content: java.io.InputStream,
        attachmentId: String = UUID.randomUUID().toString(),
    ): AttachmentRecord = withContext(Dispatchers.IO) {
        val record = AttachmentRecord(
            attachmentId,
            eventId,
            kind,
            mimeType,
            attachmentStore.put(content, attachmentId),
            System.currentTimeMillis(),
        )
        insertAttachmentRecord(record)
        record
    }

    suspend fun addExternalAttachment(
        eventId: String,
        kind: String,
        mimeType: String?,
        uri: String,
        attachmentId: String = UUID.randomUUID().toString(),
    ): AttachmentRecord = withContext(Dispatchers.IO) {
        val record = AttachmentRecord(
            attachmentId,
            eventId,
            kind,
            mimeType,
            "",
            System.currentTimeMillis(),
            uri,
        )
        insertAttachmentRecord(record)
        record
    }

    suspend fun attachmentsForEvent(eventId: String): List<AttachmentRecord> = withContext(Dispatchers.IO) {
        queryAttachments("event_id = ?", arrayOf(eventId))
    }

    suspend fun allAttachments(): List<AttachmentRecord> = withContext(Dispatchers.IO) {
        queryAttachments(null, null)
    }

    suspend fun insertAttachmentRecord(record: AttachmentRecord) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("id", record.id)
            put("event_id", record.eventId)
            put("kind", record.kind)
            put("mime_type", record.mimeType)
            put("encrypted_path", record.encryptedPath)
            put("external_uri", record.externalUri)
            put("created_at", record.createdAt)
        }
        db.writableDatabase.insertWithOnConflict(
            "attachments",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        _changes.tryEmit(Unit)
    }

    suspend fun notificationRules(): List<NotificationRule> = withContext(Dispatchers.IO) {
        val cursor = db.readableDatabase.query(
            "notification_rules",
            arrayOf("id", "payload"),
            null,
            null,
            null,
            null,
            "created_at ASC",
        )
        db.run {
            cursor.mapRows {
                NotificationRule.fromJson(it.getString(0), decryptJson(it.getBlob(1)))
            }
        }
    }

    suspend fun upsertNotificationRule(rule: NotificationRule) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("id", rule.id)
            put("created_at", System.currentTimeMillis())
            put("payload", crypto.encrypt(rule.toJson().toString().toByteArray()))
        }
        db.writableDatabase.insertWithOnConflict(
            "notification_rules",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        _changes.tryEmit(Unit)
    }

    suspend fun deleteNotificationRule(id: String) = withContext(Dispatchers.IO) {
        db.writableDatabase.delete("notification_rules", "id = ?", arrayOf(id))
        _changes.tryEmit(Unit)
    }

    suspend fun matchingNotificationRules(
        packageName: String,
        searchableText: String,
    ): List<NotificationRule> = notificationRules().filter { rule ->
        if (!rule.enabled || packageName !in rule.packageNames) return@filter false

        val excluded = rule.excludeRegex
            .takeIf { it.isNotBlank() }
            ?.let { pattern ->
                runCatching { Regex(pattern).containsMatchIn(searchableText) }.getOrDefault(false)
            }
            ?: false
        if (excluded) return@filter false

        val include = rule.includeRegex.takeIf { it.isNotBlank() } ?: return@filter true
        runCatching { Regex(include).containsMatchIn(searchableText) }.getOrDefault(false)
    }

    suspend fun pinTemplates(): List<PinTemplate> = withContext(Dispatchers.IO) {
        val cursor = db.readableDatabase.query(
            "pin_templates",
            arrayOf("id", "payload"),
            null,
            null,
            null,
            null,
            "created_at ASC",
        )
        db.run {
            cursor.mapRows {
                PinTemplate.fromJson(it.getString(0), decryptJson(it.getBlob(1)))
            }
        }
    }

    suspend fun getPinTemplate(id: String): PinTemplate? = pinTemplates().firstOrNull { it.id == id }

    suspend fun upsertPinTemplate(template: PinTemplate) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("id", template.id)
            put("created_at", System.currentTimeMillis())
            put("payload", crypto.encrypt(template.toJson().toString().toByteArray()))
        }
        db.writableDatabase.insertWithOnConflict(
            "pin_templates",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        _changes.tryEmit(Unit)
    }

    suspend fun deletePinTemplate(id: String) = withContext(Dispatchers.IO) {
        db.writableDatabase.delete("pin_templates", "id = ?", arrayOf(id))
        _changes.tryEmit(Unit)
    }

    suspend fun getGeocodeCache(key: String): PlaceLabel? = withContext(Dispatchers.IO) {
        db.readableDatabase.query(
            "geocode_cache",
            arrayOf("payload"),
            "cache_key = ?",
            arrayOf(key),
            null,
            null,
            null,
            "1",
        ).use {
            if (!it.moveToFirst()) null else PlaceLabel.fromJson(decryptJson(it.getBlob(0)))
        }
    }

    suspend fun putGeocodeCache(key: String, place: PlaceLabel) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("cache_key", key)
            put("updated_at", System.currentTimeMillis())
            put("payload", crypto.encrypt(place.toJson().toString().toByteArray()))
        }
        db.writableDatabase.insertWithOnConflict(
            "geocode_cache",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    suspend fun state(key: String): String? = withContext(Dispatchers.IO) {
        db.readableDatabase.query(
            "state",
            arrayOf("value"),
            "key = ?",
            arrayOf(key),
            null,
            null,
            null,
            "1",
        ).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    suspend fun setState(key: String, value: String) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        db.writableDatabase.insertWithOnConflict(
            "state",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    suspend fun clearForRestore() = withContext(Dispatchers.IO) {
        val writable = db.writableDatabase
        writable.beginTransaction()
        try {
            listOf(
                "attachments",
                "events",
                "track_points",
                "notification_rules",
                "pin_templates",
                "geocode_cache",
                "state",
            ).forEach { table -> writable.delete(table, null, null) }
            writable.setTransactionSuccessful()
        } finally {
            writable.endTransaction()
        }
        _changes.tryEmit(Unit)
    }

    private fun queryEvents(selection: String?, args: Array<String>?): List<TimelineEvent> {
        val cursor = db.readableDatabase.query(
            "events",
            arrayOf("id", "timestamp", "type", "payload"),
            selection,
            args,
            null,
            null,
            "timestamp ASC",
        )
        return db.run {
            cursor.mapRows { row ->
                val id = row.getString(0)
                TimelineEvent.fromJson(
                    id,
                    row.getLong(1),
                    EventType.valueOf(row.getString(2)),
                    decryptJson(row.getBlob(3)),
                ).copy(
                    attachmentIds = queryAttachments("event_id = ?", arrayOf(id)).map { it.id },
                )
            }
        }
    }

    private fun queryAttachments(selection: String?, args: Array<String>?): List<AttachmentRecord> {
        val cursor = db.readableDatabase.query(
            "attachments",
            arrayOf("id", "event_id", "kind", "mime_type", "encrypted_path", "created_at", "external_uri"),
            selection,
            args,
            null,
            null,
            "created_at ASC",
        )
        return db.run {
            cursor.mapRows {
                AttachmentRecord(
                    it.getString(0),
                    it.getString(1),
                    it.getString(2),
                    it.strOrNull(3),
                    it.getString(4),
                    it.getLong(5),
                    it.strOrNull(6),
                )
            }
        }
    }

    private fun decodeTrackPoint(id: Long, timestamp: Long, payload: ByteArray): TrackPoint =
        TrackPoint.fromJson(id, timestamp, decryptJson(payload))

    private fun decryptJson(payload: ByteArray): JSONObject =
        JSONObject(crypto.decrypt(payload).toString(Charsets.UTF_8))
}

private fun android.database.Cursor.strOrNull(index: Int): String? =
    if (isNull(index)) null else getString(index)
