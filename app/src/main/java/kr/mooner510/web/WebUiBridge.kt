package kr.mooner510.web

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.webkit.JavascriptInterface
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.mooner510.BiometricLock
import kr.mooner510.appGraph
import kr.mooner510.data.AppSettings
import kr.mooner510.data.EventType
import kr.mooner510.data.ResolvedPin
import kr.mooner510.data.SYSTEM_TYPE_GENERAL
import kr.mooner510.data.TimelineEvent
import kr.mooner510.data.TrackPoint
import kr.mooner510.data.TrackingPreset
import kr.mooner510.tracking.TrackingService
import org.json.JSONArray
import org.json.JSONObject

/** The screens the web UI can hand off to while they are still written in Compose. */
enum class NativeScreen(val key: String) {
    Rules("rules"),
    CloudBackup("cloudBackup"),
    AdvancedSettings("advancedSettings"),
}

/**
 * The only door between the web UI and native code. Every method is a narrow, purpose-built operation:
 * the page cannot pass file paths, SQL, URLs or class names, and the interface is only attached to a
 * WebView that loads our own packaged assets.
 */
class WebUiBridge(
    private val context: Context,
    private val scope: CoroutineScope,
    private val reply: (id: Int, ok: Boolean, json: String) -> Unit,
    private val openNative: (NativeScreen) -> Unit,
    private val requestBiometricEnroll: () -> Unit,
) {
    private val graph get() = context.appGraph

    @JavascriptInterface
    fun call(id: Int, method: String, argsJson: String) {
        scope.launch(Dispatchers.Default) {
            try {
                val args = if (argsJson.isBlank()) JSONObject() else JSONObject(argsJson)
                reply(id, true, dispatch(method, args)?.toString().orEmpty())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                reply(id, false, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun dispatch(method: String, args: JSONObject): Any? = when (method) {
        "getSettings" -> graph.preferences.current().toJson()
        "updateSettings" -> updateSettings(args)
        "getTimeline" -> getTimeline(args.getLong("start"), args.getLong("end"))
        "getPins" -> JSONArray().also { arr ->
            graph.repository.resolvedPinsBetween(args.getLong("start"), args.getLong("end"))
                .sortedByDescending { it.event.timestamp }
                .forEach { arr.put(it.toJson()) }
        }
        "getPinTypes" -> JSONArray().also { arr -> graph.repository.pinTypes().forEach { arr.put(it.toJson()) } }
        "addManualPin" -> {
            addManualPin(args.optString("text"), args.optString("typeId"))
            null
        }
        "getRules" -> getRules()
        "setRuleEnabled" -> {
            val rule = graph.repository.getPinRule(args.getString("id")) ?: error("규칙을 찾을 수 없습니다.")
            graph.repository.upsertPinRule(rule.copy(enabled = args.getBoolean("enabled")))
            null
        }
        "openNative" -> {
            val key = args.getString("screen")
            val screen = NativeScreen.entries.firstOrNull { it.key == key } ?: error("알 수 없는 화면입니다.")
            withContext(Dispatchers.Main) { openNative(screen) }
            null
        }
        else -> error("지원하지 않는 요청입니다: $method")
    }

    private suspend fun updateSettings(patch: JSONObject): JSONObject {
        val prefs = graph.preferences
        if (patch.has("trackingEnabled")) {
            val enabled = patch.getBoolean("trackingEnabled")
            when {
                !enabled -> {
                    prefs.setTrackingEnabled(false)
                    TrackingService.stop(context)
                }
                hasLocationPermission() -> {
                    prefs.setTrackingEnabled(true)
                    TrackingService.start(context)
                }
                // Starting a location foreground service without permission crashes the app; the
                // permission flow lives in the native settings screen.
                else -> withContext(Dispatchers.Main) { openNative(NativeScreen.AdvancedSettings) }
            }
        }
        if (patch.has("trackingPreset")) {
            runCatching { TrackingPreset.valueOf(patch.getString("trackingPreset")) }
                .getOrNull()?.let { prefs.setTrackingPreset(it) }
        }
        if (patch.has("reverseGeocodingEnabled")) prefs.setReverseGeocodingEnabled(patch.getBoolean("reverseGeocodingEnabled"))
        if (patch.has("autoStartAfterBoot")) prefs.setAutoStartAfterBoot(patch.getBoolean("autoStartAfterBoot"))
        if (patch.has("biometricLockEnabled")) {
            val enabled = patch.getBoolean("biometricLockEnabled")
            if (enabled && !BiometricLock.isAvailable(context)) {
                withContext(Dispatchers.Main) { requestBiometricEnroll() }
            } else {
                prefs.setBiometricLockEnabled(enabled)
            }
        }
        if (patch.has("mapStyleUri")) {
            val uri = patch.getString("mapStyleUri")
            // Only https styles: the WebView must never be pointed at file:/content:/http: resources.
            if (uri.startsWith("https://")) prefs.setMapStyleUri(uri)
        }
        val timelineKeys = listOf("dialTickMinutes", "dialRadiusMinutes", "detailWindowMinutes", "totalWindowMinutes", "cameraWindowMinutes")
        if (timelineKeys.any(patch::has)) {
            val cur = prefs.current()
            prefs.setTimelineDisplaySettings(
                tickMinutes = patch.optInt("dialTickMinutes", cur.dialTickMinutes),
                radiusMinutes = patch.optInt("dialRadiusMinutes", cur.dialRadiusMinutes),
                detailMinutes = patch.optInt("detailWindowMinutes", cur.detailWindowMinutes),
                totalMinutes = patch.optInt("totalWindowMinutes", cur.totalWindowMinutes),
                cameraMinutes = patch.optInt("cameraWindowMinutes", cur.cameraWindowMinutes),
            )
        }
        return prefs.current().toJson()
    }

    private suspend fun getTimeline(start: Long, end: Long): JSONObject {
        val repo = graph.repository
        val points = repo.trackPointsBetween(start, end)
        val pins = repo.resolvedPinsBetween(start, end)
        return JSONObject().apply {
            put("points", JSONArray().also { arr -> points.forEach { arr.put(it.toJsonRow()) } })
            put("pins", JSONArray().also { arr -> pins.forEach { arr.put(it.toJson()) } })
            put("pinTypes", JSONArray().also { arr -> repo.pinTypes().forEach { arr.put(it.toJson()) } })
            put("latest", repo.latestTrackPoint()?.toJsonRow() ?: JSONObject.NULL)
            put("days", JSONArray().also { arr -> repo.dataDays().forEach { arr.put(it.toString()) } })
        }
    }

    private suspend fun addManualPin(text: String, typeId: String) {
        val repo = graph.repository
        val timestamp = System.currentTimeMillis()
        val point = repo.latestTrackPoint()?.takeIf { timestamp - it.timestamp <= 5 * 60_000L }
        repo.insertEvent(
            TimelineEvent(
                timestamp = timestamp,
                type = EventType.PIN_MANUAL,
                latitude = point?.latitude,
                longitude = point?.longitude,
                title = "압정",
                body = text.takeIf { it.isNotBlank() },
                source = "MANUAL",
                pinTypeId = typeId.takeIf { it.isNotBlank() } ?: SYSTEM_TYPE_GENERAL,
            ),
        )
    }

    private suspend fun getRules(): JSONArray {
        val repo = graph.repository
        val types = repo.pinTypes().associateBy { it.id }
        return JSONArray().also { arr ->
            repo.pinRules().sortedBy { it.priority }.forEach { rule ->
                val type = types[rule.pinTypeId]
                arr.put(
                    JSONObject()
                        .put("id", rule.id)
                        .put("name", rule.name)
                        .put("source", rule.source.name)
                        .put("system", rule.system)
                        .put("enabled", rule.enabled)
                        .put("hidden", rule.hidden)
                        .put("priority", rule.priority)
                        .put("typeId", rule.pinTypeId)
                        .put("typeName", type?.name ?: "일반")
                        .put("color", type?.colorHex ?: "#3182F6")
                        .put("conditionCount", rule.conditions.size)
                        .put("packageCount", rule.packageNames.size),
                )
            }
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
}

private fun AppSettings.toJson() = JSONObject()
    .put("trackingEnabled", trackingEnabled)
    .put("trackingPreset", trackingPreset.name)
    .put("reverseGeocodingEnabled", reverseGeocodingEnabled)
    .put("biometricLockEnabled", biometricLockEnabled)
    .put("autoStartAfterBoot", autoStartAfterBoot)
    .put("mapStyleUri", mapStyleUri)
    .put("dialTickMinutes", dialTickMinutes)
    .put("dialRadiusMinutes", dialRadiusMinutes)
    .put("detailWindowMinutes", detailWindowMinutes)
    .put("totalWindowMinutes", totalWindowMinutes)
    .put("cameraWindowMinutes", cameraWindowMinutes)

private fun TrackPoint.toJsonRow() = JSONArray()
    .put(timestamp).put(latitude).put(longitude).put(accuracyMeters.toDouble())

private fun ResolvedPin.toJson() = JSONObject()
    .put("id", event.id)
    .put("t", event.timestamp)
    .put("eventType", event.type.name)
    .put("title", displayTitle)
    .put("body", displayBody ?: JSONObject.NULL)
    .put("lat", event.latitude ?: JSONObject.NULL)
    .put("lng", event.longitude ?: JSONObject.NULL)
    .put("source", event.source ?: JSONObject.NULL)
    .put("typeId", pinType.id)
    .put("typeName", pinType.name)
    .put("color", pinType.colorHex)
    .put("icon", pinType.icon.name)

private fun kr.mooner510.data.PinType.toJson() = JSONObject()
    .put("id", id)
    .put("name", name)
    .put("color", colorHex)
    .put("icon", icon.name)
