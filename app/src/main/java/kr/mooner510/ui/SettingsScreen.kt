package kr.mooner510.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.mooner510.appGraph
import kr.mooner510.data.AppSettings
import kr.mooner510.data.TrackingPreset
import kr.mooner510.map.OfflineDownloadState
import kr.mooner510.map.OfflineRegionInfo
import kr.mooner510.tracking.TrackingService
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val graph = context.appGraph
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val settings by graph.preferences.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val downloadState by graph.offlineMapManager.downloadState.collectAsStateWithLifecycle()
    var permissionRefresh by remember { mutableIntStateOf(0) }
    var regions by remember { mutableStateOf(emptyList<OfflineRegionInfo>()) }
    var trackDays by remember { mutableStateOf(emptyList<LocalDate>()) }
    var offlineStart by remember { mutableStateOf<LocalDate?>(null) }
    var offlineEnd by remember { mutableStateOf<LocalDate?>(null) }
    var pickStart by remember { mutableStateOf(false) }
    var pickEnd by remember { mutableStateOf(false) }
    var backupAction by remember { mutableStateOf<BackupAction?>(null) }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionRefresh += 1
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun refreshRegions() = graph.offlineMapManager.list { regions = it }

    suspend fun refreshTrackDays() {
        val days = graph.repository.trackDays()
        trackDays = days
        val latest = days.maxOrNull()
        if (offlineStart == null || offlineStart !in days) offlineStart = latest
        if (offlineEnd == null || offlineEnd !in days) offlineEnd = latest
    }

    LaunchedEffect(Unit) {
        refreshRegions()
        refreshTrackDays()
    }
    LaunchedEffect(Unit) {
        graph.repository.changes.collect { refreshTrackDays() }
    }
    LaunchedEffect(downloadState) {
        if (downloadState is OfflineDownloadState.Complete) refreshRegions()
    }

    val foregroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionRefresh += 1
        if (has(context, Manifest.permission.ACCESS_FINE_LOCATION, permissionRefresh) ||
            has(context, Manifest.permission.ACCESS_COARSE_LOCATION, permissionRefresh)
        ) {
            TrackingService.start(context)
        }
    }
    val sensitiveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionRefresh += 1 }
    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching { graph.backupManager.export(uri, password.toCharArray()) }
                    .onSuccess { message = "암호화 백업을 저장했습니다." }
                    .onFailure { message = "백업 실패: ${it.message}" }
                password = ""
            }
        }
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching { graph.backupManager.restore(uri, password.toCharArray()) }
                    .onSuccess {
                        graph.routineShortcutManager.refresh()
                        message = "백업을 복원했습니다."
                    }
                    .onFailure { message = "복원 실패: 비밀번호 또는 파일을 확인하세요." }
                password = ""
            }
        }
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenHeader("설정", "기록 방식, 권한, 오프라인 지도와 백업을 관리합니다.") }

        item {
            RoundedSection {
                SectionHeading("위치 기록", "기본은 균형 모드이며 기록 자체는 지도 네트워크와 독립적으로 동작합니다.")
                Spacer(Modifier.height(10.dp))
                SettingToggleRow(
                    icon = Icons.Rounded.MyLocation,
                    title = "위치 기록",
                    description = "Foreground Service로 위치를 계속 기록",
                    checked = settings.trackingEnabled,
                ) { enabled ->
                    scope.launch {
                        graph.preferences.setTrackingEnabled(enabled)
                        if (enabled) TrackingService.start(context) else TrackingService.stop(context)
                    }
                }
                SettingToggleRow(
                    icon = Icons.Rounded.LocationOn,
                    title = "재부팅 후 자동 시작",
                    description = "권한이 유지되면 기록 서비스를 복구",
                    checked = settings.autoStartAfterBoot,
                ) { scope.launch { graph.preferences.setAutoStartAfterBoot(it) } }
                SettingToggleRow(
                    icon = Icons.Rounded.Settings,
                    title = "주소/장소명 변환",
                    description = "켜진 경우에만 역지오코딩하고 암호화 캐시",
                    checked = settings.reverseGeocodingEnabled,
                ) { scope.launch { graph.preferences.setReverseGeocodingEnabled(it) } }
                SettingToggleRow(
                    icon = Icons.Rounded.Lock,
                    title = "앱 잠금",
                    description = "생체인증 또는 기기 잠금으로 보호",
                    checked = settings.biometricLockEnabled,
                ) { scope.launch { graph.preferences.setBiometricLockEnabled(it) } }
                Text(
                    "위치 정확도",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TrackingPreset.entries.forEach { preset ->
                        if (preset == settings.trackingPreset) {
                            Button(onClick = {}, modifier = Modifier.weight(1f)) { Text(preset.label) }
                        } else {
                            FilledTonalButton(
                                onClick = { scope.launch { graph.preferences.setTrackingPreset(preset) } },
                                modifier = Modifier.weight(1f),
                            ) { Text(preset.label) }
                        }
                    }
                }
            }
        }

        item {
            RoundedSection {
                SectionHeading("권한", "허용되지 않은 기능만 개별적으로 비활성화됩니다.")
                Spacer(Modifier.height(8.dp))
                PermissionActionRow(
                    Icons.Rounded.LocationOn,
                    "정밀 위치",
                    has(context, Manifest.permission.ACCESS_FINE_LOCATION, permissionRefresh),
                ) {
                    foregroundLauncher.launch(
                        buildList {
                            add(Manifest.permission.ACCESS_FINE_LOCATION)
                            add(Manifest.permission.ACCESS_COARSE_LOCATION)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }.toTypedArray(),
                    )
                }
                PermissionActionRow(
                    Icons.Rounded.MyLocation,
                    "백그라운드 위치",
                    has(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION, permissionRefresh),
                ) {
                    context.startActivity(
                        Intent(
                            AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                }
                PermissionActionRow(
                    Icons.Rounded.Phone,
                    "SMS/MMS · 통화 기록",
                    has(context, Manifest.permission.READ_SMS, permissionRefresh) &&
                        has(context, Manifest.permission.READ_CALL_LOG, permissionRefresh),
                ) {
                    sensitiveLauncher.launch(
                        buildList {
                            add(Manifest.permission.READ_SMS)
                            add(Manifest.permission.RECEIVE_SMS)
                            add(Manifest.permission.READ_CALL_LOG)
                            add(Manifest.permission.READ_PHONE_STATE)
                            add(Manifest.permission.READ_CONTACTS)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                add(Manifest.permission.READ_MEDIA_AUDIO)
                            } else {
                                add(Manifest.permission.READ_EXTERNAL_STORAGE)
                            }
                        }.toTypedArray(),
                    )
                }
                PermissionActionRow(
                    Icons.Rounded.Notifications,
                    "알림 접근",
                    notificationListenerEnabled(context, permissionRefresh),
                ) {
                    context.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                FilledTonalButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                ) {
                    Text("배터리 최적화 예외 요청")
                }
            }
        }

        item {
            RoundedSection {
                SectionHeading(
                    "오프라인 지도",
                    "기간을 고르면 그 기간에 실제로 이동한 각 날짜의 범위 주변 3km를 저장합니다.",
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DayRangeButton("시작", offlineStart, Modifier.weight(1f)) { pickStart = true }
                    DayRangeButton("종료", offlineEnd, Modifier.weight(1f)) { pickEnd = true }
                }
                Button(
                    onClick = {
                        val start = offlineStart ?: return@Button
                        val end = offlineEnd ?: return@Button
                        scope.launch {
                            val zone = ZoneId.systemDefault()
                            val from = start.atStartOfDay(zone).toInstant().toEpochMilli()
                            val until = end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                            val points = graph.repository.trackPointsBetween(from, until)
                            graph.offlineMapManager.downloadTrackPeriod(points, start, end)
                        }
                    },
                    enabled = offlineStart != null && offlineEnd != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(22.dp))
                    Text("선택 기간 지도 저장", modifier = Modifier.padding(start = 8.dp))
                }
                Text(
                    downloadStateText(downloadState),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
                if (regions.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    regions.forEach { region ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(region.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${region.completedResources}/${region.requiredResources} · ${region.completedBytes / 1024 / 1024} MB",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { graph.offlineMapManager.delete(region.id) { refreshRegions() } }) {
                                Text("삭제")
                            }
                        }
                    }
                }
            }
        }

        item {
            RoundedSection {
                SectionHeading("암호화 백업", ".nodemap 파일 전체를 비밀번호 기반 AES-256-GCM으로 암호화합니다.")
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = { backupAction = BackupAction.EXPORT }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Backup, contentDescription = null, modifier = Modifier.size(22.dp))
                        Text("백업", modifier = Modifier.padding(start = 7.dp))
                    }
                    OutlinedButton(onClick = { backupAction = BackupAction.RESTORE }, modifier = Modifier.weight(1f)) {
                        Text("복원")
                    }
                }
                message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }

        item { Spacer(Modifier.height(18.dp)) }
    }

    if (pickStart) {
        AvailableDayPickerDialog(
            availableDays = trackDays.filter { day -> offlineEnd?.let { day <= it } ?: true },
            initialDay = offlineStart ?: trackDays.maxOrNull() ?: LocalDate.now(),
            onDismiss = { pickStart = false },
            onSelected = { day ->
                offlineStart = day
                if (offlineEnd == null || offlineEnd!! < day) offlineEnd = day
                pickStart = false
            },
        )
    }
    if (pickEnd) {
        AvailableDayPickerDialog(
            availableDays = trackDays.filter { day -> offlineStart?.let { day >= it } ?: true },
            initialDay = offlineEnd ?: trackDays.maxOrNull() ?: LocalDate.now(),
            onDismiss = { pickEnd = false },
            onSelected = { day ->
                offlineEnd = day
                if (offlineStart == null || offlineStart!! > day) offlineStart = day
                pickEnd = false
            },
        )
    }

    if (backupAction != null) {
        AlertDialog(
            onDismissRequest = {
                backupAction = null
                password = ""
            },
            title = {
                Text(if (backupAction == BackupAction.EXPORT) "백업 비밀번호" else "복원 비밀번호")
            },
            text = {
                Column {
                    if (backupAction == BackupAction.RESTORE) {
                        Text("복원하면 현재 기록을 백업 내용으로 교체합니다.")
                    }
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("8자 이상") },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val action = backupAction
                        backupAction = null
                        if (action == BackupAction.EXPORT) {
                            createBackup.launch("nodemap-${LocalDate.now()}.nodemap")
                        } else {
                            openBackup.launch(arrayOf("application/octet-stream", "application/zip", "*/*"))
                        }
                    },
                    enabled = password.length >= 8,
                ) { Text("계속") }
            },
            dismissButton = {
                TextButton(onClick = {
                    backupAction = null
                    password = ""
                }) { Text("취소") }
            },
        )
    }
}

@Composable
private fun SettingToggleRow(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(10.dp)
                    .size(24.dp),
            )
        }
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PermissionActionRow(
    icon: ImageVector,
    title: String,
    granted: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(25.dp),
        )
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .padding(start = 12.dp)
                .weight(1f),
        )
        if (granted) {
            Text("허용됨", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        } else {
            FilledTonalButton(onClick = onClick) { Text("설정") }
        }
    }
}

@Composable
private fun DayRangeButton(
    label: String,
    day: LocalDate?,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, enabled = day != null, modifier = modifier) {
        Icon(Icons.Rounded.CalendarMonth, contentDescription = null, modifier = Modifier.size(20.dp))
        Column(Modifier.padding(start = 7.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(day?.toString() ?: "기록 없음", style = MaterialTheme.typography.labelLarge)
        }
    }
}

private fun downloadStateText(state: OfflineDownloadState): String = when (state) {
    OfflineDownloadState.Idle -> "저장할 기간을 선택하세요."
    is OfflineDownloadState.Downloading ->
        "${state.name}: ${state.completed}/${state.required.takeIf { it > 0 } ?: "?"} · ${state.bytes / 1024 / 1024} MB"
    is OfflineDownloadState.Complete -> "${state.name} 완료 · ${state.bytes / 1024 / 1024} MB"
    is OfflineDownloadState.Failed -> "실패: ${state.message}"
}

private fun has(context: android.content.Context, permission: String, refresh: Int): Boolean {
    refresh.hashCode()
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

private fun notificationListenerEnabled(context: android.content.Context, refresh: Int): Boolean {
    refresh.hashCode()
    return AndroidSettings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        .orEmpty()
        .contains(context.packageName)
}

private enum class BackupAction { EXPORT, RESTORE }
