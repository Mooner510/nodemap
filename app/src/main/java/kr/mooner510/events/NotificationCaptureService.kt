package kr.mooner510.events

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kr.mooner510.appGraph
import kr.mooner510.data.EventType
import kr.mooner510.data.PinRuleMatchContext
import kr.mooner510.data.PinRuleSource
import kr.mooner510.data.TimelineEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class NotificationCaptureService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        scope.launch { capture(sbn) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun capture(sbn: StatusBarNotification) {
        val notification = sbn.notification ?: return
        if (sbn.packageName == packageName) return
        val extras = notification.extras ?: Bundle.EMPTY
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val summary = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString().orEmpty()
        val body = big.ifBlank { text }
        val appLabel = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        val rule = appGraph.repository.selectPinRule(
            PinRuleSource.NOTIFICATION,
            PinRuleMatchContext(
                packageName = sbn.packageName,
                appLabel = appLabel,
                title = title,
                body = listOf(body, sub, summary).filter { it.isNotBlank() }.joinToString("\n"),
                source = sbn.packageName,
            ),
        ) ?: return

        val time = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis()
        val point = appGraph.repository.nearestTrackPoint(time)
        val event = TimelineEvent(
            timestamp = time,
            type = EventType.NOTIFICATION,
            latitude = point?.latitude,
            longitude = point?.longitude,
            title = title.ifBlank { appLabel },
            body = body.takeIf { it.isNotBlank() },
            source = sbn.packageName,
            metadata = mapOf(
                "appLabel" to appLabel,
                "notificationKey" to sbn.key,
                "notificationId" to sbn.id.toString(),
                "ruleId" to rule.id,
                "subText" to sub,
                "summaryText" to summary,
            ).filterValues { it.isNotBlank() },
            pinRuleId = rule.id,
        )
        appGraph.repository.insertEvent(event)

        val candidates = buildList<Pair<String, Any>> {
            notification.smallIcon?.let { add("small_icon" to it) }
            extractIcon(extras, Notification.EXTRA_LARGE_ICON)?.let { add("large_icon" to it) }
            extractBitmap(extras, Notification.EXTRA_PICTURE)?.let { add("picture" to it) }
        }
        candidates.forEach { (kind, visual) ->
            runCatching {
                val bitmap = when (visual) {
                    is Bitmap -> visual
                    is Icon -> visual.loadDrawable(this@NotificationCaptureService)?.toBitmap()
                    else -> null
                } ?: return@runCatching
                val bytes = ByteArrayOutputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.toByteArray()
                }
                appGraph.repository.addEncryptedAttachment(event.id, kind, "image/png", ByteArrayInputStream(bytes))
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun extractIcon(bundle: Bundle, key: String): Icon? = when (val value = bundle.getParcelable<android.os.Parcelable>(key)) {
        is Icon -> value
        is Bitmap -> Icon.createWithBitmap(value)
        else -> null
    }

    @Suppress("DEPRECATION")
    private fun extractBitmap(bundle: Bundle, key: String): Bitmap? = when (val value = bundle.getParcelable<android.os.Parcelable>(key)) {
        is Bitmap -> value
        is Icon -> value.loadDrawable(this)?.toBitmap()
        else -> null
    }

    private fun Drawable.toBitmap(): Bitmap {
        if (this is BitmapDrawable && bitmap != null) return bitmap
        val width = intrinsicWidth.takeIf { it > 0 } ?: 96
        val height = intrinsicHeight.takeIf { it > 0 } ?: 96
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            setBounds(0, 0, canvas.width, canvas.height)
            draw(canvas)
        }
    }
}
