package kr.mooner510.routine

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import kr.mooner510.appGraph
import kr.mooner510.data.EventType
import kr.mooner510.data.PinRuleSource
import kr.mooner510.data.TimelineEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume

class RoutineActionActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(RoutineShortcutManager.EXTRA_TEMPLATE_ID)
        if (intent.action != RoutineShortcutManager.ACTION_PIN_TEMPLATE || id.isNullOrBlank()) {
            finish()
            return
        }
        scope.launch(Dispatchers.IO) {
            runCatching {
                val rule = appGraph.repository.getPinRule(id)
                    ?.takeIf { it.source == PinRuleSource.ROUTINE && it.enabled }
                    ?: return@runCatching
                val now = System.currentTimeMillis()
                val latest = appGraph.repository.latestTrackPoint()
                val location = if (latest != null && now - latest.timestamp <= 120_000L) {
                    latest.latitude to latest.longitude
                } else {
                    currentLocation()?.let { it.latitude to it.longitude }
                }
                appGraph.repository.insertEvent(
                    TimelineEvent(
                        timestamp = now,
                        type = EventType.PIN_ROUTINE,
                        latitude = location?.first,
                        longitude = location?.second,
                        title = rule.titleOverride.ifBlank { rule.name },
                        body = rule.bodyTemplate.takeIf { it.isNotBlank() },
                        source = "GALAXY_ROUTINE",
                        metadata = mapOf("ruleId" to rule.id, "templateId" to rule.id),
                        pinRuleId = rule.id,
                    ),
                )
            }
            runOnUiThread { finish() }
        }
    }

    private suspend fun currentLocation(): Location? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val manager = getSystemService(LocationManager::class.java)
        val provider = when {
            manager.allProviders.contains(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> LocationManager.NETWORK_PROVIDER
        }
        return suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            manager.getCurrentLocation(provider, signal, Executor { it.run() }) {
                if (continuation.isActive) continuation.resume(it)
            }
        }
    }
}
