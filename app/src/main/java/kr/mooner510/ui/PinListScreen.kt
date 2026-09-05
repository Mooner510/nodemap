package kr.mooner510.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Notifications
import Icons.Rounded.Message
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kr.mooner510.appGraph
import kr.mooner510.data.EventType
import kr.mooner510.data.PinIcon
import kr.mooner510.data.PinType
import kr.mooner510.data.ResolvedPin
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val pinListTimeFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")

@Composable
fun PinListScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val graph = context.appGraph
    val scope = rememberCoroutineScope()
    var days by remember { mutableStateOf(emptyList<LocalDate>()) }
    var types by remember { mutableStateOf(emptyList<PinType>()) }
    var startDay by remember { mutableStateOf<LocalDate?>(null) }
    var endDay by remember { mutableStateOf<LocalDate?>(null) }
    var startTime by remember { mutableStateOf("00:00") }
    var endTime by remember { mutableStateOf("23:59") }
    var selectedTypes by remember { mutableStateOf(emptySet<String>()) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<ResolvedPin>()) }
    var startPicker by remember { mutableStateOf(false) }
    var endPicker by remember { mutableStateOf(false) }
    var selectedPin by remember { mutableStateOf<ResolvedPin?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun refreshReferenceData() {
        days = graph.repository.dataDays()
        types = graph.repository.pinTypes()
        val latest = days.maxOrNull() ?: LocalDate.now()
        if (startDay == null) startDay = latest
        if (endDay == null) endDay = latest
    }

    suspend fun search() {
        val start = startDay ?: return
        val end = endDay ?: return
        val startLocalTime = parseMinuteTime(startTime)
        val endLocalTime = parseMinuteTime(endTime)
        if (startLocalTime == null || endLocalTime == null) {
            error = "시간은 HH:mm 형식으로 입력하세요."
            return
        }
        val zone = ZoneId.systemDefault()
        val from = start.atTime(startLocalTime).atZone(zone).toInstant().toEpochMilli()
        val until = end.atTime(endLocalTime).plusMinutes(1).atZone(zone).toInstant().toEpochMilli()
        if (until <= from) {
            error = "종료 시각은 시작 시각보다 뒤여야 합니다."
            return
        }
        error = null
        results = graph.repository.resolvedPinsBetween(from, until)
            .asSequence()
            .filter { selectedTypes.isEmpty() || it.pinType.id in selectedTypes }
            .filter {
                query.isBlank() ||
                    it.displayTitle.contains(query, true) ||
                    it.displayBody.orEmpty().contains(query, true) ||
                    it.rule?.name.orEmpty().contains(query, true)
            }
            .sortedByDescending { it.event.timestamp }
            .toList()
    }

    LaunchedEffect(Unit) {
        refreshReferenceData()
        search()
    }
    LaunchedEffect(Unit) {
        graph.repository.changes.collect {
            refreshReferenceData()
            search()
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenHeader("압정 목록", "시간 범위와 타입을 기준으로 기록된 압정을 검색합니다.") }
        item {
            RoundedSection {
                SectionHeading("검색 범위")
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DateRangeButton("시작", startDay, Modifier.weight(1f)) { startPicker = true }
                    OutlinedTextField(
                        value = startTime,
                        onValueChange = { startTime = it.take(5) },
                        label = { Text("시간") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(0.72f),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DateRangeButton("종료", endDay, Modifier.weight(1f)) { endPicker = true }
                    OutlinedTextField(
                        value = endTime,
                        onValueChange = { endTime = it.take(5) },
                        label = { Text("시간") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(0.72f),
                    )
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("제목 · 내용 · 룰 검색") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    singleLine = true,
                )
                Text(
                    "압정 타입",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    item {
                        FilterChip(
                            selected = selectedTypes.isEmpty(),
                            onClick = { selectedTypes = emptySet() },
                            label = { Text("전체") },
                        )
                    }
                    items(types, key = { it.id }) { type ->
                        FilterChip(
                            selected = type.id in selectedTypes,
                            onClick = {
                                selectedTypes = if (type.id in selectedTypes) selectedTypes - type.id else selectedTypes + type.id
                            },
                            label = { Text(type.name) },
                            leadingIcon = {
                                Surface(
                                    modifier = Modifier.size(9.dp),
                                    shape = CircleShape,
                                    color = parsePinColor(type.colorHex),
                                ) {}
                            },
                        )
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                Button(
                    onClick = { scope.launch { search() } },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                ) { Text("검색") }
            }
        }

        item {
            Text(
                "${results.size}개의 압정",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        if (results.isEmpty()) {
            item {
                RoundedSection {
                    Text("조건에 맞는 압정이 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(results, key = { it.event.id }) { pin ->
                PinResultCard(pin) { selectedPin = pin }
            }
        }
        item { Spacer(Modifier.height(18.dp)) }
    }

    if (startPicker) {
        AvailableDayPickerDialog(
            availableDays = days.filter { day -> endDay?.let { day <= it } ?: true },
            initialDay = startDay ?: days.maxOrNull() ?: LocalDate.now(),
            onDismiss = { startPicker = false },
            onSelected = { startDay = it; startPicker = false },
        )
    }
    if (endPicker) {
        AvailableDayPickerDialog(
            availableDays = days.filter { day -> startDay?.let { day >= it } ?: true },
            initialDay = endDay ?: days.maxOrNull() ?: LocalDate.now(),
            onDismiss = { endPicker = false },
            onSelected = { endDay = it; endPicker = false },
        )
    }

    selectedPin?.let { pin ->
        AlertDialog(
            onDismissRequest = { selectedPin = null },
            title = { Text(pin.displayTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(formatPinTimestamp(pin.event.timestamp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("타입 · ${pin.pinType.name}")
                    pin.rule?.let { Text("룰 · ${it.name}") }
                    pin.displayBody?.let { Text(it) }
                }
            },
            confirmButton = { TextButton(onClick = { selectedPin = null }) { Text("닫기") } },
        )
    }
}

@Composable
private fun DateRangeButton(label: String, day: LocalDate?, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Icon(Icons.Rounded.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
        Text("$label ${day ?: "-"}", modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun PinResultCard(pin: ResolvedPin, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = parsePinColor(pin.pinType.colorHex).copy(alpha = 0.14f),
            ) {
                androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                    Icon(
                        pinListIcon(pin.pinType.icon, pin.event.type),
                        contentDescription = null,
                        tint = parsePinColor(pin.pinType.colorHex),
                        modifier = Modifier.size(21.dp),
                    )
                }
            }
            Column(Modifier.weight(1f).padding(start = 11.dp)) {
                Text(pin.displayTitle, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${pin.pinType.name} · ${formatPinTimestamp(pin.event.timestamp)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                pin.displayBody?.let {
                    Text(it, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private fun pinListIcon(icon: PinIcon, eventType: EventType): ImageVector = when (icon) {
    PinIcon.PHONE -> Icons.Rounded.Phone
    PinIcon.MESSAGE -> Icons.Rounded.Message
    PinIcon.NOTIFICATION -> Icons.Rounded.Notifications
    else -> when (eventType) {
        EventType.PHONE_CALL -> Icons.Rounded.Phone
        EventType.SMS, EventType.MMS -> Icons.Rounded.Message
        EventType.NOTIFICATION -> Icons.Rounded.Notifications
        else -> Icons.Rounded.PushPin
    }
}

internal fun parsePinColor(hex: String): Color = runCatching {
    Color(android.graphics.Color.parseColor(hex))
}.getOrDefault(Color(0xFF3182F6))

private fun parseMinuteTime(value: String): LocalTime? = runCatching {
    val parts = value.split(':')
    if (parts.size != 2) return@runCatching null
    LocalTime.of(parts[0].toInt(), parts[1].toInt())
}.getOrNull()

private fun formatPinTimestamp(timestamp: Long): String =
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(pinListTimeFormatter)
