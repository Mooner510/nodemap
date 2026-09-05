package kr.mooner510.events

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import androidx.core.content.ContextCompat
import kr.mooner510.appGraph
import kr.mooner510.data.EventType
import kr.mooner510.data.NodeMapRepository
import kr.mooner510.data.PinRuleMatchContext
import kr.mooner510.data.PinRuleSource
import kr.mooner510.data.TimelineEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

class MessageObserver(
    private val context: Context,
    private val repository: NodeMapRepository,
    private val scope: CoroutineScope,
) {
    private val smsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) { scope.launch { syncSms(false) } }
    }
    private val mmsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) { scope.launch { syncMms(false) } }
    }

    fun start() {
        if (!hasReadSms()) return
        context.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, smsObserver)
        context.contentResolver.registerContentObserver(Telephony.Mms.CONTENT_URI, true, mmsObserver)
        scope.launch {
            initialize("last_sms_id", Telephony.Sms.CONTENT_URI)
            initialize("last_mms_id", Telephony.Mms.CONTENT_URI)
        }
    }

    fun stop() {
        runCatching { context.contentResolver.unregisterContentObserver(smsObserver) }
        runCatching { context.contentResolver.unregisterContentObserver(mmsObserver) }
    }

    suspend fun syncSms(triggeredByBroadcast: Boolean) {
        if (!hasReadSms()) return
        if (repository.state("last_sms_id") == null) {
            val max = maxId(Telephony.Sms.CONTENT_URI)
            repository.setState("last_sms_id", if (triggeredByBroadcast) (max - 1).coerceAtLeast(0).toString() else max.toString())
            if (!triggeredByBroadcast) return
        }
        val last = repository.state("last_sms_id")?.toLongOrNull() ?: 0
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
            "${Telephony.Sms._ID} > ?",
            arrayOf(last.toString()),
            "${Telephony.Sms._ID} ASC",
        ) ?: return
        var newest = last
        cursor.use { row ->
            while (row.moveToNext()) {
                val id = row.getLong(0)
                newest = maxOf(newest, id)
                val address = row.getString(1).orEmpty()
                val body = row.getString(2).orEmpty()
                val timestamp = row.getLong(3).takeIf { it > 0 } ?: System.currentTimeMillis()
                val type = row.getInt(4)
                val direction = when (type) {
                    Telephony.Sms.MESSAGE_TYPE_INBOX -> "수신"
                    Telephony.Sms.MESSAGE_TYPE_SENT -> "발신"
                    Telephony.Sms.MESSAGE_TYPE_OUTBOX -> "발신 대기"
                    else -> "메시지"
                }
                val name = ContactResolver.displayName(context, address).orEmpty()
                val rawTitle = "$direction SMS · ${name.ifBlank { address.ifBlank { "알 수 없음" } }}"
                val rule = repository.selectPinRule(
                    PinRuleSource.SMS,
                    PinRuleMatchContext(
                        title = rawTitle,
                        body = body,
                        contactName = name,
                        address = address,
                        direction = direction,
                        source = "android.provider.Telephony.Sms",
                    ),
                ) ?: continue
                val point = repository.nearestTrackPoint(timestamp)
                repository.insertEvent(
                    TimelineEvent(
                        timestamp = timestamp,
                        type = EventType.SMS,
                        latitude = point?.latitude,
                        longitude = point?.longitude,
                        title = rawTitle,
                        body = body.takeIf { it.isNotBlank() },
                        source = "android.provider.Telephony.Sms",
                        metadata = mapOf(
                            "providerId" to id.toString(),
                            "address" to address,
                            "contactName" to name,
                            "direction" to direction,
                            "messageType" to type.toString(),
                            "ruleId" to rule.id,
                        ).filterValues { it.isNotBlank() },
                        pinRuleId = rule.id,
                    ),
                )
            }
        }
        if (newest > last) repository.setState("last_sms_id", newest.toString())
    }

    suspend fun syncMms(triggeredByBroadcast: Boolean) {
        if (!hasReadSms()) return
        if (repository.state("last_mms_id") == null) {
            val max = maxId(Telephony.Mms.CONTENT_URI)
            repository.setState("last_mms_id", if (triggeredByBroadcast) (max - 1).coerceAtLeast(0).toString() else max.toString())
            if (!triggeredByBroadcast) return
        }
        val last = repository.state("last_mms_id")?.toLongOrNull() ?: 0
        val cursor = context.contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX, Telephony.Mms.SUBJECT),
            "${Telephony.Mms._ID} > ?",
            arrayOf(last.toString()),
            "${Telephony.Mms._ID} ASC",
        ) ?: return
        var newest = last
        cursor.use { row ->
            while (row.moveToNext()) {
                val id = row.getLong(0)
                newest = maxOf(newest, id)
                val timestamp = row.getLong(1).let { if (it < 10_000_000_000L) it * 1_000L else it }
                val box = row.getInt(2)
                val subject = row.getString(3).orEmpty()
                val direction = when (box) {
                    Telephony.Mms.MESSAGE_BOX_INBOX -> "수신"
                    Telephony.Mms.MESSAGE_BOX_SENT -> "발신"
                    Telephony.Mms.MESSAGE_BOX_OUTBOX -> "발신 대기"
                    else -> "메시지"
                }
                val address = mmsAddress(id, box)
                val name = ContactResolver.displayName(context, address).orEmpty()
                val body = mmsText(id).ifBlank { subject }
                val rawTitle = "$direction MMS · ${name.ifBlank { address.ifBlank { "알 수 없음" } }}"
                val rule = repository.selectPinRule(
                    PinRuleSource.MMS,
                    PinRuleMatchContext(
                        title = rawTitle,
                        body = body,
                        contactName = name,
                        address = address,
                        direction = direction,
                        source = "android.provider.Telephony.Mms",
                    ),
                ) ?: continue
                val point = repository.nearestTrackPoint(timestamp)
                repository.insertEvent(
                    TimelineEvent(
                        timestamp = timestamp,
                        type = EventType.MMS,
                        latitude = point?.latitude,
                        longitude = point?.longitude,
                        title = rawTitle,
                        body = body.takeIf { it.isNotBlank() },
                        source = "android.provider.Telephony.Mms",
                        metadata = mapOf(
                            "providerId" to id.toString(),
                            "address" to address,
                            "contactName" to name,
                            "direction" to direction,
                            "messageBox" to box.toString(),
                            "subject" to subject,
                            "ruleId" to rule.id,
                        ).filterValues { it.isNotBlank() },
                        pinRuleId = rule.id,
                    ),
                )
            }
        }
        if (newest > last) repository.setState("last_mms_id", newest.toString())
    }

    private fun mmsAddress(id: Long, box: Int): String {
        val wanted = if (box == Telephony.Mms.MESSAGE_BOX_INBOX) 137 else 151
        return context.contentResolver.query(
            Uri.parse("content://mms/$id/addr"),
            arrayOf("address", "type"),
            null,
            null,
            null,
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val address = cursor.getString(0).orEmpty()
                    if (cursor.getInt(1) == wanted && address != "insert-address-token") add(address)
                }
            }.joinToString(", ")
        }.orEmpty()
    }

    private fun mmsText(id: Long): String = context.contentResolver.query(
        Uri.parse("content://mms/part"),
        arrayOf("_id", "ct", "text", "_data"),
        "mid = ? AND ct = ?",
        arrayOf(id.toString(), "text/plain"),
        null,
    )?.use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val part = cursor.getString(0)
                val text = cursor.getString(2)
                val data = cursor.getString(3)
                when {
                    !text.isNullOrBlank() -> add(text)
                    !data.isNullOrBlank() -> runCatching {
                        context.contentResolver.openInputStream(Uri.parse("content://mms/part/$part"))
                            ?.use { BufferedReader(InputStreamReader(it)).readText() }
                    }.getOrNull()?.takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.joinToString("\n")
    }.orEmpty()

    private suspend fun initialize(key: String, uri: Uri) {
        if (repository.state(key) == null) repository.setState(key, maxId(uri).toString())
    }

    private fun maxId(uri: Uri): Long = context.contentResolver.query(
        uri, arrayOf("MAX(_id)"), null, null, null,
    )?.use { if (it.moveToFirst()) it.getLong(0) else 0 } ?: 0

    private fun hasReadSms() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
        PackageManager.PERMISSION_GRANTED
}

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { MessageObserver(context, context.appGraph.repository, this).syncSms(true) } finally { pending.finish() }
        }
    }
}

class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_RECEIVED_ACTION) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { MessageObserver(context, context.appGraph.repository, this).syncMms(true) } finally { pending.finish() }
        }
    }
}
