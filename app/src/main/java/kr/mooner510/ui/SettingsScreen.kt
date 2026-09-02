package kr.mooner510.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.mooner510.appGraph
import kr.mooner510.data.AppSettings
import kr.mooner510.data.TrackingPreset
import kr.mooner510.map.OfflineDownloadState
import kr.mooner510.map.OfflineRegionInfo
import kr.mooner510.tracking.TrackingService
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val graph = context.appGraph
    val scope = rememberCoroutineScope()
    val settings by graph.preferences.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val downloadState by graph.offlineMapManager.downloadState.collectAsStateWithLifecycle()

    var regions by remember { mutableStateOf(emptyList<OfflineRegionInfo>()) }
    var backupAction by remember { mutableStateOf<BackupAction?>(null) }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    fun refreshRegions() {
        graph.offlineMapManager.list { regions = it }
    }

    LaunchedEffect(Unit) {
        refreshRegions()
    }
    LaunchedEffect(downloadState) {
        if (downloadState is OfflineDownloadState.Complete) refreshRegions()
    }

    val foregroundPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (
            has(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            has(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            TrackingService.start(context)
        }
    }

    val backgroundLocation = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    val sensitivePermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    graph.backupManager.export(uri, password.toCharArray())
                }.onSuccess {
                    message = "암호화 백업을 저장했습니다."
                }.onFailure {
                    message = "백업 실패: ${it.message}"
                }
                password = ""
            }
        }
    }

    val openBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    graph.backupManager.restore(uri, password.toCharArray())
                }.onSuccess {
                    graph.routineShortcutManager.refresh()
                    message = "백업을 복원했습니다."
                }.onFailure {
                    message = "복원 실패: 비밀번호 또는 파일을 확인하세요."
                }
                password = ""
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        item {
            Text(
                "기록",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 20.dp),
            )

            SettingSwitch(
                title = "위치 기록",
                description = "Foreground Service로 위치를 계속 기록합니다.",
                checked = settings.trackingEnabled,
            ) { enabled ->
                scope.launch {
                    graph.preferences.setTrackingEnabled(enabled)
                    if (enabled) TrackingService.start(context) else TrackingService.stop(context)
                }
            }

            Text("위치 기록 프리셋")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TrackingPreset.entries.forEach { preset ->
                    OutlinedButton(
                        onClick = { scope.launch { graph.preferences.setTrackingPreset(preset) } },
                        enabled = preset != settings.trackingPreset,
                    ) {
                        Text(if (preset == settings.trackingPreset) "✓ ${preset.label}" else preset.label)
                    }
                }
            }

            SettingSwitch(
                title = "재부팅 후 자동 시작",
                description = "권한이 유지되면 추적 서비스를 복구합니다.",
                checked = settings.autoStartAfterBoot,
            ) {
                scope.launch { graph.preferences.setAutoStartAfterBoot(it) }
            }

            SettingSwitch(
                title = "주소/장소명 변환",
                description = "켜진 경우에만 역지오코딩하고 결과를 암호화 캐시합니다.",
                checked = settings.reverseGeocodingEnabled,
            ) {
                scope.launch { graph.preferences.setReverseGeocodingEnabled(it) }
            }

            SettingSwitch(
                title = "앱 잠금",
                description = "생체인증 또는 기기 잠금으로 보호합니다.",
                checked = settings.biometricLockEnabled,
            ) {
                scope.launch { graph.preferences.setBiometricLockEnabled(it) }
            }
        }

        item {
            SectionTitle("권한")

            PermissionRow(
                title = "정밀 위치",
                granted = has(context, Manifest.permission.ACCESS_FINE_LOCATION),
            ) {
                foregroundPermissions.launch(
                    buildList {
                        add(Manifest.permission.ACCESS_FINE_LOCATION)
                        add(Manifest.permission.ACCESS_COARSE_LOCATION)
                        if (Build.VERSION.SDK_INT >= 33) {
                            add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }.toTypedArray(),
                )
            }

            PermissionRow(
                title = "백그라운드 위치",
                granted = has(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            ) {
                backgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }

            PermissionRow(
                title = "SMS/MMS · 통화 기록",
                granted = has(context, Manifest.permission.READ_SMS) &&
                    has(context, Manifest.permission.READ_CALL_LOG),
            ) {
                sensitivePermissions.launch(
                    buildList {
                        add(Manifest.permission.READ_SMS)
                        add(Manifest.permission.RECEIVE_SMS)
                        add(Manifest.permission.READ_CALL_LOG)
                        add(Manifest.permission.READ_PHONE_STATE)
                        add(Manifest.permission.READ_CONTACTS)
                        if (Build.VERSION.SDK_INT >= 33) {
                            add(Manifest.permission.READ_MEDIA_AUDIO)
                        } else {
                            add(Manifest.permission.READ_EXTERNAL_STORAGE)
                        }
                    }.toTypedArray(),
                )
            }

            Text("SMS/통화 기록은 Android 제한 권한입니다. 허용되지 않으면 해당 자동 압정만 비활성화됩니다.")

            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Text("알림 접근 허용 설정")
            }

            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Text("배터리 최적화 예외 요청")
            }
        }

        item {
            SectionTitle("오프라인 지도")
            Text("현재 마지막 위치 약 25km 반경을 줌 5~16으로 저장합니다.")

            Button(
                onClick = {
                    scope.launch {
                        val point = graph.repository.latestTrackPoint() ?: return@launch
                        val latitudeDelta = 25.0 / 111.0
                        val longitudeDelta = 25.0 /
                            (111.0 * kotlin.math.cos(Math.toRadians(point.latitude)).coerceAtLeast(0.2))
                        graph.offlineMapManager.download(
                            name = "${point.latitude.format(2)}, ${point.longitude.format(2)} 주변",
                            north = point.latitude + latitudeDelta,
                            east = point.longitude + longitudeDelta,
                            south = point.latitude - latitudeDelta,
                            west = point.longitude - longitudeDelta,
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            ) {
                Text("현재 위치 주변 오프라인 저장")
            }

            Text(downloadStatusText(downloadState))
        }

        items(regions, key = { it.id }) { region ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(region.name)
                    Text(
                        "${region.completedResources}/${region.requiredResources} · " +
                            "${region.completedBytes / 1024 / 1024} MB",
                    )
                }
                TextButton(
                    onClick = { graph.offlineMapManager.delete(region.id) { refreshRegions() } },
                ) {
                    Text("삭제")
                }
            }
            HorizontalDivider()
        }

        item {
            SectionTitle("암호화 백업")
            Text(".nodemap은 PBKDF2-HMAC-SHA256 310,000회 + AES-256-GCM으로 암호화됩니다.")

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { backupAction = BackupAction.EXPORT }) {
                    Text("백업")
                }
                OutlinedButton(onClick = { backupAction = BackupAction.RESTORE }) {
                    Text("복원")
                }
            }

            message?.let { Text(it) }
            Spacer(Modifier.height(32.dp))
        }
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
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val action = backupAction
                        backupAction = null
                        if (action == BackupAction.EXPORT) {
                            createBackup.launch("nodemap-${java.time.LocalDate.now()}.nodemap")
                        } else {
                            openBackup.launch(
                                arrayOf("application/octet-stream", "application/zip", "*/*"),
                            )
                        }
                    },
                    enabled = password.length >= 8,
                ) {
                    Text("계속")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        backupAction = null
                        password = ""
                    },
                ) {
                    Text("취소")
                }
            },
        )
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(description)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
        )
    }
}

@Composable
private fun PermissionRow(
    title: String,
    granted: Boolean,
    request: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f))
        if (granted) Text("허용됨") else Button(onClick = request) { Text("허용") }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
}

private fun downloadStatusText(state: OfflineDownloadState): String = when (state) {
    OfflineDownloadState.Idle -> "대기 중"
    is OfflineDownloadState.Downloading -> {
        val required = state.required.takeIf { it > 0 }?.toString() ?: "?"
        "${state.name}: ${state.completed}/$required · ${state.bytes / 1024 / 1024} MB"
    }
    is OfflineDownloadState.Complete -> {
        "${state.name} 완료 · ${state.bytes / 1024 / 1024} MB"
    }
    is OfflineDownloadState.Failed -> "실패: ${state.message}"
}

private fun has(context: android.content.Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun Double.format(decimals: Int): String = "% .${decimals}f".trim().format(this)

private enum class BackupAction {
    EXPORT,
    RESTORE,
}
