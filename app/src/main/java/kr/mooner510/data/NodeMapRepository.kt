package kr.mooner510.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val pinInitMutex = Mutex()
    @Volatile private var pinConfigurationReady = false

    suspend fun insertTrackPoint(point: TrackPoint): Long = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("timestamp", point.timestamp)
            put("day_key", dayKey(point.timestamp))
            put("payload", crypto.encrypt(point.toJson().toString().toByteArray()))
        }
        db.writableDatabase.insertOrThrow("track_points", null, values).also { _changes.tryEmit(Unit) }
    }

    suspend fun trackPointsForDay(date: LocalDate): List<TrackPoint> = withContext(Dispatchers.IO) {
        queryTrackPoints("day_key = ?", arrayOf(date.toString()))
    }

    suspend fun trackPointsBetween(startInclusive: Long, endExclusive: Long): List<TrackPoint> =
        withContext(Dispatchers.IO) {
            queryTrackPoints(
                "timestamp >= ? AND timestamp < ?",
                arrayOf(startInclusive.toString(), endExclusive.toString()),
            )
        }

    suspend fun allTrackPoints(): List<TrackPoint> = withContext(Dispatchers.IO) {
        queryTrackPoints(null, null)
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
            if (!it.moveToFirst()) null else decodeTrackPoint(it.getLong(0), it.getLong(1), it.getBlob(2))
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
                if (!it.moveToFirst()) null else decodeTrackPoint(it.getLong(0), it.getLong(1), it.getBlob(2))
            }
        }

    suspend fun dataDays(): List<LocalDate> = withContext(Dispatchers.IO) {
        queryDays("SELECT day_key FROM (SELECT day_key FROM track_points UNION SELECT day_key FROM events) ORDER BY day_key DESC")
    }

    suspend fun trackDays(): List<LocalDate> = withContext(Dispatchers.IO) {
        queryDays("SELECT DISTINCT day_key FROM track_points ORDER BY day_key DESC")
    }

    suspend fun insertEvent(event: TimelineEvent) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("id", event.id)
            put("timestamp", event.timestamp)
            put("day_key", dayKey(event.timestamp))
            put("type", event.type.name)
            put("pin_rule_id", event.pinRuleId)
            put("pin_type_id", event.pinTypeId)
            put("payload", crypto.encrypt(event.toJson().toString().toByteArray()))
        }
        db.writableDatabase.insertWithOnConflict("events", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        _changes.tryEmit(Unit)
    }

    suspend fun updateEvent(event: TimelineEvent) = insertEvent(event)

    suspend fun eventsForDay(date: LocalDate): List<TimelineEvent> = withContext(Dispatchers.IO) {
        queryEvents("day_key = ?", arrayOf(date.toString()))
    }

    suspend fun eventsBetween(startInclusive: Long, endExclusive: Long): List<TimelineEvent> =
        withContext(Dispatchers.IO) {
            queryEvents(
                "timestamp >= ? AND timestamp < ?",
                arrayOf(startInclusive.toString(), endExclusive.toString()),
            )
        }

    suspend fun allEvents(): List<TimelineEvent> = withContext(Dispatchers.IO) { queryEvents(null, null) }

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
        val record = AttachmentRecord(attachmentId, eventId, kind, mimeType, "", System.currentTimeMillis(), uri)
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
        db.writableDatabase.insertWithOnConflict("attachments", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        _changes.tryEmit(Unit)
    }

    // ---- Pin types / rules -------------------------------------------------

    suspend fun pinTypes(): List<PinType> {
        ensurePinConfiguration()
        return withContext(Dispatchers.IO) { queryPinTypes() }
    }

    suspend fun getPinType(id: String): PinType? = pinTypes().firstOrNull { it.id == id }

    suspend fun upsertPinType(type: PinType) {
        ensurePinConfiguration()
        withContext(Dispatchers.IO) { writePinType(type) }
        _changes.tryEmit(Unit)
    }

    suspend fun pinTypeUsageCount(id: String): Long {
        ensurePinConfiguration()
        return withContext(Dispatchers.IO) {
            scalarLong("SELECT COUNT(*) FROM pin_rules WHERE pin_type_id = ?", arrayOf(id)) +
                scalarLong("SELECT COUNT(*) FROM events WHERE pin_type_id = ?", arrayOf(id))
        }
    }

    suspend fun deletePinType(id: String): Boolean {
        ensurePinConfiguration()
        if (id.startsWith("system:type:") || pinTypeUsageCount(id) > 0) return false
        return withContext(Dispatchers.IO) {
            db.writableDatabase.delete("pin_types", "id = ?", arrayOf(id)) > 0
        }.also { if (it) _changes.tryEmit(Unit) }
    }

    suspend fun pinRules(): List<PinRule> {
        ensurePinConfiguration()
        return withContext(Dispatchers.IO) { queryPinRules() }
    }

    suspend fun pinRules(source: PinRuleSource): List<PinRule> =
        pinRules().filter { it.source == source }.sortedBy { it.priority }

    suspend fun getPinRule(id: String): PinRule? = pinRules().firstOrNull { it.id == id }

    suspend fun upsertPinRule(rule: PinRule) {
        ensurePinConfiguration()
        val normalized = if (rule.enabled) rule.copy(hidden = false) else rule
        withContext(Dispatchers.IO) { writePinRule(normalized) }
        _changes.tryEmit(Unit)
    }

    suspend fun pinRuleUsageCount(id: String): Long {
        ensurePinConfiguration()
        return withContext(Dispatchers.IO) {
            val direct = scalarLong("SELECT COUNT(*) FROM events WHERE pin_rule_id = ?", arrayOf(id))
            if (direct > 0) return@withContext direct
            when (id) {
                SYSTEM_RULE_PHONE_CALL -> scalarLong("SELECT COUNT(*) FROM events WHERE type = ?", arrayOf(EventType.PHONE_CALL.name))
                SYSTEM_RULE_SMS -> scalarLong("SELECT COUNT(*) FROM events WHERE type = ?", arrayOf(EventType.SMS.name))
                SYSTEM_RULE_MMS -> scalarLong("SELECT COUNT(*) FROM events WHERE type = ?", arrayOf(EventType.MMS.name))
                else -> queryEvents(null, null).count { effectiveRuleId(it) == id }.toLong()
            }
        }
    }

    suspend fun deletePinRule(id: String): Boolean {
        ensurePinConfiguration()
        val rule = getPinRule(id) ?: return false
        if (rule.system || pinRuleUsageCount(id) > 0) return false
        return withContext(Dispatchers.IO) {
            db.writableDatabase.delete("pin_rules", "id = ?", arrayOf(id)) > 0
        }.also { if (it) _changes.tryEmit(Unit) }
    }

    suspend fun selectPinRule(source: PinRuleSource, context: PinRuleMatchContext): PinRule? {
        val rules = pinRules(source)
            .asSequence()
            .filter { it.enabled }
            .sortedBy { it.priority }
        return rules.firstOrNull { ruleMatches(it, context) }
    }

    suspend fun resolvedPinsBetween(
        startInclusive: Long,
        endExclusive: Long,
        includeHidden: Boolean = false,
    ): List<ResolvedPin> {
        ensurePinConfiguration()
        val events = eventsBetween(startInclusive, endExclusive)
        val rules = pinRules().associateBy { it.id }
        val types = pinTypes().associateBy { it.id }
        val general = types[SYSTEM_TYPE_GENERAL] ?: defaultGeneralType()
        return events.mapNotNull { event ->
            val rule = effectiveRuleId(event)?.let(rules::get)
            if (!includeHidden && rule?.hidden == true) return@mapNotNull null
            val type = types[rule?.pinTypeId ?: event.pinTypeId ?: SYSTEM_TYPE_GENERAL] ?: general
            ResolvedPin(event, rule, type)
        }
    }

    suspend fun resolvePin(event: TimelineEvent, includeHidden: Boolean = false): ResolvedPin? {
        ensurePinConfiguration()
        val rules = pinRules().associateBy { it.id }
        val types = pinTypes().associateBy { it.id }
        val rule = effectiveRuleId(event)?.let(rules::get)
        if (!includeHidden && rule?.hidden == true) return null
        val type = types[rule?.pinTypeId ?: event.pinTypeId ?: SYSTEM_TYPE_GENERAL] ?: defaultGeneralType()
        return ResolvedPin(event, rule, type)
    }

    fun effectiveRuleId(event: TimelineEvent): String? = event.pinRuleId ?: when (event.type) {
        EventType.PHONE_CALL -> SYSTEM_RULE_PHONE_CALL
        EventType.SMS -> SYSTEM_RULE_SMS
        EventType.MMS -> SYSTEM_RULE_MMS
        EventType.NOTIFICATION -> event.metadata["ruleId"]
            ?: event.metadata["ruleIds"]?.split(',')?.firstOrNull { it.isNotBlank() }
        EventType.PIN_ROUTINE -> event.metadata["ruleId"] ?: event.metadata["templateId"]
        else -> null
    }

    private fun ruleMatches(rule: PinRule, context: PinRuleMatchContext): Boolean {
        if (rule.source == PinRuleSource.NOTIFICATION) {
            val selected = context.packageName in rule.packageNames
            val packageMatches = when (rule.appSelectionMode) {
                AppSelectionMode.INCLUDE_SELECTED -> selected
                AppSelectionMode.EXCLUDE_SELECTED -> !selected
            }
            if (!packageMatches) return false
        }
        if (rule.conditions.isEmpty()) return true
        val results = rule.conditions.map { conditionMatches(it, context) }
        return when (rule.conditionJoin) {
            RuleConditionJoin.ALL -> results.all { it }
            RuleConditionJoin.ANY -> results.any { it }
        }
    }

    private fun conditionMatches(condition: RuleCondition, context: PinRuleMatchContext): Boolean {
        val actual = context.value(condition.field)
        val expected = condition.value
        return when (condition.operator) {
            RuleConditionOperator.CONTAINS -> actual.contains(expected, ignoreCase = true)
            RuleConditionOperator.NOT_CONTAINS -> !actual.contains(expected, ignoreCase = true)
            RuleConditionOperator.EQUALS -> actual.equals(expected, ignoreCase = true)
            RuleConditionOperator.NOT_EQUALS -> !actual.equals(expected, ignoreCase = true)
            RuleConditionOperator.REGEX -> runCatching { Regex(expected).containsMatchIn(actual) }.getOrDefault(false)
            RuleConditionOperator.NOT_REGEX -> !runCatching { Regex(expected).containsMatchIn(actual) }.getOrDefault(false)
            RuleConditionOperator.GREATER_OR_EQUAL -> {
                val a = actual.toDoubleOrNull() ?: return false
                val e = expected.toDoubleOrNull() ?: return false
                a >= e
            }
            RuleConditionOperator.LESS_OR_EQUAL -> {
                val a = actual.toDoubleOrNull() ?: return false
                val e = expected.toDoubleOrNull() ?: return false
                a <= e
            }
        }
    }

    private suspend fun ensurePinConfiguration() {
        if (pinConfigurationReady) return
        pinInitMutex.withLock {
            if (pinConfigurationReady) return@withLock
            withContext(Dispatchers.IO) {
                val types = listOf(
                    PinType(SYSTEM_TYPE_GENERAL, "일반", "#3182F6", PinIcon.PIN),
                    PinType(SYSTEM_TYPE_CALL, "통화", "#00A86B", PinIcon.PHONE),
                    PinType(SYSTEM_TYPE_MESSAGE, "메시지", "#7B61FF", PinIcon.MESSAGE),
                    PinType(SYSTEM_TYPE_NOTIFICATION, "알림", "#F59F00", PinIcon.NOTIFICATION),
                    PinType(SYSTEM_TYPE_ROUTINE, "루틴", "#E64980", PinIcon.ROUTINE),
                )
                val existingTypeIds = queryPinTypes().mapTo(mutableSetOf()) { it.id }
                types.filterNot { it.id in existingTypeIds }.forEach(::writePinType)

                val rules = listOf(
                    PinRule(
                        id = SYSTEM_RULE_PHONE_CALL,
                        name = "자동 통화 감지",
                        source = PinRuleSource.PHONE_CALL,
                        system = true,
                        enabled = true,
                        priority = 10000,
                        pinTypeId = SYSTEM_TYPE_CALL,
                    ),
                    PinRule(
                        id = SYSTEM_RULE_SMS,
                        name = "자동 SMS 감지",
                        source = PinRuleSource.SMS,
                        system = true,
                        enabled = true,
                        priority = 10000,
                        pinTypeId = SYSTEM_TYPE_MESSAGE,
                    ),
                    PinRule(
                        id = SYSTEM_RULE_MMS,
                        name = "자동 MMS 감지",
                        source = PinRuleSource.MMS,
                        system = true,
                        enabled = true,
                        priority = 10000,
                        pinTypeId = SYSTEM_TYPE_MESSAGE,
                    ),
                    PinRule(
                        id = SYSTEM_RULE_NOTIFICATION,
                        name = "모든 알림 감지",
                        source = PinRuleSource.NOTIFICATION,
                        system = true,
                        enabled = false,
                        priority = 10000,
                        pinTypeId = SYSTEM_TYPE_NOTIFICATION,
                        appSelectionMode = AppSelectionMode.EXCLUDE_SELECTED,
                    ),
                )
                val existingRuleIds = queryPinRules().mapTo(mutableSetOf()) { it.id }
                rules.filterNot { it.id in existingRuleIds }.forEach(::writePinRule)

                if (rawState("pin_config_v2_migrated") != "1") {
                    migrateLegacyNotificationRules()
                    migrateLegacyPinTemplates()
                    writeState("pin_config_v2_migrated", "1")
                }
            }
            pinConfigurationReady = true
        }
    }

    private fun migrateLegacyNotificationRules() {
        val existing = queryPinRules().mapTo(mutableSetOf()) { it.id }
        rawNotificationRules().forEachIndexed { index, legacy ->
            if (legacy.id in existing) return@forEachIndexed
            val conditions = buildList {
                if (legacy.includeRegex.isNotBlank()) add(
                    RuleCondition(field = RuleConditionField.TEXT, operator = RuleConditionOperator.REGEX, value = legacy.includeRegex),
                )
                if (legacy.excludeRegex.isNotBlank()) add(
                    RuleCondition(field = RuleConditionField.TEXT, operator = RuleConditionOperator.NOT_REGEX, value = legacy.excludeRegex),
                )
            }
            writePinRule(
                PinRule(
                    id = legacy.id,
                    name = legacy.name,
                    source = PinRuleSource.NOTIFICATION,
                    enabled = legacy.enabled,
                    priority = 100 + index,
                    pinTypeId = SYSTEM_TYPE_NOTIFICATION,
                    appSelectionMode = AppSelectionMode.INCLUDE_SELECTED,
                    packageNames = legacy.packageNames,
                    conditionJoin = RuleConditionJoin.ALL,
                    conditions = conditions,
                ),
            )
        }
    }

    private fun migrateLegacyPinTemplates() {
        val existing = queryPinRules().mapTo(mutableSetOf()) { it.id }
        rawPinTemplates().forEachIndexed { index, legacy ->
            if (legacy.id in existing) return@forEachIndexed
            writePinRule(
                PinRule(
                    id = legacy.id,
                    name = legacy.name,
                    source = PinRuleSource.ROUTINE,
                    enabled = legacy.enabled,
                    priority = 100 + index,
                    pinTypeId = SYSTEM_TYPE_ROUTINE,
                    titleOverride = legacy.name,
                    bodyTemplate = legacy.text,
                ),
            )
        }
    }

    private fun queryPinTypes(): List<PinType> {
        val cursor = db.readableDatabase.query(
            "pin_types", arrayOf("id", "payload"), null, null, null, null, "created_at ASC",
        )
        return db.run { cursor.mapRows { PinType.fromJson(it.getString(0), decryptJson(it.getBlob(1))) } }
    }

    private fun queryPinRules(): List<PinRule> {
        val cursor = db.readableDatabase.query(
            "pin_rules", arrayOf("id", "payload"), null, null, null, null, "priority ASC, created_at ASC",
        )
        return db.run { cursor.mapRows { PinRule.fromJson(it.getString(0), decryptJson(it.getBlob(1))) } }
    }

    private fun writePinType(type: PinType) {
        val values = ContentValues().apply {
            put("id", type.id)
            put("created_at", System.currentTimeMillis())
            put("payload", crypto.encrypt(type.toJson().toString().toByteArray()))
        }
        db.writableDatabase.insertWithOnConflict("pin_types", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun writePinRule(rule: PinRule) {
        val values = ContentValues().apply {
            put("id", rule.id)
            put("created_at", System.currentTimeMillis())
            put("source", rule.source.name)
            put("is_system", if (rule.system) 1 else 0)
            put("enabled", if (rule.enabled) 1 else 0)
            put("hidden", if (rule.hidden) 1 else 0)
            put("pin_type_id", rule.pinTypeId)
            put("priority", rule.priority)
            put("payload", crypto.encrypt(rule.toJson().toString().toByteArray()))
        }
        db.writableDatabase.insertWithOnConflict("pin_rules", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun defaultGeneralType() = PinType(SYSTEM_TYPE_GENERAL, "일반", "#3182F6", PinIcon.PIN)

    // ---- Legacy APIs kept for v1 restore/shortcut compatibility -----------

    suspend fun notificationRules(): List<NotificationRule> {
        ensurePinConfiguration()
        return withContext(Dispatchers.IO) { rawNotificationRules() }
    }

    suspend fun upsertNotificationRule(rule: NotificationRule) {
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put("id", rule.id)
                put("created_at", System.currentTimeMillis())
                put("payload", crypto.encrypt(rule.toJson().toString().toByteArray()))
            }
            db.writableDatabase.insertWithOnConflict("notification_rules", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
        ensurePinConfiguration()
        val migrated = PinRule(
            id = rule.id,
            name = rule.name,
            source = PinRuleSource.NOTIFICATION,
            enabled = rule.enabled,
            priority = pinRules().maxOfOrNull { it.priority }?.plus(1) ?: 100,
            pinTypeId = SYSTEM_TYPE_NOTIFICATION,
            appSelectionMode = AppSelectionMode.INCLUDE_SELECTED,
            packageNames = rule.packageNames,
            conditions = buildList {
                if (rule.includeRegex.isNotBlank()) add(RuleCondition(field = RuleConditionField.TEXT, operator = RuleConditionOperator.REGEX, value = rule.includeRegex))
                if (rule.excludeRegex.isNotBlank()) add(RuleCondition(field = RuleConditionField.TEXT, operator = RuleConditionOperator.NOT_REGEX, value = rule.excludeRegex))
            },
        )
        upsertPinRule(migrated)
    }

    suspend fun deleteNotificationRule(id: String) {
        withContext(Dispatchers.IO) { db.writableDatabase.delete("notification_rules", "id = ?", arrayOf(id)) }
        deletePinRule(id)
        _changes.tryEmit(Unit)
    }

    suspend fun matchingNotificationRules(packageName: String, searchableText: String): List<NotificationRule> =
        notificationRules().filter { rule ->
            if (!rule.enabled || packageName !in rule.packageNames) return@filter false
            val excluded = rule.excludeRegex.takeIf { it.isNotBlank() }?.let { pattern ->
                runCatching { Regex(pattern).containsMatchIn(searchableText) }.getOrDefault(false)
            } ?: false
            if (excluded) return@filter false
            val include = rule.includeRegex.takeIf { it.isNotBlank() } ?: return@filter true
            runCatching { Regex(include).containsMatchIn(searchableText) }.getOrDefault(false)
        }

    suspend fun pinTemplates(): List<PinTemplate> {
        ensurePinConfiguration()
        return pinRules(PinRuleSource.ROUTINE).map {
            PinTemplate(it.id, it.name, it.bodyTemplate, it.enabled)
        }
    }

    suspend fun getPinTemplate(id: String): PinTemplate? = pinTemplates().firstOrNull { it.id == id }

    suspend fun upsertPinTemplate(template: PinTemplate) {
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put("id", template.id)
                put("created_at", System.currentTimeMillis())
                put("payload", crypto.encrypt(template.toJson().toString().toByteArray()))
            }
            db.writableDatabase.insertWithOnConflict("pin_templates", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
        ensurePinConfiguration()
        val old = getPinRule(template.id)
        upsertPinRule(
            (old ?: PinRule(
                id = template.id,
                name = template.name,
                source = PinRuleSource.ROUTINE,
                pinTypeId = SYSTEM_TYPE_ROUTINE,
                priority = pinRules().maxOfOrNull { it.priority }?.plus(1) ?: 100,
            )).copy(
                name = template.name,
                enabled = template.enabled,
                titleOverride = template.name,
                bodyTemplate = template.text,
            ),
        )
    }

    suspend fun deletePinTemplate(id: String) {
        if (deletePinRule(id)) {
            withContext(Dispatchers.IO) { db.writableDatabase.delete("pin_templates", "id = ?", arrayOf(id)) }
            _changes.tryEmit(Unit)
        }
    }

    private fun rawNotificationRules(): List<NotificationRule> {
        val cursor = db.readableDatabase.query(
            "notification_rules", arrayOf("id", "payload"), null, null, null, null, "created_at ASC",
        )
        return db.run { cursor.mapRows { NotificationRule.fromJson(it.getString(0), decryptJson(it.getBlob(1))) } }
    }

    private fun rawPinTemplates(): List<PinTemplate> {
        val cursor = db.readableDatabase.query(
            "pin_templates", arrayOf("id", "payload"), null, null, null, null, "created_at ASC",
        )
        return db.run { cursor.mapRows { PinTemplate.fromJson(it.getString(0), decryptJson(it.getBlob(1))) } }
    }

    // ---- Geocode / state --------------------------------------------------

    suspend fun getGeocodeCache(key: String): PlaceLabel? = withContext(Dispatchers.IO) {
        db.readableDatabase.query(
            "geocode_cache", arrayOf("payload"), "cache_key = ?", arrayOf(key), null, null, null, "1",
        ).use { if (!it.moveToFirst()) null else PlaceLabel.fromJson(decryptJson(it.getBlob(0))) }
    }

    suspend fun putGeocodeCache(key: String, place: PlaceLabel) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("cache_key", key)
            put("updated_at", System.currentTimeMillis())
            put("payload", crypto.encrypt(place.toJson().toString().toByteArray()))
        }
        db.writableDatabase.insertWithOnConflict("geocode_cache", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    suspend fun state(key: String): String? = withContext(Dispatchers.IO) { rawState(key) }

    suspend fun setState(key: String, value: String) = withContext(Dispatchers.IO) { writeState(key, value) }

    private fun rawState(key: String): String? = db.readableDatabase.query(
        "state", arrayOf("value"), "key = ?", arrayOf(key), null, null, null, "1",
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun writeState(key: String, value: String) {
        val values = ContentValues().apply { put("key", key); put("value", value) }
        db.writableDatabase.insertWithOnConflict("state", null, values, SQLiteDatabase.CONFLICT_REPLACE)
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
                "pin_rules",
                "pin_types",
                "geocode_cache",
                "state",
            ).forEach { table -> writable.delete(table, null, null) }
            writable.setTransactionSuccessful()
        } finally {
            writable.endTransaction()
        }
        pinConfigurationReady = false
        _changes.tryEmit(Unit)
    }

    // Used by backup code after v2. System defaults can be recreated, but user types/rules must be preserved.
    suspend fun allPinTypes(): List<PinType> = pinTypes()
    suspend fun allPinRules(): List<PinRule> = pinRules()
    suspend fun restorePinType(type: PinType) = upsertPinType(type)
    suspend fun restorePinRule(rule: PinRule) = upsertPinRule(rule)

    private fun queryTrackPoints(selection: String?, args: Array<String>?): List<TrackPoint> {
        val cursor = db.readableDatabase.query(
            "track_points", arrayOf("id", "timestamp", "payload"), selection, args, null, null, "timestamp ASC",
        )
        return db.run { cursor.mapRows { decodeTrackPoint(it.getLong(0), it.getLong(1), it.getBlob(2)) } }
    }

    private fun queryDays(sql: String): List<LocalDate> {
        val result = mutableListOf<LocalDate>()
        db.readableDatabase.rawQuery(sql, null).use { cursor ->
            while (cursor.moveToNext()) runCatching { LocalDate.parse(cursor.getString(0)) }.getOrNull()?.let(result::add)
        }
        return result
    }

    private fun queryEvents(selection: String?, args: Array<String>?): List<TimelineEvent> {
        val cursor = db.readableDatabase.query(
            "events",
            arrayOf("id", "timestamp", "type", "pin_rule_id", "pin_type_id", "payload"),
            selection,
            args,
            null,
            null,
            "timestamp ASC",
        )
        return db.run {
            cursor.mapRows { row ->
                val id = row.getString(0)
                val decoded = TimelineEvent.fromJson(
                    id,
                    row.getLong(1),
                    EventType.valueOf(row.getString(2)),
                    decryptJson(row.getBlob(5)),
                )
                decoded.copy(
                    pinRuleId = row.strOrNull(3) ?: decoded.pinRuleId,
                    pinTypeId = row.strOrNull(4) ?: decoded.pinTypeId,
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

    private fun scalarLong(sql: String, args: Array<String>): Long =
        db.readableDatabase.rawQuery(sql, args).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    private fun decodeTrackPoint(id: Long, timestamp: Long, payload: ByteArray): TrackPoint =
        TrackPoint.fromJson(id, timestamp, decryptJson(payload))

    private fun decryptJson(payload: ByteArray): JSONObject =
        JSONObject(crypto.decrypt(payload).toString(Charsets.UTF_8))
}

private fun android.database.Cursor.strOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
