package kr.mooner510.ui

import android.os.SystemClock
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Message
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kr.mooner510.data.EventType
import kr.mooner510.data.PinIcon
import kr.mooner510.data.ResolvedPin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.floor
import kotlin.math.roundToInt

private const val SCRUBBER_MINUTE_MS = 60_000L
private const val SCRUBBER_MAP_PREVIEW_INTERVAL_MS = 300L
private const val SCRUBBER_PRECISION_HOLD_MS = 1_000L
private const val SCRUBBER_PRECISION_SCALE = 4f
private val scrubberTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private data class ScrubberPinCluster(
    val xPx: Float,
    val targetTime: Long,
    val pins: List<ResolvedPin>,
)

@Composable
internal fun TimelineScrubberV2(
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
    val scope = rememberCoroutineScope()

    var interactionActive by remember { mutableStateOf(false) }
    var precision by remember { mutableStateOf(false) }
    var preview by remember { mutableLongStateOf(selectedTime) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    var lastMapUpdate by remember { mutableLongStateOf(0L) }

    val zoom by animateFloatAsState(
        targetValue = if (precision) SCRUBBER_PRECISION_SCALE else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "timelinePrecision",
    )
    val zoomState = rememberUpdatedState(zoom)
    val widthState = rememberUpdatedState(widthPx)
    val radiusState = rememberUpdatedState(radiusMinutes)

    fun millisPerPx(): Double {
        val radiusMs = radiusState.value * SCRUBBER_MINUTE_MS.toDouble() / zoomState.value.coerceAtLeast(1f)
        return radiusMs * 2.0 / widthState.value.coerceAtLeast(1f)
    }

    val scrollState = rememberScrollableState { deltaPx ->
        val scale = millisPerPx()
        if (scale <= 0.0) return@rememberScrollableState 0f

        val previous = preview
        val unclamped = previous - deltaPx * scale
        val target = unclamped.toLong().coerceIn(startState.value, endState.value)
        preview = target

        val now = SystemClock.uptimeMillis()
        if (now - lastMapUpdate >= SCRUBBER_MAP_PREVIEW_INTERVAL_MS) {
            lastMapUpdate = now
            previewCallback.value(target)
        }

        if (target == previous) 0f else ((previous - target) / scale).toFloat()
    }
    val flingBehavior = ScrollableDefaults.flingBehavior()

    LaunchedEffect(scrollState.isScrollInProgress) {
        if (scrollState.isScrollInProgress) {
            if (!interactionActive) {
                interactionActive = true
                startCallback.value()
            }
        } else if (interactionActive) {
            previewCallback.value(preview)
            commitCallback.value(preview)
            interactionActive = false
        }
    }

    LaunchedEffect(selectedTime) {
        if (!scrollState.isScrollInProgress && !interactionActive) {
            preview = selectedTime
        }
    }

    val displayTime = if (scrollState.isScrollInProgress || interactionActive) preview else selectedTime
    val sortedPins = remember(pins) { pins.sortedBy { it.event.timestamp } }
    val previousPin = remember(sortedPins, displayTime) {
        sortedPins.lastOrNull { it.event.timestamp < displayTime - 1_000L }
    }
    val nextPin = remember(sortedPins, displayTime) {
        sortedPins.firstOrNull { it.event.timestamp > displayTime + 1_000L }
    }

    fun animateToTime(targetTime: Long) {
        val target = targetTime.coerceIn(startState.value, endState.value)
        val scale = millisPerPx()
        if (scale <= 0.0) return
        val deltaPx = ((preview - target) / scale).toFloat()
        if (deltaPx == 0f) {
            preview = target
            previewCallback.value(target)
            commitCallback.value(target)
            return
        }
        scope.launch {
            scrollState.animateScrollBy(deltaPx, tween(durationMillis = 260))
        }
    }

    val density = LocalDensity.current
    val effectiveRadiusMs = (radiusMinutes * SCRUBBER_MINUTE_MS / zoom.coerceAtLeast(1f))
        .toLong()
        .coerceAtLeast(SCRUBBER_MINUTE_MS)
    val clusters = remember(sortedPins, displayTime, effectiveRadiusMs, widthPx) {
        clusterScrubberPins(
            pins = sortedPins,
            centerTime = displayTime,
            radiusMs = effectiveRadiusMs,
            widthPx = widthPx,
            spacingPx = with(density) { 28.dp.toPx() },
        )
    }
    val primary = MaterialTheme.colorScheme.primary
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        shape = RoundedCornerShape(21.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(top = 8.dp, bottom = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { previousPin?.let { animateToTime(it.event.timestamp) } },
                    enabled = previousPin != null,
                ) {
                    Icon(Icons.Rounded.ChevronLeft, contentDescription = "이전 압정")
                }
                Text(
                    text = scrubberFormatTime(displayTime),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                IconButton(
                    onClick = { nextPin?.let { animateToTime(it.event.timestamp) } },
                    enabled = nextPin != null,
                ) {
                    Icon(Icons.Rounded.ChevronRight, contentDescription = "다음 압정")
                }
            }
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
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            precision = false
                            val origin = down.position
                            var released = false
                            var moved = false
                            val held = withTimeoutOrNull(SCRUBBER_PRECISION_HOLD_MS) {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: run {
                                        released = true
                                        return@withTimeoutOrNull Unit
                                    }
                                    if ((change.position - origin).getDistance() > viewConfiguration.touchSlop) {
                                        moved = true
                                        return@withTimeoutOrNull Unit
                                    }
                                    if (!change.pressed) {
                                        released = true
                                        return@withTimeoutOrNull Unit
                                    }
                                }
                            }
                            if (held == null && !released && !moved) precision = true
                            while (!released) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                            }
                            precision = false
                        }
                    }
                    .scrollable(
                        state = scrollState,
                        orientation = Orientation.Horizontal,
                        flingBehavior = flingBehavior,
                        reverseDirection = false,
                    ),
            ) {
                Canvas(Modifier.fillMaxWidth().height(78.dp)) {
                    val center = size.width / 2f
                    val radiusMs = radiusMinutes * SCRUBBER_MINUTE_MS.toDouble() / zoom.coerceAtLeast(1f)
                    val span = radiusMs * 2.0
                    val unit = tickMinutes.coerceAtLeast(1) * SCRUBBER_MINUTE_MS
                    var tick = floor((displayTime - radiusMs) / unit).toLong() * unit
                    val last = (displayTime + radiusMs).toLong()
                    while (tick <= last + unit) {
                        val x = center + (((tick - displayTime).toDouble() / span) * size.width).toFloat()
                        if (x in -2f..size.width + 2f) {
                            val ordinal = Math.floorDiv(tick, unit)
                            val major = ordinal % 4L == 0L
                            val half = ordinal % 2L == 0L
                            val h = if (major) 29f else if (half) 20f else 11f
                            drawLine(
                                color = tickColor,
                                start = Offset(x, size.height - h),
                                end = Offset(x, size.height),
                                strokeWidth = if (major) 2.2f else 1.3f,
                                cap = StrokeCap.Round,
                            )
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
                                    (cluster.xPx.coerceIn(
                                        markerPx / 2f,
                                        (widthPx - markerPx / 2f).coerceAtLeast(markerPx / 2f),
                                    ) - markerPx / 2f).roundToInt(),
                                    with(density) { 7.dp.toPx() }.roundToInt(),
                                )
                            }
                            .size(markerSize)
                            .clickable { animateToTime(cluster.targetTime) },
                        shape = CircleShape,
                        color = scrubberPinColor(first.pinType.colorHex),
                        shadowElevation = 2.dp,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (cluster.pins.size > 1) {
                                Text(
                                    cluster.pins.size.coerceAtMost(99).toString(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White,
                                )
                            } else {
                                Icon(
                                    scrubberPinVector(first.pinType.icon, first.event.type),
                                    first.displayTitle,
                                    tint = Color.White,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun clusterScrubberPins(
    pins: List<ResolvedPin>,
    centerTime: Long,
    radiusMs: Long,
    widthPx: Float,
    spacingPx: Float,
): List<ScrubberPinCluster> {
    if (widthPx <= 1f || radiusMs <= 0L || pins.isEmpty()) return emptyList()
    val minTime = centerTime - radiusMs
    val maxTime = centerTime + radiusMs
    val startIndex = lowerBoundPin(pins, minTime)
    if (startIndex >= pins.size) return emptyList()

    val span = radiusMs * 2.0
    val visible = mutableListOf<Pair<Float, ResolvedPin>>()
    var index = startIndex
    while (index < pins.size) {
        val pin = pins[index]
        if (pin.event.timestamp > maxTime) break
        val x = widthPx / 2f + (((pin.event.timestamp - centerTime).toDouble() / span) * widthPx).toFloat()
        visible += x to pin
        index++
    }
    if (visible.isEmpty()) return emptyList()

    val result = mutableListOf<ScrubberPinCluster>()
    var x = visible.first().first
    var grouped = mutableListOf(visible.first().second)
    visible.drop(1).forEach { (nextX, pin) ->
        if (nextX - x < spacingPx) {
            grouped += pin
            x = (x * (grouped.size - 1) + nextX) / grouped.size
        } else {
            result += makeCluster(x, grouped)
            x = nextX
            grouped = mutableListOf(pin)
        }
    }
    result += makeCluster(x, grouped)
    return result
}

private fun makeCluster(x: Float, pins: List<ResolvedPin>): ScrubberPinCluster {
    val target = pins.map { it.event.timestamp.toDouble() }.average().toLong()
    return ScrubberPinCluster(x, target, pins.toList())
}

private fun lowerBoundPin(pins: List<ResolvedPin>, target: Long): Int {
    var low = 0
    var high = pins.size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (pins[mid].event.timestamp < target) low = mid + 1 else high = mid
    }
    return low
}

private fun scrubberFormatTime(timestamp: Long): String =
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(scrubberTimeFormatter)

private fun scrubberPinColor(value: String): Color =
    Color(runCatching { android.graphics.Color.parseColor(value) }.getOrDefault(android.graphics.Color.rgb(49, 130, 246)))

private fun scrubberPinVector(icon: PinIcon, eventType: EventType): ImageVector = when (icon) {
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
