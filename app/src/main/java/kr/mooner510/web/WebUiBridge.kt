package kr.mooner510.web

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.JavascriptInterface
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.mooner510.BiometricLock
import kr.mooner510.appGraph
import kr.mooner510.backup.BackupProgress
import kr.mooner510.backup.RemoteBackupMetadata
import kr.mooner510.data.AppSelectionMode
import kr.mooner510.data.AppSettings
import kr.mooner510.data.EventType
import kr.mooner510.data.PinIcon
import kr.mooner510.data.PinRule
import kr.mooner510.data.PinRuleSource
import kr.mooner510.data.PinType
import kr.mooner510.data.ResolvedPin
import kr.mooner510.data.RuleCondition
import kr.mooner510.data.RuleConditionField
import kr.mooner510.data.RuleConditionJoin
import kr.mooner510.data.RuleConditionOperator
import kr.mooner510.data.SYSTEM_TYPE_GENERAL
import kr.mooner510.data.TimelineEvent
import kr.mooner510.data.TrackPoint
import kr.mooner510.data.TrackingPreset
import kr.mooner510.map.AutoSyncStatus
import kr.mooner510.map.DownloadLogEntry
import kr.mooner510.map.OfflineDownloadState
import kr.mooner510.tracking.TrackingService
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** What only the hosting activity can do: system permission dialogs, document pickers and settings screens. */
interface WebUiHostActions {
    suspend fun requestPermissions(permissions: Array<String>)
    suspend fun createDocument(fileName: String): Uri?
    suspend fun openDocument(): Uri?
    fun startActivity(intent: Intent)
    fun requestBiometricEnroll()
    fun emit(name: String, json: String)
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
    private val host: WebUiHostActions,
) {
    private val graph get() = context.appGraph
    private val backupBusy = AtomicBoolean(false)

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
        "completeOnboarding" -> completeOnboarding()
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

        "getRuleEditor" -> getRuleEditor()
        "saveRule" -> {
            saveRule(args)
            null
        }
        "deleteRule" -> {
            val rule = graph.repository.getPinRule(args.getString("id")) ?: error("규칙을 찾을 수 없습니다.")
            check(graph.repository.deletePinRule(rule.id)) { "압정을 만든 규칙이나 시스템 규칙은 삭제할 수 없습니다." }
            refreshRoutineShortcutsIfNeeded(rule)
            null
        }
        "setRuleEnabled" -> {
            val rule = graph.repository.getPinRule(args.getString("id")) ?: error("규칙을 찾을 수 없습니다.")
            graph.repository.upsertPinRule(rule.copy(enabled = args.getBoolean("enabled")))
            refreshRoutineShortcutsIfNeeded(rule)
            null
        }
        "setRuleHidden" -> {
            val rule = graph.repository.getPinRule(args.getString("id")) ?: error("규칙을 찾을 수 없습니다.")
            graph.repository.upsertPinRule(rule.copy(hidden = args.getBoolean("hidden")))
            null
        }
        "moveRule" -> {
            moveRule(args.getString("id"), args.getInt("delta"))
            null
        }
        "savePinType" -> {
            savePinType(args)
            null
        }
        "deletePinType" -> {
            check(graph.repository.deletePinType(args.getString("id"))) { "사용 중인 타입이나 기본 타입은 삭제할 수 없습니다." }
            null
        }
        "getInstalledApps" -> getInstalledApps()

        "getPermissions" -> permissionsJson()
        "requestPermission" -> {
            requestPermission(args.getString("kind"))
            permissionsJson()
        }

        "getOfflineMaps" -> getOfflineMaps()
        "downloadAllOfflineMaps" -> {
            // Returns once the download has started; progress arrives as "offline-changed" events.
            graph.offlineMapManager.downloadAll()
            null
        }
        "deleteOfflineMap" -> {
            check(graph.offlineMapManager.delete(args.getLong("id"))) { "오프라인 지도를 삭제하지 못했습니다." }
            null
        }

        "exportBackup" -> exclusiveBackup { exportBackup(args.getString("password")) }
        "importBackup" -> exclusiveBackup { importBackup(args.getString("password")) }
        "cloudBackupConfigured" -> graph.cloudBackupManager.configured
        "cloudBackupMetadata" -> withContext(Dispatchers.Main) { graph.cloudBackupManager.metadata(context) }?.toJson() ?: JSONObject.NULL
        "cloudBackup" -> exclusiveBackup {
            val password = args.getString("password").toCharArray()
            val expected = args.optString("expectedId").takeIf { it.isNotEmpty() }
            graph.cloudBackupManager.backup(context, password, expected, ::emitProgress).toJson()
        }
        "cloudRestore" -> exclusiveBackup {
            val password = args.getString("password").toCharArray()
            graph.cloudBackupManager.restore(context, password, ::emitProgress)
                .also { graph.routineShortcutManager.refresh() }
                .toJson()
        }
        "cloudDelete" -> exclusiveBackup {
            graph.cloudBackupManager.delete(context)
            null
        }

        else -> error("지원하지 않는 요청입니다: $method")
    }

    private suspend fun updateSettings(patch: JSONObject): JSONObject {
        val prefs = graph.preferences
        if (patch.has("trackingEnabled")) {
            val enabled = patch.getBoolean("trackingEnabled")
            if (enabled && !hasLocationPermission()) {
                // Starting a location foreground service without permission crashes the app: ask first.
                host.requestPermissions(LOCATION_PERMISSIONS)
            }
            when {
                !enabled -> {
                    prefs.setTrackingEnabled(false)
                    TrackingService.stop(context)
                }
                hasLocationPermission() -> {
                    prefs.setTrackingEnabled(true)
                    TrackingService.start(context)
                }
            }
        }
        if (patch.has("trackingPreset")) {
            runCatching { TrackingPreset.valueOf(patch.getString("trackingPreset")) }
                .getOrNull()?.let { prefs.setTrackingPreset(it) }
        }
        if (patch.has("reverseGeocodingEnabled")) prefs.setReverseGeocodingEnabled(patch.getBoolean("reverseGeocodingEnabled"))
        if (patch.has("autoStartAfterBoot")) prefs.setAutoStartAfterBoot(patch.getBoolean("autoStartAfterBoot"))
        if (patch.has("autoOfflineMaps")) prefs.setAutoOfflineMaps(patch.getBoolean("autoOfflineMaps"))
        if (patch.has("biometricLockEnabled")) {
            val enabled = patch.getBoolean("biometricLockEnabled")
            if (enabled && !BiometricLock.isAvailable(context)) {
                withContext(Dispatchers.Main) { host.requestBiometricEnroll() }
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

    private suspend fun completeOnboarding(): JSONObject {
        val prefs = graph.preferences
        if (hasLocationPermission() && prefs.current().trackingEnabled) TrackingService.start(context)
        prefs.setOnboardingCompleted(true)
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
            // The UI contract is ascending (oldest first); dataDays() is newest first.
            put("days", JSONArray().also { arr -> repo.dataDays().asReversed().forEach { arr.put(it.toString()) } })
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

    // ---- Pin rules / types ---------------------------------------------------

    private suspend fun getRuleEditor(): JSONObject {
        val repo = graph.repository
        val types = repo.pinTypes()
        val rules = repo.pinRules().sortedWith(compareBy<PinRule> { it.source.ordinal }.thenBy { it.priority })
        return JSONObject()
            .put(
                "types",
                JSONArray().also { arr ->
                    types.forEach { arr.put(it.toJson().put("system", it.id.startsWith("system:type:")).put("usage", repo.pinTypeUsageCount(it.id))) }
                },
            )
            .put("rules", JSONArray().also { arr -> rules.forEach { arr.put(it.toJson(repo.pinRuleUsageCount(it.id))) } })
    }

    private suspend fun saveRule(json: JSONObject) {
        val repo = graph.repository
        val existing = json.optString("id").takeIf { it.isNotEmpty() }?.let { repo.getPinRule(it) ?: error("규칙을 찾을 수 없습니다.") }
        val usage = existing?.let { repo.pinRuleUsageCount(it.id) } ?: 0L
        val requestedSource = PinRuleSource.valueOf(json.getString("source"))
        // The detection source of a system rule, or of one that already produced pins, is fixed.
        val source = if (existing != null && (existing.system || usage > 0)) existing.source else requestedSource
        val typeId = json.getString("typeId")
        require(repo.getPinType(typeId) != null) { "압정 타입을 찾을 수 없습니다." }
        val conditions = json.optJSONArray("conditions").objects().map { c ->
            RuleCondition(
                id = c.optString("id").ifEmpty { UUID.randomUUID().toString() },
                field = RuleConditionField.valueOf(c.getString("field")),
                operator = RuleConditionOperator.valueOf(c.getString("operator")),
                value = c.getString("value"),
            ).also { require(conditionValueValid(it)) { "조건 값을 확인하세요: ${it.value}" } }
        }
        val appMode = AppSelectionMode.valueOf(json.getString("appMode"))
        val packages = json.optJSONArray("packages").strings().toSet()
        require(source != PinRuleSource.NOTIFICATION || appMode == AppSelectionMode.EXCLUDE_SELECTED || packages.isNotEmpty()) {
            "‘선택한 앱만’은 한 개 이상의 앱을 선택해야 합니다."
        }
        val priority = existing?.priority ?: ((
            repo.pinRules()
                .filter { it.source == source && !it.system && it.priority < 10_000 }
                .maxOfOrNull { it.priority } ?: 90
            ) + 10)
        val rule = PinRule(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = json.getString("name").trim().ifBlank { "압정 룰" },
            source = source,
            system = existing?.system ?: false,
            enabled = existing?.enabled ?: true,
            hidden = existing?.hidden ?: false,
            priority = priority,
            pinTypeId = typeId,
            titleOverride = json.optString("titleOverride").trim(),
            bodyTemplate = json.optString("bodyTemplate").trim(),
            appSelectionMode = appMode,
            packageNames = packages,
            conditionJoin = RuleConditionJoin.valueOf(json.getString("join")),
            conditions = conditions,
        )
        repo.upsertPinRule(rule)
        refreshRoutineShortcutsIfNeeded(rule)
        // A rule moved away from ROUTINE must drop its shortcut too.
        if (existing?.source == PinRuleSource.ROUTINE && rule.source != PinRuleSource.ROUTINE) graph.routineShortcutManager.refresh()
    }

    private suspend fun moveRule(id: String, delta: Int) {
        val repo = graph.repository
        val rules = repo.pinRules()
        val rule = rules.firstOrNull { it.id == id } ?: error("규칙을 찾을 수 없습니다.")
        val sameSource = rules.filter { it.source == rule.source }.sortedBy { it.priority }
        val from = sameSource.indexOfFirst { it.id == id }
        val to = (from + delta).coerceIn(0, sameSource.lastIndex)
        if (to == from) return
        val other = sameSource[to]
        repo.upsertPinRule(rule.copy(priority = other.priority))
        repo.upsertPinRule(other.copy(priority = rule.priority))
    }

    private suspend fun savePinType(json: JSONObject) {
        val name = json.getString("name").trim()
        val color = json.getString("color").trim().uppercase()
        require(name.isNotEmpty()) { "타입 이름을 입력하세요." }
        require(COLOR_HEX.matches(color)) { "색상은 #RRGGBB 형식이어야 합니다." }
        val id = json.optString("id").takeIf { it.isNotEmpty() }
            ?.also { require(graph.repository.getPinType(it) != null) { "타입을 찾을 수 없습니다." } }
            ?: UUID.randomUUID().toString()
        graph.repository.upsertPinType(
            PinType(id = id, name = name, colorHex = color, icon = PinIcon.valueOf(json.getString("icon"))),
        )
    }

    private suspend fun getInstalledApps(): JSONArray = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        JSONArray().also { arr ->
            pm.getInstalledApplications(0)
                .map { pm.getApplicationLabel(it).toString() to it.packageName }
                .sortedBy { it.first.lowercase() }
                .forEach { (label, pkg) -> arr.put(JSONObject().put("label", label).put("packageName", pkg)) }
        }
    }

    private suspend fun refreshRoutineShortcutsIfNeeded(rule: PinRule) {
        if (rule.source == PinRuleSource.ROUTINE) graph.routineShortcutManager.refresh()
    }

    // ---- Permissions -------------------------------------------------------------

    private fun permissionsJson() = JSONObject()
        .put("location", hasLocationPermission())
        .put("backgroundLocation", granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        .put("sensitive", granted(Manifest.permission.READ_SMS) && granted(Manifest.permission.READ_CALL_LOG))
        .put("notificationAccess", NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName))

    private suspend fun requestPermission(kind: String) {
        val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        when (kind) {
            "location" -> {
                host.requestPermissions(LOCATION_PERMISSIONS)
                if (hasLocationPermission() && graph.preferences.current().trackingEnabled) TrackingService.start(context)
            }
            "sensitive" -> host.requestPermissions(SENSITIVE_PERMISSIONS)
            // Android only grants "allow all the time" from the app's settings page.
            "backgroundLocation", "appSettings" -> withContext(Dispatchers.Main) { host.startActivity(appDetails) }
            "notificationAccess" -> withContext(Dispatchers.Main) { host.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            "batteryOptimization" -> withContext(Dispatchers.Main) {
                runCatching {
                    host.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
                }
            }
            else -> error("알 수 없는 권한입니다.")
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission(): Boolean =
        granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    // ---- Offline maps --------------------------------------------------------------

    private suspend fun getOfflineMaps(): JSONObject {
        val regions = graph.offlineMapManager.list()
        return JSONObject()
            .put(
                "regions",
                JSONArray().also { arr ->
                    regions.forEach {
                        arr.put(
                            JSONObject()
                                .put("id", it.id)
                                .put("name", it.name)
                                .put("completed", it.completedResources)
                                .put("required", it.requiredResources)
                                .put("bytes", it.completedBytes)
                                .put("auto", it.auto),
                        )
                    }
                },
            )
            .put("download", graph.offlineMapManager.downloadState.value.toJson())
            .put("auto", graph.autoOfflineSync.status.value.toJson())
            .put("log", JSONArray().also { arr -> graph.offlineMapManager.recentLogs().forEach { arr.put(it.toJson()) } })
    }

    // ---- Backups ----------------------------------------------------------------------

    /** One backup/restore at a time; leaving the screen must not abort an upload or a half-applied restore. */
    private suspend fun exclusiveBackup(block: suspend () -> Any?): Any? {
        check(backupBusy.compareAndSet(false, true)) { "다른 백업 작업이 진행 중입니다." }
        try {
            // Main, like the screens these replaced: Google sign-in shows UI; the managers move I/O off it themselves.
            return withContext(NonCancellable + Dispatchers.Main) { block() }
        } finally {
            backupBusy.set(false)
        }
    }

    private fun emitProgress(progress: BackupProgress) {
        host.emit(
            "backup-progress",
            JSONObject()
                .put("phase", progress.phase)
                .put("fraction", progress.fraction.toDouble())
                .put("detail", progress.detail ?: JSONObject.NULL)
                .toString(),
        )
    }

    private suspend fun exportBackup(password: String): JSONObject {
        require(password.length >= 8) { "백업 비밀번호는 8자 이상이어야 합니다." }
        val uri = host.createDocument("nodemap-${LocalDate.now()}.nodemap") ?: return JSONObject().put("cancelled", true)
        val chars = password.toCharArray()
        try {
            graph.backupManager.export(uri, chars, ::emitProgress)
        } finally {
            chars.fill('\u0000')
        }
        return JSONObject().put("cancelled", false)
    }

    private suspend fun importBackup(password: String): JSONObject {
        require(password.length >= 8) { "백업 비밀번호는 8자 이상이어야 합니다." }
        val uri = host.openDocument() ?: return JSONObject().put("cancelled", true)
        val chars = password.toCharArray()
        try {
            runCatching { graph.backupManager.restore(uri, chars, ::emitProgress) }
                .getOrElse { error("복원 실패: 비밀번호 또는 파일을 확인하세요.") }
            graph.routineShortcutManager.refresh()
        } finally {
            chars.fill('\u0000')
        }
        return JSONObject().put("cancelled", false)
    }

    private companion object {
        val COLOR_HEX = Regex("^#[0-9A-F]{6}$")

        val LOCATION_PERMISSIONS = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()

        val SENSITIVE_PERMISSIONS = buildList {
            add(Manifest.permission.READ_SMS)
            add(Manifest.permission.RECEIVE_SMS)
            add(Manifest.permission.READ_CALL_LOG)
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.READ_CONTACTS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                @Suppress("DEPRECATION")
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }
}

private fun conditionValueValid(condition: RuleCondition): Boolean {
    if (condition.value.isBlank()) return false
    return when (condition.operator) {
        RuleConditionOperator.REGEX, RuleConditionOperator.NOT_REGEX -> runCatching { Regex(condition.value) }.isSuccess
        RuleConditionOperator.GREATER_OR_EQUAL, RuleConditionOperator.LESS_OR_EQUAL -> condition.value.toDoubleOrNull() != null
        else -> true
    }
}

private fun JSONArray?.objects(): List<JSONObject> = this?.let { arr -> List(arr.length()) { arr.getJSONObject(it) } }.orEmpty()

private fun JSONArray?.strings(): List<String> = this?.let { arr -> List(arr.length()) { arr.getString(it) } }.orEmpty()

internal fun AppSettings.toJson() = JSONObject()
    .put("onboardingCompleted", onboardingCompleted)
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
    .put("autoOfflineMaps", autoOfflineMaps)

internal fun OfflineDownloadState.toJson(): JSONObject = when (this) {
    OfflineDownloadState.Idle -> JSONObject().put("state", "idle")
    is OfflineDownloadState.Downloading -> JSONObject()
        .put("state", "downloading").put("name", name).put("completed", completed).put("required", required).put("bytes", bytes)
        .put("auto", auto).put("lastTileAt", lastTileAt.takeIf { it > 0 } ?: JSONObject.NULL)
    is OfflineDownloadState.Complete -> JSONObject().put("state", "complete").put("name", name).put("bytes", bytes)
    is OfflineDownloadState.Failed -> JSONObject().put("state", "failed").put("message", message)
}

internal fun AutoSyncStatus.toJson(): JSONObject = JSONObject()
    .put("enabled", enabled)
    .put("foreground", foreground)
    .put("wifi", wifi)

private fun DownloadLogEntry.toJson() = JSONObject()
    .put("id", id)
    .put("name", name)
    .put("auto", auto)
    .put("startedAt", startedAt)
    .put("updatedAt", updatedAt)
    .put("lastTileAt", lastTileAt ?: JSONObject.NULL)
    .put("endedAt", endedAt ?: JSONObject.NULL)
    .put("tiles", tiles)
    .put("bytes", bytes)
    .put("remaining", remaining)
    .put("result", result)
    .put("message", message ?: JSONObject.NULL)

private fun RemoteBackupMetadata.toJson() = JSONObject()
    .put("id", id)
    .put("createdAt", createdAt.toEpochMilli())
    .put("expiresAt", expiresAt.toEpochMilli())
    .put("sizeBytes", sizeBytes)

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

private fun PinType.toJson() = JSONObject()
    .put("id", id)
    .put("name", name)
    .put("color", colorHex)
    .put("icon", icon.name)

private fun PinRule.toJson(usage: Long) = JSONObject()
    .put("id", id)
    .put("name", name)
    .put("source", source.name)
    .put("system", system)
    .put("enabled", enabled)
    .put("hidden", hidden)
    .put("priority", priority)
    .put("typeId", pinTypeId)
    .put("titleOverride", titleOverride)
    .put("bodyTemplate", bodyTemplate)
    .put("appMode", appSelectionMode.name)
    .put("packages", JSONArray(packageNames.toList()))
    .put("join", conditionJoin.name)
    .put(
        "conditions",
        JSONArray().also { arr ->
            conditions.forEach {
                arr.put(JSONObject().put("id", it.id).put("field", it.field.name).put("operator", it.operator.name).put("value", it.value))
            }
        },
    )
    .put("usage", usage)
