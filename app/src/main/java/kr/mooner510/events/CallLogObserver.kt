package kr.mooner510.events

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import androidx.core.content.ContextCompat
import kr.mooner510.data.EventType
import kr.mooner510.data.NodeMapRepository
import kr.mooner510.data.PinRuleMatchContext
import kr.mooner510.data.PinRuleSource
import kr.mooner510.data.TimelineEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CallLogObserver(
    private val context: Context,
    private val repository: NodeMapRepository,
    private val scope: CoroutineScope,
) {
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { scope.launch { sync() } }
    }
    private val linker = RecordingLinker(context)

    fun start() {
        if (!hasPermission()) return
        context.contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer)
        scope.launch { initializeBaselineIfNeeded() }
    }

    fun stop() { runCatching { context.contentResolver.unregisterContentObserver(observer) } }

    suspend fun sync() {
        if (!hasPermission()) return
        val state = repository.state("last_call_id") ?: run { initializeBaselineIfNeeded(); return }
        val last = state.toLongOrNull() ?: 0
        val cursor = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                CallLog.Calls.TYPE,
            ),
            "${CallLog.Calls._ID} > ?",
            arrayOf(last.toString()),
            "${CallLog.Calls._ID} ASC",
        ) ?: return

        var newest = last
        cursor.use { row ->
            while (row.moveToNext()) {
                val id = row.getLong(0)
                newest = maxOf(newest, id)
                val number = row.getString(1).orEmpty()
                val cached = row.getString(2).orEmpty()
                val timestamp = row.getLong(3)
                val duration = row.getLong(4)
                val callType = row.getInt(5)
                val direction = callTypeLabel(callType)
                val contact = cached.ifBlank { ContactResolver.displayName(context, number).orEmpty() }
                val display = contact.ifBlank { number }.ifBlank { "알 수 없음" }
                val rawTitle = "$direction 통화 · $display"
                val rawBody = formatDuration(duration)
                val rule = repository.selectPinRule(
                    PinRuleSource.PHONE_CALL,
                    PinRuleMatchContext(
                        title = rawTitle,
                        body = rawBody,
                        contactName = contact,
                        phoneNumber = number,
                        direction = direction,
                        durationSeconds = duration,
                        source = "android.provider.CallLog",
                    ),
                ) ?: continue
                val point = repository.nearestTrackPoint(timestamp)
                val event = TimelineEvent(
                    timestamp = timestamp,
                    type = EventType.PHONE_CALL,
                    latitude = point?.latitude,
                    longitude = point?.longitude,
                    title = rawTitle,
                    body = rawBody,
                    source = "android.provider.CallLog",
                    metadata = mapOf(
                        "providerId" to id.toString(),
                        "number" to number,
                        "contactName" to contact,
                        "direction" to direction,
                        "callType" to callType.toString(),
                        "durationSeconds" to duration.toString(),
                        "ruleId" to rule.id,
                    ).filterValues { it.isNotBlank() },
                    pinRuleId = rule.id,
                )
                repository.insertEvent(event)
                scope.launch { attachRecording(event.id, timestamp, duration) }
            }
        }
        if (newest > last) repository.setState("last_call_id", newest.toString())
    }

    private suspend fun attachRecording(eventId: String, start: Long, duration: Long) {
        for (wait in longArrayOf(3_000, 20_000, 60_000)) {
            delay(wait)
            val uri = linker.findLikelyRecording(start, duration) ?: continue
            if (repository.attachmentsForEvent(eventId).none { it.kind == "call_recording" && it.externalUri == uri.toString() }) {
                repository.addExternalAttachment(eventId, "call_recording", linker.mimeType(uri), uri.toString())
            }
            return
        }
    }

    private suspend fun initializeBaselineIfNeeded() {
        if (repository.state("last_call_id") != null || !hasPermission()) return
        val max = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf("MAX(${CallLog.Calls._ID})"),
            null,
            null,
            null,
        )?.use { if (it.moveToFirst()) it.getLong(0) else 0 } ?: 0
        repository.setState("last_call_id", max.toString())
    }

    private fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
        PackageManager.PERMISSION_GRANTED

    private fun callTypeLabel(type: Int) = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "수신"
        CallLog.Calls.OUTGOING_TYPE -> "발신"
        CallLog.Calls.MISSED_TYPE -> "부재중"
        CallLog.Calls.REJECTED_TYPE -> "거절"
        CallLog.Calls.BLOCKED_TYPE -> "차단"
        else -> "전화"
    }

    private fun formatDuration(seconds: Long) =
        if (seconds >= 60) "${seconds / 60}분 ${seconds % 60}초" else "${seconds}초"
}
