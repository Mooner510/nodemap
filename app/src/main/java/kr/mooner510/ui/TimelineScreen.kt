package kr.mooner510.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Paint
import android.location.Location
import android.os.SystemClock
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Message
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.mooner510.appGraph
import kr.mooner510.data.AppSettings
import kr.mooner510.data.EventType
import kr.mooner510.data.PinIcon
import kr.mooner510.data.PinType
import kr.mooner510.data.ResolvedPin
import kr.mooner510.data.SYSTEM_TYPE_GENERAL
import kr.mooner510.data.TimelineEvent
import kr.mooner510.data.TrackPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.annotations.Icon as MapIcon
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

private const val MINUTE_MS = 60_000L
private const val MAP_PREVIEW_INTERVAL_MS = 220L
private const val PRECISION_HOLD_MS = 1_000L
private const val PRECISION_SCALE = 4f
private const val DIAL_FLING_FRICTION_PER_SECOND = 3.0
private const val DIAL_FLING_MIN_SCREENS_PER_SECOND = 0.35f
private const val DIAL_FLING_MAX_SCREENS_PER_SECOND = 6f
private const val DIAL_FLING_STOP_SCREENS_PER_SECOND = 0.06f
private const val ROUTE_GAP_THRESHOLD_MS = 90_000L
private const val ROUTE_GAP_MIN_DISTANCE_METERS = 25f
private const val ROUTE_OLD_SOURCE_ID = "nodemap-timeline-route-old-source"
private const val ROUTE_OLD_CASING_LAYER_ID = "nodemap-timeline-route-old-casing"
private const val ROUTE_OLD_LAYER_ID = "nodemap-timeline-route-old"
private const val ROUTE_DETAIL_SOURCE_ID = "nodemap-timeline-route-detail-source"
private const val ROUTE_DETAIL_CASING_LAYER_ID = "nodemap-timeline-route-detail-casing"
private const val ROUTE_DETAIL_LAYER_ID = "nodemap-timeline-route-detail"
private const val ROUTE_GAP_SOURCE_ID = "nodemap-timeline-route-gap-source"
private const val ROUTE_GAP_CASING_LAYER_ID = "nodemap-timeline-route-gap-casing"
private const val ROUTE_GAP_LAYER_ID = "nodemap-timeline-route-gap"
private val ROUTE_OLD_COLOR = android.graphics.Color.rgb(59, 130, 246)
private val ROUTE_DETAIL_COLOR = android.graphics.Color.rgb(29, 78, 216)
private val ROUTE_GAP_COLOR = android.graphics.Color.rgb(245, 158, 11)
private val ROUTE_CASING_COLOR = android.graphics.Color.argb(204, 255, 255, 255)
private val timelineTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val timelineDateFormatter = DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREAN)

@Composable
fun TimelineScreen() {
    val context = LocalContext.current
    val graph = context.appGraph
    val scope = rememberCoroutineScope()
    val settings by graph.preferences.settings.collectAsStateWithLifecycle(initialValue = AppSettings())

    var selectedTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var previewTime by remember { mutableLongStateOf(selectedTime) }
    var scrubbing by remember { mutableStateOf(false) }
    var points by remember { mutableStateOf(emptyList<TrackPoint>()) }
    var pins by remember { mutableStateOf(emptyList<ResolvedPin>()) }
    var latestPoint by remember { mutableStateOf<TrackPoint?>(null) }
    var pinTypes by remember { mutableStateOf(emptyList<PinType>()) }
    var availableDays by remember { mutableStateOf(emptyList<LocalDate>()) }
    var initialLoaded by remember { mutableStateOf(false) }
    var dayPickerOpen by remember { mutableStateOf(false) }
    var manualPinOpen by remember { mutableStateOf(false) }
    var recenterRequest by remember { mutableIntStateOf(0) }
    var fitRequest by remember { mutableIntStateOf(0) }

    val displayTime = if (scrubbing) previewTime else selectedTime
    val displayDate = remember(displayTime) { displayTime.toTimelineDate() }
    val displayDateState = rememberUpdatedState(displayDate)
    val settingsState = rememberUpdatedState(settings)

    suspend fun reload(day: LocalDate) {
        val current = settingsState.value
        val zone = ZoneId.systemDefault()
        val before = max(
            current.totalWindowMinutes,
            max(current.detailWindowMinutes, current.cameraWindowMinutes),
        ) + current.dialRadiusMinutes + 60
        val after = current.dialRadiusMinutes + 60
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli() - before * MINUTE_MS
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() + after * MINUTE_MS
        points = graph.repository.trackPointsBetween(start, end)
        pins = graph.repository.resolvedPinsBetween(start, end)
        latestPoint = graph.repository.latestTrackPoint()
        pinTypes = graph.repository.pinTypes()
        availableDays = graph.repository.dataDays()
    }

    LaunchedEffect(Unit) {
        latestPoint = graph.repository.latestTrackPoint()
        pinTypes = graph.repository.pinTypes()
        availableDays = graph.repository.dataDays()
        initialLoaded = true
    }
    LaunchedEffect(
        initialLoaded,
        displayDate,
        settings.totalWindowMinutes,
        settings.detailWindowMinutes,
        settings.cameraWindowMinutes,
        settings.dialRadiusMinutes,
    ) {
        if (initialLoaded) reload(displayDate)
    }
    LaunchedEffect(Unit) {
        graph.repository.changes.collect { reload(displayDateState.value) }
    }

    val now = System.currentTimeMillis()
    val rangeStart = availableDays.minOrNull()
        ?.atStartOfDay(ZoneId.systemDefault())
        ?.toInstant()
        ?.toEpochMilli()
        ?: now - settings.totalWindowMinutes * MINUTE_MS

    Column(Modifier.fillMaxSize()) {
        TimelineDateHeader(
            date = displayDate,
            enabled = availableDays.isNotEmpty(),
            onClick = { dayPickerOpen = true },
        )

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .clip(RoundedCornerShape(24.dp)),
        ) {
            if (!initialLoaded) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Box(contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            } else {
                TimelineMap(
                    modifier = Modifier.fillMaxSize(),
                    points = points,
                    pins = pins,
                    renderTime = displayTime,
                    latestPoint = latestPoint,
                    styleUri = settings.mapStyleUri,
                    detailWindowMinutes = settings.detailWindowMinutes,
                    totalWindowMinutes = settings.totalWindowMinutes,
                    cameraWindowMinutes = settings.cameraWindowMinutes,
                    scrubbing = scrubbing,
                    recenterRequest = recenterRequest,
                    fitRequest = fitRequest,
                )
            }

            TrackingStatusPill(
                settings = settings,
                latestPoint = latestPoint,
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
            )

            if ((now - displayTime).absoluteValue > 5 * MINUTE_MS) {
                TimelineMapAction(
                    label = "지금",
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                ) {
                    val target = System.currentTimeMillis()
                    selectedTime = target
                    previewTime = target
                    scrubbing = false
                    recenterRequest += 1
                }
            }
            if (latestPoint != null) {
                TimelineMapAction(
                    label = "내 위치",
                    modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                ) { recenterRequest += 1 }
            }
            SmallFloatingActionButton(
                onClick = { manualPinOpen = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
                shape = RoundedCornerShape(15.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "압정 추가")
            }
        }

        SelectedPinStrip(pins, displayTime)
        TimelineScrubber(
            selectedTime = selectedTime,
            pins = pins,
            tickMinutes = settings.dialTickMinutes,
            radiusMinutes = settings.dialRadiusMinutes,
            rangeStart = rangeStart,
            rangeEnd = now,
            onStart = {
                scrubbing = true
                previewTime = selectedTime
            },
            onPreview = {
                previewTime = it
                scrubbing = true
            },
            onCommit = {
                selectedTime = it
                previewTime = it
                scrubbing = false
                fitRequest += 1
            },
        )
    }

    if (dayPickerOpen) {
        AvailableDayPickerDialog(
            availableDays = availableDays,
            initialDay = displayDate,
            onDismiss = { dayPickerOpen = false },
            onSelected = { day ->
                dayPickerOpen = false
                scope.launch {
                    val zone = ZoneId.systemDefault()
                    val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
                    val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    val dayPoints = graph.repository.trackPointsForDay(day)
                    val dayPins = graph.repository.resolvedPinsBetween(start, end)
                    val target = (dayPoints.map { it.timestamp } + dayPins.map { it.event.timestamp }).maxOrNull()
                        ?: day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
                    selectedTime = target.coerceAtMost(System.currentTimeMillis())
                    previewTime = selectedTime
                    fitRequest += 1
                }
            },
        )
    }

    if (manualPinOpen) {
        ManualPinDialog(
            types = pinTypes,
            onDismiss = { manualPinOpen = false },
            onSave = { text, typeId ->
                scope.launch {
                    val timestamp = System.currentTimeMillis()
                    val point = graph.repository.latestTrackPoint()
                        ?.takeIf { timestamp - it.timestamp <= 5 * MINUTE_MS }
                    graph.repository.insertEvent(
                        TimelineEvent(
                            timestamp = timestamp,
                            type = EventType.PIN_MANUAL,
                            latitude = point?.latitude,
                            longitude = point?.longitude,
                            title = "압정",
                            body = text,
                            source = "MANUAL",
                            pinTypeId = typeId,
                        ),
                    )
                    manualPinOpen = false
                }
            },
        )
    }
}

@Composable
private fun TimelineDateHeader(date: LocalDate, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("타임랩스", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(date.format(timelineDateFormatter), style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (enabled) "날짜를 눌러 기록이 있는 날로 이동" else "위치 기록이 쌓이면 날짜를 선택할 수 있습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(Modifier.size(42.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.CalendarMonth, contentDescription = "날짜 선택", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun TimelineMapAction(label: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        shadowElevation = 3.dp,
    ) {
        Row(Modifier.padding(horizontal = 11.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.MyLocation, contentDescription = label, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
private fun TimelineMap(
    modifier: Modifier,
    points: List<TrackPoint>,
    pins: List<ResolvedPin>,
    renderTime: Long,
    latestPoint: TrackPoint?,
    styleUri: String,
    detailWindowMinutes: Int,
    totalWindowMinutes: Int,
    cameraWindowMinutes: Int,
    scrubbing: Boolean,
    recenterRequest: Int,
    fitRequest: Int,
) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
            onStart()
            onResume()
        }
    }
    val currentIcon = remember(context) { makeTimelineDotIcon(context, 38f, ROUTE_DETAIL_COLOR) }
    val selectedIcon = remember(context) { makeTimelineDotIcon(context, 30f, android.graphics.Color.rgb(25, 31, 40)) }
    val pinIconCache = remember { mutableMapOf<String, MapIcon>() }
    var appliedStyle by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(false) }
    var initialCameraApplied by remember { mutableStateOf(false) }
    var handledRecenter by remember { mutableIntStateOf(0) }
    var handledFit by remember { mutableIntStateOf(0) }

    DisposableEffect(mapView) {
        onDispose {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    fun pinIcon(pin: ResolvedPin): MapIcon {
        val key = "${pin.pinType.id}:${pin.pinType.colorHex}"
        return pinIconCache.getOrPut(key) {
            val color = runCatching { android.graphics.Color.parseColor(pin.pinType.colorHex) }
                .getOrDefault(android.graphics.Color.rgb(49, 130, 246))
            makeTimelineDotIcon(context, 27f, color)
        }
    }

    fun fitCamera(map: MapLibreMap, animate: Boolean) {
        val start = renderTime - cameraWindowMinutes * MINUTE_MS
        val fitPoints = points.filter { it.timestamp in start..renderTime }
        if (fitPoints.isEmpty()) return
        val coordinates = fitPoints.map { LatLng(it.latitude, it.longitude) }
        val first = fitPoints.first()
        val distinct = fitPoints.any {
            (it.latitude - first.latitude).absoluteValue > 0.00001 ||
                (it.longitude - first.longitude).absoluteValue > 0.00001
        }
        val update = if (coordinates.size == 1 || !distinct) {
            CameraUpdateFactory.newLatLngZoom(coordinates.last(), 15.5)
        } else {
            CameraUpdateFactory.newLatLngBounds(
                LatLngBounds.Builder().includes(coordinates).build(),
                (36f * density).roundToInt(),
            )
        }
        if (animate) map.easeCamera(update, 140) else map.moveCamera(update)
    }

    fun render(map: MapLibreMap) {
        drawTimelineMap(
            map = map,
            points = points,
            pins = pins,
            renderTime = renderTime,
            latestPoint = latestPoint,
            detailWindowMinutes = detailWindowMinutes,
            totalWindowMinutes = totalWindowMinutes,
            currentIcon = currentIcon,
            selectedIcon = selectedIcon,
            pinIcon = ::pinIcon,
        )
        if (!initialCameraApplied) {
            latestPoint?.let {
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), 15.5))
            }
            initialCameraApplied = true
            ready = true
        }
        if (recenterRequest != handledRecenter) {
            handledRecenter = recenterRequest
            latestPoint?.let {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), 15.5), 280)
            }
        }
        if (scrubbing) {
            fitCamera(map, false)
        } else if (fitRequest != handledFit) {
            handledFit = fitRequest
            fitCamera(map, true)
        }
    }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mapView },
            update = { view ->
                view.getMapAsync { map ->
                    if (map.style == null || appliedStyle != styleUri) {
                        ready = false
                        map.setStyle(styleUri) {
                            appliedStyle = styleUri
                            initialCameraApplied = false
                            pinIconCache.clear()
                            render(map)
                        }
                    } else {
                        render(map)
                    }
                }
            },
        )
        if (!ready) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant) {
                Box(contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
}

private fun drawTimelineMap(
    map: MapLibreMap,
    points: List<TrackPoint>,
    pins: List<ResolvedPin>,
    renderTime: Long,
    latestPoint: TrackPoint?,
    detailWindowMinutes: Int,
    totalWindowMinutes: Int,
    currentIcon: MapIcon,
    selectedIcon: MapIcon,
    pinIcon: (ResolvedPin) -> MapIcon,
) {
    map.clear()
    val totalStart = renderTime - totalWindowMinutes * MINUTE_MS
    val detailStart = renderTime - detailWindowMinutes * MINUTE_MS
    val total = points.filter { it.timestamp in totalStart..renderTime }
    val routeData = buildTimelineRouteData(total, detailStart)
    renderTimelineRouteLayers(map, routeData)

    latestPoint?.let {
        map.addMarker(
            MarkerOptions()
                .position(LatLng(it.latitude, it.longitude))
                .icon(currentIcon)
                .title("현재 위치")
                .snippet("마지막 기록 ${timelineFormatTime(it.timestamp)} · ±${it.accuracyMeters.roundToInt()}m"),
        )
    }
    points.minByOrNull { (it.timestamp - renderTime).absoluteValue }
        ?.takeIf { (it.timestamp - renderTime).absoluteValue <= 10 * MINUTE_MS }
        ?.takeIf { latestPoint == null || (it.timestamp - latestPoint.timestamp).absoluteValue > MINUTE_MS }
        ?.let {
            map.addMarker(
                MarkerOptions()
                    .position(LatLng(it.latitude, it.longitude))
                    .icon(selectedIcon)
                    .title("선택 시각 ${timelineFormatTime(renderTime)}"),
            )
        }

    pins.asSequence()
        .filter { it.event.timestamp in detailStart..renderTime }
        .filter { it.event.latitude != null && it.event.longitude != null }
        .forEach { pin ->
            map.addMarker(
                MarkerOptions()
                    .position(LatLng(pin.event.latitude!!, pin.event.longitude!!))
                    .icon(pinIcon(pin))
                    .title(pin.displayTitle)
                    .snippet(pin.displayBody ?: "${pin.pinType.name} · ${timelineFormatTime(pin.event.timestamp)}"),
            )
        }
}

private data class TimelineRouteData(
    val oldSegments: List<List<TrackPoint>>,
    val detailSegments: List<List<TrackPoint>>,
    val gapSegments: List<List<TrackPoint>>,
)

private fun buildTimelineRouteData(
    points: List<TrackPoint>,
    detailStart: Long,
    maxGapMs: Long = ROUTE_GAP_THRESHOLD_MS,
): TimelineRouteData {
    if (points.isEmpty()) return TimelineRouteData(emptyList(), emptyList(), emptyList())

    val sorted = points.sortedBy { it.timestamp }
    val solidSegments = mutableListOf<List<TrackPoint>>()
    val gapSegments = mutableListOf<List<TrackPoint>>()
    var current = mutableListOf(sorted.first())

    for (point in sorted.drop(1)) {
        val previous = current.last()
        if (point.timestamp - previous.timestamp > maxGapMs) {
            if (current.size >= 2) solidSegments += current.toList()
            if (isMeaningfulRouteGap(previous, point)) gapSegments += listOf(previous, point)
            current = mutableListOf(point)
        } else {
            current += point
        }
    }
    if (current.size >= 2) solidSegments += current.toList()

    val oldSegments = mutableListOf<List<TrackPoint>>()
    val detailSegments = mutableListOf<List<TrackPoint>>()
    solidSegments.forEach { segment ->
        val firstDetailIndex = segment.indexOfFirst { it.timestamp >= detailStart }
        when {
            firstDetailIndex < 0 -> oldSegments += segment
            firstDetailIndex == 0 -> detailSegments += segment
            else -> {
                val oldPart = segment.take(firstDetailIndex)
                if (oldPart.size >= 2) oldSegments += oldPart
                val detailedPart = segment.drop(firstDetailIndex - 1)
                if (detailedPart.size >= 2) detailSegments += detailedPart
            }
        }
    }

    return TimelineRouteData(oldSegments, detailSegments, gapSegments)
}

private fun isMeaningfulRouteGap(start: TrackPoint, end: TrackPoint): Boolean {
    val result = FloatArray(1)
    Location.distanceBetween(start.latitude, start.longitude, end.latitude, end.longitude, result)
    val uncertaintyMeters = max(ROUTE_GAP_MIN_DISTANCE_METERS, start.accuracyMeters + end.accuracyMeters)
    return result[0] > uncertaintyMeters
}

private fun renderTimelineRouteLayers(map: MapLibreMap, data: TimelineRouteData) {
    val style = map.style ?: return
    updateTimelineLineLayers(
        style = style,
        sourceId = ROUTE_OLD_SOURCE_ID,
        casingLayerId = ROUTE_OLD_CASING_LAYER_ID,
        lineLayerId = ROUTE_OLD_LAYER_ID,
        geoJson = routeGeoJson(data.oldSegments),
        color = ROUTE_OLD_COLOR,
        width = 5.5f,
        opacity = 0.78f,
    )
    updateTimelineLineLayers(
        style = style,
        sourceId = ROUTE_DETAIL_SOURCE_ID,
        casingLayerId = ROUTE_DETAIL_CASING_LAYER_ID,
        lineLayerId = ROUTE_DETAIL_LAYER_ID,
        geoJson = routeGeoJson(data.detailSegments),
        color = ROUTE_DETAIL_COLOR,
        width = 7.5f,
        opacity = 1f,
    )
    updateTimelineLineLayers(
        style = style,
        sourceId = ROUTE_GAP_SOURCE_ID,
        casingLayerId = ROUTE_GAP_CASING_LAYER_ID,
        lineLayerId = ROUTE_GAP_LAYER_ID,
        geoJson = routeGeoJson(data.gapSegments),
        color = ROUTE_GAP_COLOR,
        width = 6.5f,
        opacity = 1f,
        dashArray = arrayOf(1.2f, 2.8f),
    )
}

private fun updateTimelineLineLayers(
    style: Style,
    sourceId: String,
    casingLayerId: String,
    lineLayerId: String,
    geoJson: String,
    color: Int,
    width: Float,
    opacity: Float,
    dashArray: Array<Float>? = null,
) {
    val source = style.getSourceAs<GeoJsonSource>(sourceId)
    if (source == null) {
        style.addSource(GeoJsonSource(sourceId, geoJson))
    } else {
        source.setGeoJson(geoJson)
    }

    val lineCapValue = if (dashArray == null) Property.LINE_CAP_ROUND else Property.LINE_CAP_BUTT
    if (style.getLayer(casingLayerId) == null) {
        val casingWidth = width + 3f
        val casingLayer = LineLayer(casingLayerId, sourceId).withProperties(
            lineColor(ROUTE_CASING_COLOR),
            lineWidth(casingWidth),
            lineOpacity(max(opacity, 0.82f)),
            lineCap(lineCapValue),
            lineJoin(Property.LINE_JOIN_ROUND),
        )
        dashArray?.let { dash ->
            val scale = width / casingWidth
            casingLayer.setProperties(lineDasharray(Array(dash.size) { index -> dash[index] * scale }))
        }
        style.addLayer(casingLayer)
    }

    if (style.getLayer(lineLayerId) == null) {
        val routeLayer = LineLayer(lineLayerId, sourceId).withProperties(
            lineColor(color),
            lineWidth(width),
            lineOpacity(opacity),
            lineCap(lineCapValue),
            lineJoin(Property.LINE_JOIN_ROUND),
        )
        dashArray?.let { routeLayer.setProperties(lineDasharray(it)) }
        style.addLayer(routeLayer)
    }
}

private fun routeGeoJson(segments: List<List<TrackPoint>>): String {
    val features = segments.asSequence()
        .filter { it.size >= 2 }
        .joinToString(",") { segment ->
            val coordinates = segment.joinToString(",") { point ->
                "[${point.longitude},${point.latitude}]"
            }
            "{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[$coordinates]}}"
        }
    return "{\"type\":\"FeatureCollection\",\"features\":[$features]}"
}

@Composable
private fun TrackingStatusPill(settings: AppSettings, latestPoint: TrackPoint?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val age = latestPoint?.let { System.currentTimeMillis() - it.timestamp }
    val text = when {
        !hasPermission -> "위치 권한 필요"
        !settings.trackingEnabled -> "위치 기록 꺼짐"
        latestPoint == null -> "현재 위치 찾는 중"
        age != null && age < 2 * MINUTE_MS -> "기록 중 · ±${latestPoint.accuracyMeters.roundToInt()}m"
        else -> "마지막 기록 ${((age ?: 0L) / MINUTE_MS).coerceAtLeast(1)}분 전"
    }
    Surface(modifier, CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f), shadowElevation = 3.dp) {
        Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp))
    }
}

private data class DialPinCluster(val xPx: Float, val pins: List<ResolvedPin>)

@Composable
private fun TimelineScrubber(
    selectedTime: Long,
    pins: List<ResolvedPin>,
    tickMinutes: Int,
    radiusMinutes: Int,
    rangeStart: Long,
    rangeEnd: Long,
    onStart: () -> Unit,
    onPreview: (Long) -> Unit,
    onCommit: (Long) -> Unit,
) {
    val selectedState = rememberUpdatedState(selectedTime)
    val startState = rememberUpdatedState(rangeStart)
    val endState = rememberUpdatedState(rangeEnd)
    val previewCallback = rememberUpdatedState(onPreview)
    val commitCallback = rememberUpdatedState(onCommit)
    val startCallback = rememberUpdatedState(onStart)
    val flingScope = rememberCoroutineScope()
    var flingJob by remember { mutableStateOf<Job?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var precision by remember { mutableStateOf(false) }
    var preview by remember { mutableLongStateOf(selectedTime) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    val displayTime = if (dragging) preview else selectedTime
    val zoom by animateFloatAsState(
        targetValue = if (precision) PRECISION_SCALE else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "timelinePrecision",
    )
    val zoomState = rememberUpdatedState(zoom)
    val density = LocalDensity.current
    val effectiveRadiusMs = (radiusMinutes * MINUTE_MS / zoom.coerceAtLeast(1f)).toLong().coerceAtLeast(MINUTE_MS)
    val sortedPins = remember(pins) { pins.sortedBy { it.event.timestamp } }
    val clusters = remember(sortedPins, displayTime, effectiveRadiusMs, widthPx) {
        clusterDialPins(sortedPins, displayTime, effectiveRadiusMs, widthPx, with(density) { 28.dp.toPx() })
    }
    val primary = MaterialTheme.colorScheme.primary
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f)

    LaunchedEffect(selectedTime, dragging) { if (!dragging) preview = selectedTime }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        shape = RoundedCornerShape(21.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(top = 10.dp, bottom = 6.dp)) {
            Text(timelineFormatTime(displayTime), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.align(Alignment.CenterHorizontally))
            Text(
                if (precision || zoom > 1.1f) "정밀 조정 · 4× 확대" else "좌우로 이동 · 빠르게 놓으면 관성 이동 · 1초간 누르면 정밀 확대",
                style = MaterialTheme.typography.labelMedium,
                color = if (precision) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(78.dp)
                    .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
                    .pointerInput(tickMinutes, radiusMinutes, rangeStart, rangeEnd) {
                        awaitEachGesture {
                            val continuingFling = flingJob?.isActive == true
                            flingJob?.cancel()
                            flingJob = null

                            val down = awaitFirstDown(requireUnconsumed = false)
                            dragging = true
                            precision = false
                            if (!continuingFling) preview = selectedState.value
                            startCallback.value()

                            val velocityTracker = VelocityTracker()
                            velocityTracker.addPosition(down.uptimeMillis, down.position)
                            val origin = down.position
                            var latest = origin
                            var released = false
                            var moved = false
                            var lastMapUpdate = SystemClock.uptimeMillis()

                            val held = withTimeoutOrNull(PRECISION_HOLD_MS) {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: run {
                                        released = true
                                        return@withTimeoutOrNull Unit
                                    }
                                    latest = change.position
                                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                                    if (!change.pressed) {
                                        released = true
                                        return@withTimeoutOrNull Unit
                                    }
                                    if ((latest - origin).getDistance() > viewConfiguration.touchSlop) {
                                        moved = true
                                        return@withTimeoutOrNull Unit
                                    }
                                }
                            }
                            if (released) {
                                dragging = false
                                precision = false
                                commitCallback.value(preview)
                                return@awaitEachGesture
                            }
                            if (held == null && !moved) precision = true

                            fun applyDelta(deltaPx: Float, forceMapUpdate: Boolean = false): Boolean {
                                val radiusMs = radiusMinutes * MINUTE_MS.toDouble() / zoomState.value.coerceAtLeast(1f)
                                val millisPerPx = radiusMs * 2.0 / widthPx.coerceAtLeast(1f)
                                val previous = preview
                                preview = (preview - deltaPx * millisPerPx).toLong().coerceIn(startState.value, endState.value)
                                val now = SystemClock.uptimeMillis()
                                if (forceMapUpdate || now - lastMapUpdate >= MAP_PREVIEW_INTERVAL_MS) {
                                    lastMapUpdate = now
                                    previewCallback.value(preview)
                                }
                                return preview != previous
                            }

                            var last = if (moved) origin else latest
                            if (moved) {
                                val delta = latest.x - origin.x
                                if (delta != 0f) applyDelta(delta)
                                last = latest
                            }
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                if (!change.pressed) break
                                val delta = change.position.x - last.x
                                if (delta != 0f) {
                                    change.consume()
                                    applyDelta(delta)
                                }
                                last = change.position
                            }

                            val wasPrecision = precision
                            precision = false
                            val screenWidth = widthPx.coerceAtLeast(1f)
                            val minVelocity = screenWidth * DIAL_FLING_MIN_SCREENS_PER_SECOND
                            val maxVelocity = screenWidth * DIAL_FLING_MAX_SCREENS_PER_SECOND
                            val stopVelocity = screenWidth * DIAL_FLING_STOP_SCREENS_PER_SECOND
                            val releaseVelocity = runCatching { velocityTracker.calculateVelocity().x }
                                .getOrDefault(0f)
                                .coerceIn(-maxVelocity, maxVelocity)

                            if (moved && !wasPrecision && releaseVelocity.absoluteValue >= minVelocity) {
                                flingJob = flingScope.launch {
                                    var velocityPxPerSecond = releaseVelocity.toDouble()
                                    var lastFrameNanos = 0L
                                    while (isActive && velocityPxPerSecond.absoluteValue > stopVelocity) {
                                        withFrameNanos { frameNanos ->
                                            if (lastFrameNanos != 0L) {
                                                val deltaSeconds = ((frameNanos - lastFrameNanos) / 1_000_000_000.0)
                                                    .coerceIn(0.0, 0.05)
                                                val movedInRange = applyDelta((velocityPxPerSecond * deltaSeconds).toFloat())
                                                if (!movedInRange) {
                                                    velocityPxPerSecond = 0.0
                                                } else {
                                                    velocityPxPerSecond *= exp(-DIAL_FLING_FRICTION_PER_SECOND * deltaSeconds)
                                                }
                                            }
                                            lastFrameNanos = frameNanos
                                        }
                                    }
                                    if (isActive) {
                                        applyDelta(0f, forceMapUpdate = true)
                                        dragging = false
                                        commitCallback.value(preview)
                                        flingJob = null
                                    }
                                }
                            } else {
                                applyDelta(0f, forceMapUpdate = true)
                                dragging = false
                                commitCallback.value(preview)
                            }
                        }
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val center = size.width / 2f
                    val radiusMs = radiusMinutes * MINUTE_MS.toDouble() / zoom.coerceAtLeast(1f)
                    val span = radiusMs * 2.0
                    val unit = tickMinutes.coerceAtLeast(1) * MINUTE_MS
                    var tick = floor((displayTime - radiusMs) / unit).toLong() * unit
                    val last = (displayTime + radiusMs).toLong()
                    while (tick <= last + unit) {
                        val x = center + (((tick - displayTime).toDouble() / span) * size.width).toFloat()
                        if (x in -2f..size.width + 2f) {
                            val ordinal = Math.floorDiv(tick, unit)
                            val major = ordinal % 4L == 0L
                            val half = ordinal % 2L == 0L
                            val h = if (major) 29f else if (half) 20f else 11f
                            drawLine(tickColor, Offset(x, size.height - h), Offset(x, size.height), if (major) 2.2f else 1.3f, StrokeCap.Round)
                        }
                        tick += unit
                    }
                    drawLine(primary, Offset(center, 3f), Offset(center, size.height), 4f, StrokeCap.Round)
                }
                clusters.forEach { cluster ->
                    val first = cluster.pins.first()
                    val markerSize = 25.dp
                    val markerPx = with(density) { markerSize.toPx() }
                    Surface(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    (cluster.xPx.coerceIn(markerPx / 2f, (widthPx - markerPx / 2f).coerceAtLeast(markerPx / 2f)) - markerPx / 2f).roundToInt(),
                                    with(density) { 7.dp.toPx() }.roundToInt(),
                                )
                            }
                            .size(markerSize),
                        shape = CircleShape,
                        color = parsePinColor(first.pinType.colorHex),
                        shadowElevation = 2.dp,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (cluster.pins.size > 1) {
                                Text(cluster.pins.size.coerceAtMost(99).toString(), style = MaterialTheme.typography.labelMedium, color = Color.White)
                            } else {
                                Icon(timelinePinVector(first.pinType.icon, first.event.type), first.displayTitle, tint = Color.White, modifier = Modifier.size(15.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun clusterDialPins(
    pins: List<ResolvedPin>,
    centerTime: Long,
    radiusMs: Long,
    widthPx: Float,
    spacingPx: Float,
): List<DialPinCluster> {
    if (widthPx <= 1f || radiusMs <= 0L) return emptyList()
    val span = radiusMs * 2.0
    val visible = pins.asSequence()
        .filter { it.event.timestamp in (centerTime - radiusMs)..(centerTime + radiusMs) }
        .map {
            val x = widthPx / 2f + (((it.event.timestamp - centerTime).toDouble() / span) * widthPx).toFloat()
            x to it
        }
        .toList()
    if (visible.isEmpty()) return emptyList()
    val result = mutableListOf<DialPinCluster>()
    var x = visible.first().first
    var grouped = mutableListOf(visible.first().second)
    visible.drop(1).forEach { (nextX, pin) ->
        if (nextX - x < spacingPx) {
            grouped += pin
            x = (x * (grouped.size - 1) + nextX) / grouped.size
        } else {
            result += DialPinCluster(x, grouped.toList())
            x = nextX
            grouped = mutableListOf(pin)
        }
    }
    result += DialPinCluster(x, grouped.toList())
    return result
}

@Composable
private fun SelectedPinStrip(pins: List<ResolvedPin>, selectedTime: Long) {
    val nearby = remember(pins, selectedTime) {
        pins.filter { (it.event.timestamp - selectedTime).absoluteValue <= 90_000L }
            .sortedBy { (it.event.timestamp - selectedTime).absoluteValue }
    }
    var detail by remember { mutableStateOf<ResolvedPin?>(null) }
    nearby.firstOrNull()?.let { pin ->
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp).clickable { detail = pin },
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = parsePinColor(pin.pinType.colorHex).copy(alpha = 0.14f)) {
                    Icon(
                        timelinePinVector(pin.pinType.icon, pin.event.type),
                        contentDescription = null,
                        tint = parsePinColor(pin.pinType.colorHex),
                        modifier = Modifier.padding(8.dp).size(22.dp),
                    )
                }
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Text("${pin.displayTitle} · ${timelineFormatTime(pin.event.timestamp)}", style = MaterialTheme.typography.titleMedium)
                    pin.displayBody?.let { Text(it, maxLines = 2, style = MaterialTheme.typography.bodyMedium) }
                    if (nearby.size > 1) Text("이 시각 주변 압정 ${nearby.size}개", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
    detail?.let { pin ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(pin.displayTitle) },
            text = {
                Column {
                    Text("${pin.pinType.name} · ${timelineFormatTime(pin.event.timestamp)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    pin.rule?.let { Text("룰 · ${it.name}", modifier = Modifier.padding(top = 4.dp)) }
                    pin.displayBody?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
                }
            },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("닫기") } },
        )
    }
}

@Composable
private fun ManualPinDialog(types: List<PinType>, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var typeId by remember(types) {
        mutableStateOf(types.firstOrNull { it.id == SYSTEM_TYPE_GENERAL }?.id ?: types.firstOrNull()?.id.orEmpty())
    }
    var pickerOpen by remember { mutableStateOf(false) }
    val selected = types.firstOrNull { it.id == typeId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("현재 위치에 압정") },
        text = {
            Column {
                Button(onClick = { pickerOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(timelinePinVector(selected?.icon ?: PinIcon.PIN, EventType.PIN_MANUAL), null, modifier = Modifier.size(19.dp))
                    Text("타입 · ${selected?.name ?: "선택"}", modifier = Modifier.padding(start = 6.dp))
                }
                OutlinedTextField(text, { text = it }, label = { Text("기록") }, minLines = 3, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = {
            Button(onClick = { onSave(text.trim(), typeId) }, enabled = text.isNotBlank() && typeId.isNotBlank()) { Text("기록") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
    if (pickerOpen) {
        AlertDialog(
            onDismissRequest = { pickerOpen = false },
            title = { Text("압정 타입") },
            text = {
                Column {
                    types.forEach { type ->
                        Row(
                            Modifier.fillMaxWidth().clickable { typeId = type.id; pickerOpen = false }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(Modifier.size(12.dp), shape = CircleShape, color = parsePinColor(type.colorHex)) {}
                            Icon(timelinePinVector(type.icon, EventType.PIN_MANUAL), null, tint = parsePinColor(type.colorHex), modifier = Modifier.padding(start = 8.dp).size(20.dp))
                            Text(type.name, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pickerOpen = false }) { Text("취소") } },
        )
    }
}

private fun timelinePinVector(icon: PinIcon, eventType: EventType): ImageVector = when (icon) {
    PinIcon.PIN -> Icons.Rounded.PushPin
    PinIcon.PHONE -> Icons.Rounded.Phone
    PinIcon.MESSAGE -> Icons.Rounded.Message
    PinIcon.NOTIFICATION -> Icons.Rounded.Notifications
    PinIcon.ROUTINE -> Icons.Rounded.Bolt
    PinIcon.STAR -> Icons.Rounded.Star
    PinIcon.PLACE -> Icons.Rounded.Place
    PinIcon.HOME -> Icons.Rounded.Home
    PinIcon.WORK -> Icons.Rounded.Work
}.let { selected ->
    if (icon != PinIcon.PIN) selected else when (eventType) {
        EventType.PHONE_CALL -> Icons.Rounded.Phone
        EventType.SMS, EventType.MMS -> Icons.Rounded.Message
        EventType.NOTIFICATION -> Icons.Rounded.Notifications
        else -> selected
    }
}

private fun makeTimelineDotIcon(context: android.content.Context, sizeDp: Float, fillColor: Int): MapIcon {
    val px = (sizeDp * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val center = px / 2f
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(center, center, px * 0.49f, paint)
    paint.color = fillColor
    canvas.drawCircle(center, center, px * 0.36f, paint)
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(center, center, px * 0.13f, paint)
    return IconFactory.getInstance(context).fromBitmap(bitmap)
}

private fun Long.toTimelineDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()
private fun timelineFormatTime(timestamp: Long): String = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(timelineTimeFormatter)
