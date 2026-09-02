package kr.mooner510.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Message
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kr.mooner510.appGraph
import kr.mooner510.tracking.TrackingService
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen() {
    val context = LocalContext.current
    val graph = context.appGraph
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var page by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }

    val foregroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refresh += 1 }
    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refresh += 1 }
    val sensitiveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refresh += 1 }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh += 1
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val foregroundGranted = hasLocationPermission(context, refresh)
    val backgroundGranted = hasPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION, refresh)
    val sensitiveGranted = hasPermission(context, Manifest.permission.READ_SMS, refresh) &&
        hasPermission(context, Manifest.permission.READ_CALL_LOG, refresh)
    val notificationAccessGranted = notificationListenerEnabled(context, refresh)

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 20.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("NodeMap", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text(
                "${page + 1} / 4",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(30.dp))
            when (page) {
                0 -> WelcomePage()
                1 -> LocationPermissionPage(
                    foregroundGranted = foregroundGranted,
                    backgroundGranted = backgroundGranted,
                    onForeground = {
                        foregroundLauncher.launch(
                            buildList {
                                add(Manifest.permission.ACCESS_FINE_LOCATION)
                                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    add(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }.toTypedArray(),
                        )
                    },
                    onBackground = {
                        if (foregroundGranted) {
                            backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        }
                    },
                    onSettings = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    },
                )
                2 -> AutomaticPinsPage(
                    sensitiveGranted = sensitiveGranted,
                    notificationAccessGranted = notificationAccessGranted,
                    onSensitive = {
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
                    },
                    onNotificationAccess = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                )
                else -> FinishPage(
                    foregroundGranted = foregroundGranted,
                    backgroundGranted = backgroundGranted,
                    sensitiveGranted = sensitiveGranted,
                    notificationAccessGranted = notificationAccessGranted,
                    onBatterySettings = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        }
                    },
                )
            }
            Spacer(Modifier.height(28.dp))
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(4) { index ->
                Box(
                    Modifier
                        .size(if (index == page) 22.dp else 8.dp, 8.dp)
                        .background(
                            if (index == page) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                            CircleShape,
                        ),
                )
            }
            Spacer(Modifier.weight(1f))
            if (page > 0) {
                TextButton(onClick = { page -= 1 }) { Text("이전") }
            }
            Button(
                onClick = {
                    if (page < 3) {
                        page += 1
                    } else {
                        scope.launch {
                            graph.preferences.setOnboardingCompleted(true)
                            if (foregroundGranted && graph.preferences.current().trackingEnabled) {
                                TrackingService.start(context)
                            }
                        }
                    }
                },
            ) {
                Text(if (page == 3) "NodeMap 시작" else "다음")
                Icon(
                    Icons.Rounded.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun WelcomePage() {
    OnboardingHero(
        icon = Icons.Rounded.LocationOn,
        title = "하루의 이동을\n자동으로 남겨요",
        description = "NodeMap은 위치와 압정을 기기 안에 저장합니다. 서버 계정 없이, 필요한 기록만 내 휴대폰에 쌓입니다.",
    )
    Spacer(Modifier.height(24.dp))
    RoundedSection {
        OnboardingFeature(Icons.Rounded.LocationOn, "이동 경로", "시간에 따라 움직인 위치를 타임라인으로 확인")
        OnboardingFeature(Icons.Rounded.Message, "자동 압정", "SMS/MMS, 통화, 선택한 앱 알림을 규칙에 따라 기록")
        OnboardingFeature(Icons.Rounded.Lock, "로컬 암호화", "민감한 내용과 첨부파일은 기기 키로 암호화")
    }
}

@Composable
private fun LocationPermissionPage(
    foregroundGranted: Boolean,
    backgroundGranted: Boolean,
    onForeground: () -> Unit,
    onBackground: () -> Unit,
    onSettings: () -> Unit,
) {
    OnboardingHero(
        icon = Icons.Rounded.LocationOn,
        title = "위치 기록을\n먼저 준비할게요",
        description = "화면을 닫아도 이동 경로를 계속 남기려면 정밀 위치와 백그라운드 위치 권한이 필요합니다.",
    )
    Spacer(Modifier.height(24.dp))
    RoundedSection {
        PermissionStep(
            title = "정밀 위치",
            description = "현재 위치와 이동 경로 기록",
            granted = foregroundGranted,
            actionText = "허용",
            onClick = onForeground,
        )
        PermissionStep(
            title = "항상 위치 허용",
            description = "앱을 사용하지 않을 때도 기록",
            granted = backgroundGranted,
            enabled = foregroundGranted,
            actionText = "설정",
            onClick = onBackground,
        )
        TextButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
            Text("앱 권한 설정 직접 열기")
        }
    }
}

@Composable
private fun AutomaticPinsPage(
    sensitiveGranted: Boolean,
    notificationAccessGranted: Boolean,
    onSensitive: () -> Unit,
    onNotificationAccess: () -> Unit,
) {
    OnboardingHero(
        icon = Icons.Rounded.Notifications,
        title = "자동 압정도\n원하면 켤 수 있어요",
        description = "전화·문자와 선택한 앱의 알림을 위치와 함께 기록할 수 있습니다. 이 권한들은 없어도 기본 위치 기록은 동작합니다.",
    )
    Spacer(Modifier.height(24.dp))
    RoundedSection {
        PermissionStep(
            title = "SMS/MMS · 통화",
            description = "송수신 시각, 상대방, 통화 기록과 녹음 연결",
            granted = sensitiveGranted,
            actionText = "허용",
            onClick = onSensitive,
        )
        PermissionStep(
            title = "알림 접근",
            description = "사용자가 만든 규칙에 맞는 알림 압정 생성",
            granted = notificationAccessGranted,
            actionText = "설정",
            onClick = onNotificationAccess,
        )
        Text(
            "일부 통화/SMS 권한은 Android 또는 설치 방식에 따라 허용되지 않을 수 있으며, 그 경우 해당 자동 압정만 비활성화됩니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun FinishPage(
    foregroundGranted: Boolean,
    backgroundGranted: Boolean,
    sensitiveGranted: Boolean,
    notificationAccessGranted: Boolean,
    onBatterySettings: () -> Unit,
) {
    OnboardingHero(
        icon = Icons.Rounded.CheckCircle,
        title = "준비가 끝났어요",
        description = "위치 권한이 있으면 시작과 동시에 기록을 시작합니다. 빠진 권한은 나중에 설정 화면에서 언제든 추가할 수 있습니다.",
    )
    Spacer(Modifier.height(24.dp))
    RoundedSection {
        StatusLine("위치 기록", foregroundGranted)
        StatusLine("백그라운드 기록", backgroundGranted)
        StatusLine("전화·문자 자동 압정", sensitiveGranted)
        StatusLine("알림 자동 압정", notificationAccessGranted)
        FilledTonalButton(
            onClick = onBatterySettings,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
        ) {
            Text("배터리 최적화 예외 설정")
        }
    }
}

@Composable
private fun OnboardingHero(icon: ImageVector, title: String, description: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(88.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
        }
    }
    Text(
        title,
        style = MaterialTheme.typography.headlineLarge,
        modifier = Modifier.padding(top = 24.dp),
    )
    Text(
        description,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun OnboardingFeature(icon: ImageVector, title: String, description: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
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
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PermissionStep(
    title: String,
    description: String,
    granted: Boolean,
    enabled: Boolean = true,
    actionText: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (granted) {
            Surface(shape = CircleShape, color = Color(0xFFE7F8EE)) {
                Text(
                    "완료",
                    color = Color(0xFF1B8F4D),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        } else {
            FilledTonalButton(onClick = onClick, enabled = enabled) { Text(actionText) }
        }
    }
}

@Composable
private fun StatusLine(label: String, enabled: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(
            if (enabled) "사용 가능" else "나중에 설정",
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

private fun hasLocationPermission(context: android.content.Context, refresh: Int): Boolean {
    refresh.hashCode()
    return hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION, refresh) ||
        hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION, refresh)
}

private fun hasPermission(context: android.content.Context, permission: String, refresh: Int): Boolean {
    refresh.hashCode()
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

private fun notificationListenerEnabled(context: android.content.Context, refresh: Int): Boolean {
    refresh.hashCode()
    return Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        .orEmpty()
        .contains(context.packageName)
}
