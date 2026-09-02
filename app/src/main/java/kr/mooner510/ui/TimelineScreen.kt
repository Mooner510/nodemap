package kr.mooner510.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Paint
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Message
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.mooner510.appGraph
import kr.mooner510.data.AppSettings
import kr.mooner510.data.AttachmentRecord
import kr.mooner510.data.EventType
import kr.mooner510.data.TimelineEvent
import kr.mooner510.data.TrackPoint
import kotlinx.coroutines.launch
import org.maplibre.android.annotations.Icon as MapIcon
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

private const val HOUR_MS = 60 * 60_000L
private const val SCRUBBER_WINDOW_MS = 6 * HOUR_MS
private const val SCRUBBER_TICK_MS = 5 * 60_000L
private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val dateFormatter = DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREAN)

@Composable
fun TimelineScreen() {
    val context = LocalContext.current
    val graph = context.appGraph
    val scope = rememberCoroutineScope()
    val settings by graph.preferences.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    var selectedTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var points by remember { mutableStateOf(emptyList<TrackPoint>()) }
    var events by remember { mutableStateOf(emptyList<TimelineEvent>()) }
    var latestPoint by remember { mutableStateOf<TrackPoint?>(null) }
    var availableDays by remember { mutableStateOf(emptyList<LocalDate>()) }
    var dayPickerOpen by remember { mutableStateOf(false) }
    var manualPinOpen by remember { mutableStateOf(false) }
    var currentLocationFocusRequest by remember { mutableIntStateOf(0) }

    val selectedDate = remember(selectedTime) { selectedTime.toLocalDate() }
    val selectedDateState = rememberUpdatedState(selectedDate)

    suspend fun reload(day: LocalDate) {
        val zone = ZoneId.systemDefault()
        val start = day.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        points = graph.repository.trackPointsBetween(start, end)
        events = graph.repository.eventsBetween(start, end)
        latestPoint = graph.repository.latestTrackPoint()
        availableDays = graph.repository.dataDays()
    }

    LaunchedEffect(selectedDate) { reload(selectedDate) }
    LaunchedEffect(Unit) {
        graph.repository.changes.collect { reload(selectedDateState.value) }
    }

    val now = System.currentTimeMillis()
    val historyStart = availableDays.minOrNull()
        ?.atStartOfDay(ZoneId.systemDefault())
        ?.toInstant()
        ?.toEpochMilli()
        ?: (now - 12 * HOUR_MS)
    val historyEnd = now

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TimelineDateHeader(
                date = selectedDate,
                hasSelectableDays = availableDays.isNotEmpty(),
                onClick = { dayPickerOpen = true },
            )

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .clip(RoundedCornerShape(24.dp)),
            ) {
                TimelineMap(
                    modifier = Modifier.fillMaxSize(),
                    points = points,
                    events = events,
                    selectedTime = selectedTime,
                    selectedDate = selectedDate,
                    latestPoint = latestPoint,
                    styleUri = settings.mapStyleUri,
                    currentLocationFocusRequest = currentLocationFocusRequest,
                )
                TrackingStatusPill(
                    settings = settings,
                    latestPoint = latestPoint,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp),
                )
                if ((now - selectedTime).absoluteValue > 5 * 60_000L) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp)
                            .clickable { selectedTime = System.currentTimeMillis() },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                        shadowElevation = 3.dp,
                    ) {
                        Row(
                            Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.MyLocation,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                "지금",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                }
                if (latestPoint != null) {
                    SmallFloatingActionButton(
                        onClick = { currentLocationFocusRequest += 1 },
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(10.dp),
                        shape = CircleShape,
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                        contentColor = MaterialTheme.colorScheme.primary,
                    ) {
                        Icon(
                            Icons.Rounded.MyLocation,
                            contentDescription = "현재 위치로 이동",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                SmallFloatingActionButton(
                    onClick = { manualPinOpen = true },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp),
                    shape = RoundedCornerShape(15.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "압정 추가", modifier = Modifier.size(24.dp))
                }
            }

            SelectedEventStrip(events, selectedTime)
            TimelineScrubber(
                selectedTime = selectedTime,
                events = events,
                rangeStart = historyStart,
                rangeEnd = historyEnd,
                onTime = { selectedTime = it },
            )
        }
    }

    if (dayPickerOpen) {
        AvailableDayPickerDialog(
            availableDays = availableDays,
            initialDay = selectedDate,
            onDismiss = { dayPickerOpen = false },
            onSelected = { day ->
                dayPickerOpen = false
                scope.launch {
                    val dayPoints = graph.repository.trackPointsForDay(day)
                    val dayEvents = graph.repository.eventsForDay(day)
                    val target = (dayPoints.map { it.timestamp } + dayEvents.map { it.timestamp }).maxOrNull()
                        ?: day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    selectedTime = target.coerceAtMost(System.currentTimeMillis())
                }
            },
        )
    }

    if (manualPinOpen) {
        ManualPinDialog(
            onDismiss = { manualPinOpen = false },
            onSave = { text ->
                scope.launch {
                    val timestamp = System.currentTimeMillis()
                    val point = graph.repository.latestTrackPoint()
                        ?.takeIf { timestamp - it.timestamp <= 5 * 60_000L }
                    graph.repository.insertEvent(
                        TimelineEvent(
                            timestamp = timestamp,
                            type = EventType.PIN_MANUAL,
                            latitude = point?.latitude,
                            longitude = point?.longitude,
                            title = "압정",
                            body = text,
                            source = "MANUAL",
                        ),
                    )
                    manualPinOpen = false
                }
            },
        )
    }
}

@Composable
private fun TimelineDateHeader(date: LocalDate, hasSelectableDays: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clickable(enabled = hasSelectableDays, onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "타임랩스",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(date.format(dateFormatter), style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (hasSelectableDays) "날짜를 눌러 기록이 있는 날로 바로 이동"
                    else "위치 기록이 쌓이면 날짜를 선택할 수 있습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Surface(
                modifier = Modifier.size(42.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.CalendarMonth,
                        contentDescription = "날짜 선택",
                        tint = if (hasSelectableDays) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(23.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineMap(
    modifier: Modifier,
    points: List<TrackPoint>,
    events: List<TimelineEvent>,
    selectedTime: Long,
    selectedDate: LocalDate,
    latestPoint: TrackPoint?,
    styleUri: String,
    currentLocationFocusRequest: Int,
) {
    val context = LocalContext.current
    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
            onStart()
            onResume()
        }
    }
    val currentIcon = remember(context) { makeDotIcon(context, 38f, android.graphics.Color.rgb(49, 130, 246)) }
    val selectedIcon = remember(context) { makeDotIcon(context, 30f, android.graphics.Color.rgb(25, 31, 40)) }
    val eventIcon = remember(context) { makeDotIcon(context, 27f, android.graphics.Color.rgb(240, 68, 82)) }
    var appliedStyle by remember { mutableStateOf<String?>(null) }
    var focusedDay by remember { mutableStateOf<LocalDate?>(null) }
    var handledCurrentLocationFocusRequest by remember { mutableIntStateOf(0) }

    DisposableEffect(mapView) {
        onDispose {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    fun render(map: MapLibreMap) {
        val focus = drawTimeline(
            map = map,
            points = points,
            events = events,
            selectedTime = selectedTime,
            latestPoint = latestPoint,
            currentIcon = currentIcon,
            selectedIcon = selectedIcon,
            eventIcon = eventIcon,
        )
        if (focus != null && focusedDay != selectedDate) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(focus, 15.5), 350)
            focusedDay = selectedDate
        }
        if (
            latestPoint != null &&
            currentLocationFocusRequest != handledCurrentLocationFocusRequest
        ) {
            map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(latestPoint.latitude, latestPoint.longitude),
                    15.5,
                ),
                350,
            )
            handledCurrentLocationFocusRequest = currentLocationFocusRequest
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { mapView },
        update = { view ->
            view.getMapAsync { map ->
                if (map.style == null || appliedStyle != styleUri) {
                    map.setStyle(styleUri) {
                        appliedStyle = styleUri
                        focusedDay = null
                        render(map)
                    }
                } else {
                    render(map)
                }
            }
        },
    )
}

private fun drawTimeline(
    map: MapLibreMap,
    points: List<TrackPoint>,
    events: List<TimelineEvent>,
    selectedTime: Long,
    latestPoint: TrackPoint?,
    currentIcon: MapIcon,
    selectedIcon: MapIcon,
    eventIcon: MapIcon,
): LatLng? {
    map.clear()
    val twelveHours = points.filter { it.timestamp in (selectedTime - 12 * HOUR_MS)..selectedTime }
    val old = twelveHours.filter { it.timestamp < selectedTime - 2 * HOUR_MS }
    val recent = twelveHours.filter { it.timestamp >= selectedTime - 2 * HOUR_MS }

    splitAtGaps(old).forEach { segment ->
        if (segment.size >= 2) {
            map.addPolyline(
                PolylineOptions()
                    .addAll(segment.map { LatLng(it.latitude, it.longitude) })
                    .color(android.graphics.Color.argb(85, 49, 130, 246))
                    .width(6f),
            )
        }
    }
    splitAtGaps(recent).forEach { segment ->
        if (segment.size >= 2) {
            map.addPolyline(
                PolylineOptions()
                    .addAll(segment.map { LatLng(it.latitude, it.longitude) })
                    .color(android.graphics.Color.rgb(49, 130, 246))
                    .width(9f),
            )
        }
    }

    val selectedPoint = points
        .minByOrNull { (it.timestamp - selectedTime).absoluteValue }
        ?.takeIf { (it.timestamp - selectedTime).absoluteValue <= 10 * 60_000L }

    latestPoint?.let { point ->
        map.addMarker(
            MarkerOptions()
                .position(LatLng(point.latitude, point.longitude))
                .icon(currentIcon)
                .title("현재 위치")
                .snippet("마지막 기록 ${formatTime(point.timestamp)} · ±${point.accuracyMeters.roundToInt()}m"),
        )
    }

    selectedPoint
        ?.takeIf { latestPoint == null || (it.timestamp - latestPoint.timestamp).absoluteValue > 60_000L }
        ?.let { point ->
            map.addMarker(
                MarkerOptions()
                    .position(LatLng(point.latitude, point.longitude))
                    .icon(selectedIcon)
                    .title("선택 시각 ${formatTime(selectedTime)}"),
            )
        }

    events
        .filter {
            (it.timestamp - selectedTime).absoluteValue <= 90_000L &&
                it.latitude != null && it.longitude != null
        }
        .forEach { event ->
            map.addMarker(
                MarkerOptions()
                    .position(LatLng(event.latitude!!, event.longitude!!))
                    .icon(eventIcon)
                    .title(event.title)
                    .snippet(event.body ?: formatTime(event.timestamp)),
            )
        }

    val historicalFocus = points
        .asSequence()
        .filter { it.timestamp <= selectedTime }
        .lastOrNull()
        ?.takeIf { selectedTime - it.timestamp <= 12 * HOUR_MS }

    val focusPoint = selectedPoint
        ?: historicalFocus
        ?: latestPoint?.takeIf { (it.timestamp - selectedTime).absoluteValue <= 10 * 60_000L }

    return focusPoint?.let { LatLng(it.latitude, it.longitude) }
}

private fun splitAtGaps(points: List<TrackPoint>, maxGapMs: Long = 300_000L): List<List<TrackPoint>> {
    if (points.isEmpty()) return emptyList()
    val result = mutableListOf<MutableList<TrackPoint>>()
    var current = mutableListOf(points.first())
    result += current
    for (point in points.drop(1)) {
        if (point.timestamp - current.last().timestamp > maxGapMs) {
            current = mutableListOf()
            result += current
        }
        current += point
    }
    return result
}

@Composable
private fun TrackingStatusPill(
    settings: AppSettings,
    latestPoint: TrackPoint?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    val age = latestPoint?.let { System.currentTimeMillis() - it.timestamp }
    val active = settings.trackingEnabled && hasPermission
    val text = when {
        !hasPermission -> "위치 권한 필요"
        !settings.trackingEnabled -> "위치 기록 꺼짐"
        latestPoint == null -> "현재 위치 찾는 중"
        age != null && age < 2 * 60_000L -> "기록 중 · ±${latestPoint.accuracyMeters.roundToInt()}m"
        else -> "마지막 기록 ${formatAge(age ?: 0L)} 전"
    }
    val dotColor = when {
        active && latestPoint != null && (age ?: Long.MAX_VALUE) < 2 * 60_000L -> Color(0xFF19A15F)
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }

    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        shadowElevation = 3.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = CircleShape, color = dotColor, modifier = Modifier.size(8.dp)) {}
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

@Composable
private fun TimelineScrubber(
    selectedTime: Long,
    events: List<TimelineEvent>,
    rangeStart: Long,
    rangeEnd: Long,
    onTime: (Long) -> Unit,
) {
    val selectedState = rememberUpdatedState(selectedTime)
    val rangeStartState = rememberUpdatedState(rangeStart)
    val rangeEndState = rememberUpdatedState(rangeEnd)
    val onTimeState = rememberUpdatedState(onTime)
    var dragging by remember { mutableStateOf(false) }
    var previewTime by remember { mutableLongStateOf(selectedTime) }
    var dragStartTime by remember { mutableLongStateOf(selectedTime) }
    var totalDragPx by remember { mutableFloatStateOf(0f) }
    var scrubberWidthPx by remember { mutableFloatStateOf(1f) }
    val displayTime = if (dragging) previewTime else selectedTime
    val primary = MaterialTheme.colorScheme.primary
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f)
    val eventColor = MaterialTheme.colorScheme.error
    val pinColor = MaterialTheme.colorScheme.primary
    val pinCenterColor = MaterialTheme.colorScheme.surface

    LaunchedEffect(selectedTime, dragging) {
        if (!dragging) previewTime = selectedTime
    }

    val dragState = rememberDraggableState { delta ->
        totalDragPx += delta
        val millisPerPixel = SCRUBBER_WINDOW_MS.toDouble() / scrubberWidthPx.coerceAtLeast(1f).toDouble()
        previewTime = (dragStartTime - totalDragPx * millisPerPixel)
            .toLong()
            .coerceIn(rangeStartState.value, rangeEndState.value)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        shape = RoundedCornerShape(21.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(top = 10.dp, bottom = 6.dp)) {
            Text(
                formatTime(displayTime),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Text(
                "좌우로 드래그 · 손을 놓으면 지도에 반영됩니다",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 1.dp),
            )
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .onSizeChanged { scrubberWidthPx = it.width.toFloat().coerceAtLeast(1f) }
                    .draggable(
                        state = dragState,
                        orientation = Orientation.Horizontal,
                        startDragImmediately = true,
                        onDragStarted = {
                            dragging = true
                            dragStartTime = selectedState.value
                            previewTime = dragStartTime
                            totalDragPx = 0f
                        },
                        onDragStopped = {
                            val committedTime = previewTime
                            dragging = false
                            onTimeState.value(committedTime)
                        },
                    ),
            ) {
                val center = size.width / 2f
                val halfWindow = SCRUBBER_WINDOW_MS / 2L
                val visibleStart = displayTime - halfWindow
                val visibleEnd = displayTime + halfWindow
                var tickTime = Math.floorDiv(visibleStart, SCRUBBER_TICK_MS) * SCRUBBER_TICK_MS

                while (tickTime <= visibleEnd + SCRUBBER_TICK_MS) {
                    val x = center + (
                        (tickTime - displayTime).toDouble() /
                            SCRUBBER_WINDOW_MS.toDouble() * size.width.toDouble()
                        ).toFloat()
                    val absoluteMinute = Math.floorDiv(tickTime, 60_000L)
                    val major = absoluteMinute % 60L == 0L
                    val half = absoluteMinute % 30L == 0L
                    val tickHeight = if (major) 30f else if (half) 21f else 11f
                    drawLine(
                        color = tickColor,
                        start = Offset(x, size.height - tickHeight),
                        end = Offset(x, size.height),
                        strokeWidth = if (major) 2.2f else 1.3f,
                        cap = StrokeCap.Round,
                    )
                    tickTime += SCRUBBER_TICK_MS
                }

                events.forEach { event ->
                    val delta = event.timestamp - displayTime
                    if (delta in -halfWindow..halfWindow) {
                        val x = center + (
                            delta.toDouble() /
                                SCRUBBER_WINDOW_MS.toDouble() * size.width.toDouble()
                            ).toFloat()
                        if (event.type == EventType.PIN_MANUAL || event.type == EventType.PIN_ROUTINE) {
                            drawLine(
                                color = pinColor,
                                start = Offset(x, 10f),
                                end = Offset(x, 27f),
                                strokeWidth = 2.4f,
                                cap = StrokeCap.Round,
                            )
                            drawCircle(
                                color = pinColor,
                                radius = 6.5f,
                                center = Offset(x, 10f),
                            )
                            drawCircle(
                                color = pinCenterColor,
                                radius = 2.2f,
                                center = Offset(x, 10f),
                            )
                        } else {
                            drawCircle(
                                color = eventColor,
                                radius = 4.5f,
                                center = Offset(x, 11f),
                            )
                        }
                    }
                }

                drawLine(
                    color = primary,
                    start = Offset(center, 3f),
                    end = Offset(center, size.height),
                    strokeWidth = 4f,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun SelectedEventStrip(events: List<TimelineEvent>, selectedTime: Long) {
    val context = LocalContext.current
    val graph = context.appGraph
    val scope = rememberCoroutineScope()
    val nearby = remember(events, selectedTime) {
        events
            .filter { (it.timestamp - selectedTime).absoluteValue <= 90_000L }
            .sortedBy { (it.timestamp - selectedTime).absoluteValue }
    }
    var detail by remember { mutableStateOf<TimelineEvent?>(null) }

    nearby.firstOrNull()?.let { event ->
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .clickable { detail = event },
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Row(
                Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(
                        eventIcon(event.type),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(8.dp)
                            .size(22.dp),
                    )
                }
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Text("${event.title} · ${formatTime(event.timestamp)}", style = MaterialTheme.typography.titleMedium)
                    event.body?.let {
                        Text(
                            it,
                            maxLines = 2,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (nearby.size > 1) {
                        Text(
                            "이 시각 주변 기록 ${nearby.size}개",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }

    detail?.let { event ->
        var attachments by remember(event.id) { mutableStateOf(emptyList<AttachmentRecord>()) }
        LaunchedEffect(event.id) { attachments = graph.repository.attachmentsForEvent(event.id) }
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(event.title) },
            text = {
                Column {
                    Text(formatTime(event.timestamp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    event.body?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
                    attachments.forEach { attachment ->
                        if (!attachment.externalUri.isNullOrBlank()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(attachment.kind, Modifier.weight(1f))
                                TextButton(
                                    onClick = {
                                        runCatching {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW).apply {
                                                    setDataAndType(
                                                        Uri.parse(attachment.externalUri),
                                                        attachment.mimeType ?: "audio/*",
                                                    )
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                },
                                            )
                                        }
                                    },
                                ) { Text("열기") }
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            runCatching {
                                                context.contentResolver
                                                    .openInputStream(Uri.parse(attachment.externalUri))
                                                    ?.use {
                                                        graph.repository.addEncryptedAttachment(
                                                            event.id,
                                                            "${attachment.kind}_IMPORTED",
                                                            attachment.mimeType,
                                                            it,
                                                        )
                                                    }
                                            }
                                            attachments = graph.repository.attachmentsForEvent(event.id)
                                        }
                                    },
                                ) { Text("보관") }
                            }
                        } else {
                            Text("${attachment.kind} · 암호화 보관됨")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("닫기") } },
        )
    }
}

@Composable
private fun ManualPinDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("현재 위치에 압정") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("기록") },
                minLines = 3,
            )
        },
        confirmButton = {
            Button(onClick = { onSave(text.trim()) }, enabled = text.isNotBlank()) { Text("기록") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

private fun eventIcon(type: EventType): ImageVector = when (type) {
    EventType.PHONE_CALL -> Icons.Rounded.Phone
    EventType.SMS, EventType.MMS -> Icons.Rounded.Message
    EventType.NOTIFICATION -> Icons.Rounded.Notifications
    else -> Icons.Rounded.PushPin
}

private fun makeDotIcon(
    context: android.content.Context,
    sizeDp: Float,
    fillColor: Int,
): MapIcon {
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

private fun Long.toLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

private fun formatTime(timestamp: Long): String =
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(timeFormatter)

private fun formatAge(milliseconds: Long): String = when {
    milliseconds < 60_000L -> "${(milliseconds / 1_000L).coerceAtLeast(1)}초"
    milliseconds < HOUR_MS -> "${milliseconds / 60_000L}분"
    else -> "${milliseconds / HOUR_MS}시간"
}
