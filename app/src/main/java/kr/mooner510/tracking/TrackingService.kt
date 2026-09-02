package kr.mooner510.tracking

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kr.mooner510.MainActivity
import kr.mooner510.R
import kr.mooner510.appGraph
import kr.mooner510.data.TrackPoint
import kr.mooner510.data.TrackingPreset
import kr.mooner510.events.CallLogObserver
import kr.mooner510.events.MessageObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.Executor
import kotlin.math.max

class TrackingService : Service(), LocationListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val graph get() = appGraph

    private lateinit var locationManager: LocationManager
    private lateinit var messages: MessageObserver
    private lateinit var calls: CallLogObserver

    private var currentProfile: RequestProfile? = null
    private var stationary = false
    private var lastLocation: Location? = null
    private var lastMovementAt = 0L
    private var settingsJob: Job? = null
    private val executor = Executor { command -> scope.launch { command.run() } }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        locationManager = getSystemService(LocationManager::class.java)
        messages = MessageObserver(this, graph.repository, scope)
        calls = CallLogObserver(this, graph.repository, scope)
        messages.start()
        calls.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            510,
            notification("위치 권한을 확인하는 중"),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )

        settingsJob?.cancel()
        settingsJob = scope.launch {
            graph.preferences.settings.collect { settings ->
                if (!settings.trackingEnabled) {
                    stopSelf()
                    return@collect
                }

                profileFor(settings.trackingPreset, stationary)
                    .takeIf { it != currentProfile }
                    ?.let { requestUpdates(it) }
            }
        }
        return START_STICKY
    }

    override fun onLocationChanged(location: Location) {
        val now = System.currentTimeMillis()
        updateMovement(location, now)

        lastLocation?.let { previous ->
            if (
                location.time - previous.time < 1_500 &&
                location.distanceTo(previous) < 1.5f &&
                location.accuracy >= previous.accuracy
            ) {
                return
            }
        }

        lastLocation = location
        scope.launch {
            graph.repository.insertTrackPoint(
                TrackPoint(
                    timestamp = location.time.takeIf { it > 0 } ?: now,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracyMeters = location.accuracy,
                    altitudeMeters = location.altitude.takeIf { location.hasAltitude() },
                    speedMps = location.speed.takeIf { location.hasSpeed() },
                    bearingDegrees = location.bearing.takeIf { location.hasBearing() },
                    provider = location.provider,
                    isMock = location.isMock,
                ),
            )
            updateNotification("정확도 ±${max(1, location.accuracy.toInt())}m · 방금 기록")
        }
    }

    override fun onProviderDisabled(provider: String) {
        updateNotification("위치 공급자 꺼짐: $provider")
    }

    override fun onDestroy() {
        runCatching { locationManager.removeUpdates(this) }
        messages.stop()
        calls.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun requestUpdates(profile: RequestProfile) {
        val fineDenied = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) != PackageManager.PERMISSION_GRANTED
        val coarseDenied = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) != PackageManager.PERMISSION_GRANTED

        if (fineDenied && coarseDenied) {
            updateNotification("위치 권한 필요")
            return
        }

        runCatching { locationManager.removeUpdates(this) }

        val request = LocationRequest.Builder(profile.interval)
            .setMinUpdateIntervalMillis(profile.fastest)
            .setMinUpdateDistanceMeters(profile.distance)
            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
            .build()

        val provider = when {
            locationManager.allProviders.contains(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> LocationManager.NETWORK_PROVIDER
        }

        runCatching {
            locationManager.requestLocationUpdates(provider, request, executor, this)
            if (
                provider == LocationManager.GPS_PROVIDER &&
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            ) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    request,
                    executor,
                    this,
                )
            }
            currentProfile = profile
        }.onFailure {
            updateNotification("위치 기록 시작 실패: ${it.javaClass.simpleName}")
        }
    }

    private fun updateMovement(location: Location, now: Long) {
        val moved =
            (location.hasSpeed() && location.speed >= 0.8f) ||
                (lastLocation != null && location.distanceTo(lastLocation!!) >= 12f)

        if (moved) lastMovementAt = now

        val nextStationary = lastMovementAt > 0 && now - lastMovementAt >= 180_000
        if (nextStationary != stationary) {
            stationary = nextStationary
            scope.launch {
                requestUpdates(
                    profileFor(graph.preferences.current().trackingPreset, stationary),
                )
            }
        }
    }

    private fun profileFor(preset: TrackingPreset, isStationary: Boolean): RequestProfile = when (preset) {
        TrackingPreset.PRECISE -> if (isStationary) {
            RequestProfile(20_000, 5_000, 5f)
        } else {
            RequestProfile(3_000, 1_000, 2f)
        }

        TrackingPreset.BALANCED -> if (isStationary) {
            RequestProfile(45_000, 10_000, 15f)
        } else {
            RequestProfile(8_000, 2_000, 5f)
        }

        TrackingPreset.BATTERY -> if (isStationary) {
            RequestProfile(180_000, 60_000, 50f)
        } else {
            RequestProfile(30_000, 10_000, 15f)
        }
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                "nodemap_tracking",
                getString(R.string.tracking_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
            },
        )
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(510, notification(text))
    }

    private fun notification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, "nodemap_tracking")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.tracking_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private data class RequestProfile(
        val interval: Long,
        val fastest: Long,
        val distance: Float,
    )

    companion object {
        fun start(context: android.content.Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TrackingService::class.java),
            )
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, TrackingService::class.java))
        }
    }
}
