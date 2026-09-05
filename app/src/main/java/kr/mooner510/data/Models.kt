package kr.mooner510.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

enum class EventType { PIN_MANUAL, PIN_ROUTINE, PHONE_CALL, SMS, MMS, NOTIFICATION, SYSTEM }

enum class PinRuleSource { PHONE_CALL, SMS, MMS, NOTIFICATION, ROUTINE }
enum class PinIcon { PIN, PHONE, MESSAGE, NOTIFICATION, ROUTINE, STAR, PLACE, HOME, WORK }
enum class AppSelectionMode { INCLUDE_SELECTED, EXCLUDE_SELECTED }
enum class RuleConditionJoin { ALL, ANY }
enum class RuleConditionField {
    TEXT,
    TITLE,
    BODY,
    PACKAGE_NAME,
    APP_LABEL,
    CONTACT_NAME,
    PHONE_NUMBER,
    ADDRESS,
    DIRECTION,
    DURATION_SECONDS,
    SOURCE,
}
enum class RuleConditionOperator {
    CONTAINS,
    NOT_CONTAINS,
    EQUALS,
    NOT_EQUALS,
    REGEX,
    NOT_REGEX,
    GREATER_OR_EQUAL,
    LESS_OR_EQUAL,
}

data class TrackPoint(
    val id: Long = 0,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val altitudeMeters: Double? = null,
    val speedMps: Float? = null,
    val bearingDegrees: Float? = null,
    val provider: String? = null,
    val isMock: Boolean = false,
) {
    fun toJson() = JSONObject().apply {
        put("latitude", latitude)
        put("longitude", longitude)
        put("accuracyMeters", accuracyMeters.toDouble())
        altitudeMeters?.let { put("altitudeMeters", it) }
        speedMps?.let { put("speedMps", it.toDouble()) }
        bearingDegrees?.let { put("bearingDegrees", it.toDouble()) }
        provider?.let { put("provider", it) }
        put("isMock", isMock)
    }

    companion object {
        fun fromJson(id: Long, timestamp: Long, json: JSONObject) = TrackPoint(
            id,
            timestamp,
            json.getDouble("latitude"),
            json.getDouble("longitude"),
            json.optDouble("accuracyMeters", 9999.0).toFloat(),
            json.optDoubleOrNull("altitudeMeters"),
            json.optDoubleOrNull("speedMps")?.toFloat(),
            json.optDoubleOrNull("bearingDegrees")?.toFloat(),
            json.optStringOrNull("provider"),
            json.optBoolean("isMock", false),
        )
    }
}

data class TimelineEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long,
    val type: EventType,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val title: String,
    val body: String? = null,
    val source: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val attachmentIds: List<String> = emptyList(),
    val pinRuleId: String? = null,
    val pinTypeId: String? = null,
) {
    fun toJson() = JSONObject().apply {
        latitude?.let { put("latitude", it) }
        longitude?.let { put("longitude", it) }
        put("title", title)
        body?.let { put("body", it) }
        source?.let { put("source", it) }
        put("metadata", JSONObject(metadata))
        put("attachmentIds", JSONArray(attachmentIds))
        pinRuleId?.let { put("pinRuleId", it) }
        pinTypeId?.let { put("pinTypeId", it) }
    }

    companion object {
        fun fromJson(id: String, timestamp: Long, type: EventType, json: JSONObject): TimelineEvent {
            val m = json.optJSONObject("metadata") ?: JSONObject()
            val metadata = buildMap { m.keys().forEach { put(it, m.optString(it)) } }
            val a = json.optJSONArray("attachmentIds") ?: JSONArray()
            return TimelineEvent(
                id = id,
                timestamp = timestamp,
                type = type,
                latitude = json.optDoubleOrNull("latitude"),
                longitude = json.optDoubleOrNull("longitude"),
                title = json.optString("title", type.name),
                body = json.optStringOrNull("body"),
                source = json.optStringOrNull("source"),
                metadata = metadata,
                attachmentIds = buildList { for (i in 0 until a.length()) add(a.getString(i)) },
                pinRuleId = json.optStringOrNull("pinRuleId"),
                pinTypeId = json.optStringOrNull("pinTypeId"),
            )
        }
    }
}

data class PinType(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val colorHex: String = "#3182F6",
    val icon: PinIcon = PinIcon.PIN,
) {
    fun toJson() = JSONObject().apply {
        put("name", name)
        put("colorHex", colorHex)
        put("icon", icon.name)
    }

    companion object {
        fun fromJson(id: String, json: JSONObject) = PinType(
            id = id,
            name = json.optString("name", "일반"),
            colorHex = json.optString("colorHex", "#3182F6"),
            icon = json.optString("icon")
                .takeIf { it.isNotBlank() }
                ?.let { runCatching { PinIcon.valueOf(it) }.getOrNull() }
                ?: PinIcon.PIN,
        )
    }
}

data class RuleCondition(
    val id: String = UUID.randomUUID().toString(),
    val field: RuleConditionField = RuleConditionField.TEXT,
    val operator: RuleConditionOperator = RuleConditionOperator.CONTAINS,
    val value: String = "",
) {
    fun toJson() = JSONObject().apply {
        put("id", id)
        put("field", field.name)
        put("operator", operator.name)
        put("value", value)
    }

    companion object {
        fun fromJson(json: JSONObject) = RuleCondition(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            field = runCatching { RuleConditionField.valueOf(json.optString("field")) }
                .getOrDefault(RuleConditionField.TEXT),
            operator = runCatching { RuleConditionOperator.valueOf(json.optString("operator")) }
                .getOrDefault(RuleConditionOperator.CONTAINS),
            value = json.optString("value", ""),
        )
    }
}

data class PinRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val source: PinRuleSource,
    val system: Boolean = false,
    val enabled: Boolean = true,
    val hidden: Boolean = false,
    val priority: Int = 100,
    val pinTypeId: String,
    val titleOverride: String = "",
    val bodyTemplate: String = "",
    val appSelectionMode: AppSelectionMode = AppSelectionMode.INCLUDE_SELECTED,
    val packageNames: Set<String> = emptySet(),
    val conditionJoin: RuleConditionJoin = RuleConditionJoin.ALL,
    val conditions: List<RuleCondition> = emptyList(),
) {
    fun toJson() = JSONObject().apply {
        put("name", name)
        put("source", source.name)
        put("system", system)
        put("enabled", enabled)
        put("hidden", hidden)
        put("priority", priority)
        put("pinTypeId", pinTypeId)
        put("titleOverride", titleOverride)
        put("bodyTemplate", bodyTemplate)
        put("appSelectionMode", appSelectionMode.name)
        put("packageNames", JSONArray(packageNames.toList()))
        put("conditionJoin", conditionJoin.name)
        put("conditions", JSONArray().apply { conditions.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(id: String, json: JSONObject): PinRule {
            val packages = json.optJSONArray("packageNames") ?: JSONArray()
            val conditions = json.optJSONArray("conditions") ?: JSONArray()
            return PinRule(
                id = id,
                name = json.optString("name", "압정 룰"),
                source = runCatching { PinRuleSource.valueOf(json.optString("source")) }
                    .getOrDefault(PinRuleSource.NOTIFICATION),
                system = json.optBoolean("system", false),
                enabled = json.optBoolean("enabled", true),
                hidden = json.optBoolean("hidden", false),
                priority = json.optInt("priority", 100),
                pinTypeId = json.optString("pinTypeId", SYSTEM_TYPE_GENERAL),
                titleOverride = json.optString("titleOverride", ""),
                bodyTemplate = json.optString("bodyTemplate", ""),
                appSelectionMode = runCatching {
                    AppSelectionMode.valueOf(json.optString("appSelectionMode"))
                }.getOrDefault(AppSelectionMode.INCLUDE_SELECTED),
                packageNames = buildSet {
                    for (i in 0 until packages.length()) add(packages.getString(i))
                },
                conditionJoin = runCatching {
                    RuleConditionJoin.valueOf(json.optString("conditionJoin"))
                }.getOrDefault(RuleConditionJoin.ALL),
                conditions = buildList {
                    for (i in 0 until conditions.length()) {
                        conditions.optJSONObject(i)?.let { add(RuleCondition.fromJson(it)) }
                    }
                },
            )
        }
    }
}

data class PinRuleMatchContext(
    val packageName: String = "",
    val appLabel: String = "",
    val title: String = "",
    val body: String = "",
    val contactName: String = "",
    val phoneNumber: String = "",
    val address: String = "",
    val direction: String = "",
    val durationSeconds: Long? = null,
    val source: String = "",
) {
    val text: String
        get() = listOf(title, body, appLabel, contactName, phoneNumber, address, direction, source)
            .filter { it.isNotBlank() }
            .joinToString("\n")

    fun value(field: RuleConditionField): String = when (field) {
        RuleConditionField.TEXT -> text
        RuleConditionField.TITLE -> title
        RuleConditionField.BODY -> body
        RuleConditionField.PACKAGE_NAME -> packageName
        RuleConditionField.APP_LABEL -> appLabel
        RuleConditionField.CONTACT_NAME -> contactName
        RuleConditionField.PHONE_NUMBER -> phoneNumber
        RuleConditionField.ADDRESS -> address
        RuleConditionField.DIRECTION -> direction
        RuleConditionField.DURATION_SECONDS -> durationSeconds?.toString().orEmpty()
        RuleConditionField.SOURCE -> source
    }
}

data class ResolvedPin(
    val event: TimelineEvent,
    val rule: PinRule?,
    val pinType: PinType,
) {
    val displayTitle: String get() = rule?.titleOverride?.takeIf { it.isNotBlank() } ?: event.title
    val displayBody: String? get() = event.body ?: rule?.bodyTemplate?.takeIf { it.isNotBlank() }
}

// Legacy models remain readable so existing v1 data can be migrated without loss.
data class NotificationRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val packageNames: Set<String>,
    val includeRegex: String = "",
    val excludeRegex: String = "",
    val enabled: Boolean = true,
) {
    fun toJson() = JSONObject().apply {
        put("name", name)
        put("packageNames", JSONArray(packageNames.toList()))
        put("includeRegex", includeRegex)
        put("excludeRegex", excludeRegex)
        put("enabled", enabled)
    }

    companion object {
        fun fromJson(id: String, json: JSONObject): NotificationRule {
            val p = json.optJSONArray("packageNames") ?: JSONArray()
            return NotificationRule(
                id,
                json.optString("name", "규칙"),
                buildSet { for (i in 0 until p.length()) add(p.getString(i)) },
                json.optString("includeRegex", ""),
                json.optString("excludeRegex", ""),
                json.optBoolean("enabled", true),
            )
        }
    }
}

data class PinTemplate(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val text: String,
    val enabled: Boolean = true,
) {
    fun toJson() = JSONObject().apply {
        put("name", name)
        put("text", text)
        put("enabled", enabled)
    }

    companion object {
        fun fromJson(id: String, json: JSONObject) = PinTemplate(
            id,
            json.optString("name", "압정"),
            json.optString("text", ""),
            json.optBoolean("enabled", true),
        )
    }
}

data class AttachmentRecord(
    val id: String,
    val eventId: String,
    val kind: String,
    val mimeType: String?,
    val encryptedPath: String,
    val createdAt: Long,
    val externalUri: String? = null,
)

data class PlaceLabel(val displayName: String, val address: String?) {
    fun toJson() = JSONObject().apply {
        put("displayName", displayName)
        address?.let { put("address", it) }
    }

    companion object {
        fun fromJson(json: JSONObject) = PlaceLabel(
            json.optString("displayName", ""),
            json.optStringOrNull("address"),
        )
    }
}

const val SYSTEM_TYPE_GENERAL = "system:type:general"
const val SYSTEM_TYPE_CALL = "system:type:call"
const val SYSTEM_TYPE_MESSAGE = "system:type:message"
const val SYSTEM_TYPE_NOTIFICATION = "system:type:notification"
const val SYSTEM_TYPE_ROUTINE = "system:type:routine"

const val SYSTEM_RULE_PHONE_CALL = "system:rule:phone-call"
const val SYSTEM_RULE_SMS = "system:rule:sms"
const val SYSTEM_RULE_MMS = "system:rule:mms"
const val SYSTEM_RULE_NOTIFICATION = "system:rule:notification"

fun dayKey(timestamp: Long, zoneId: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate().format(DateTimeFormatter.ISO_DATE)

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (!has(key) || isNull(key)) null else optDouble(key).takeUnless { it.isNaN() }

private fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
