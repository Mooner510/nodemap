package kr.mooner510.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.mooner510.appGraph
import kr.mooner510.data.AppSettings
import kr.mooner510.data.TrackingPreset
import kotlinx.coroutines.launch

@Composable
internal fun TimelineDisplaySettingsSection() {
    val context = LocalContext.current
    val graph = context.appGraph
    val scope = rememberCoroutineScope()
    val settings by graph.preferences.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    var tick by remember(settings.dialTickMinutes) { mutableStateOf(settings.dialTickMinutes.toString()) }
    var radius by remember(settings.dialRadiusMinutes) { mutableStateOf(settings.dialRadiusMinutes.toString()) }
    var detail by remember(settings.detailWindowMinutes) { mutableStateOf(settings.detailWindowMinutes.toString()) }
    var total by remember(settings.totalWindowMinutes) { mutableStateOf(settings.totalWindowMinutes.toString()) }
    var camera by remember(settings.cameraWindowMinutes) { mutableStateOf(settings.cameraWindowMinutes.toString()) }
    var message by remember { mutableStateOf<String?>(null) }

    val tickValue = tick.toIntOrNull()
    val radiusValue = radius.toIntOrNull()
    val detailValue = detail.toIntOrNull()
    val totalValue = total.toIntOrNull()
    val cameraValue = camera.toIntOrNull()
    val valid = listOf(tickValue, radiusValue, detailValue, totalValue, cameraValue).all { it != null && it > 0 } &&
        detailValue!! <= totalValue!! && cameraValue!! <= detailValue

    RoundedSection {
        SectionHeading(
            "타임랩스 표시",
            "모든 값은 분 단위입니다. 정밀 확대 모드는 선택한 다이얼 범위를 일시적으로 4배 확대합니다.",
        )
        Column(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MinuteSettingField("다이얼 눈금 단위", tick, { tick = it }, "기본 눈금 한 칸의 시간")
            MinuteSettingField("다이얼 좌우 반경", radius, { radius = it }, "선택 시각을 중심으로 한쪽에 노출되는 시간")
            MinuteSettingField("자세히 표시", detail, { detail = it }, "선명한 이동 경로와 모든 압정을 표시하는 과거 범위")
            MinuteSettingField("전체 표시", total, { total = it }, "희미한 과거 경로까지 포함한 전체 과거 범위")
            MinuteSettingField("카메라 자동 맞춤", camera, { camera = it }, "다이얼 조정 중 이 과거 범위의 모든 경로가 화면에 들어오도록 맞춤")
        }
        if (!valid) {
            Text(
                "양의 정수를 입력하고 ‘카메라 자동 맞춤 ≤ 자세히 표시 ≤ 전체 표시’를 만족해야 합니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Button(
            onClick = {
                scope.launch {
                    graph.preferences.setTimelineDisplaySettings(
                        tickValue!!,
                        radiusValue!!,
                        detailValue!!,
                        totalValue!!,
                        cameraValue!!,
                    )
                    message = "타임랩스 표시 설정을 저장했습니다."
                }
            },
            enabled = valid,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        ) { Text("표시 설정 저장") }
        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun MinuteSettingField(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    description: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit)) },
        label = { Text(title) },
        supportingText = { Text(description) },
        suffix = { Text("분") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun TrackingPresetDetails(preset: TrackingPreset) {
    val (moving, stationary) = when (preset) {
        TrackingPreset.PRECISE ->
            "이동 중: 선호 3초 · 최소 1초 · 최소 이동 2m" to
                "정지 중: 선호 20초 · 최소 5초 · 최소 이동 5m"
        TrackingPreset.BALANCED ->
            "이동 중: 선호 8초 · 최소 2초 · 최소 이동 5m" to
                "정지 중: 선호 45초 · 최소 10초 · 최소 이동 15m"
        TrackingPreset.BATTERY ->
            "이동 중: 선호 30초 · 최소 10초 · 최소 이동 15m" to
                "정지 중: 선호 180초 · 최소 60초 · 최소 이동 50m"
    }
    Column(Modifier.fillMaxWidth().padding(top = 9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(moving, style = MaterialTheme.typography.bodyMedium)
        Text(stationary, style = MaterialTheme.typography.bodyMedium)
        Text(
            "3분 동안 움직임이 감지되지 않으면 정지 프로필로 전환합니다. 모든 프로필은 Android QUALITY_HIGH_ACCURACY를 요청합니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "‘선호 주기’는 희망 수신 간격이고 실제 갱신을 보장하지 않습니다. 최소 주기는 더 빠른 업데이트를 받을 수 있는 하한이며, 최소 이동 거리보다 가까운 후보 위치는 전달되지 않습니다. 기기·위치 공급자·전원 상태에 따라 실제 기록 간격은 달라질 수 있습니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
