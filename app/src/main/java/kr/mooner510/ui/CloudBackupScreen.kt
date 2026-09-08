package kr.mooner510.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kr.mooner510.appGraph
import kr.mooner510.backup.RemoteBackupMetadata
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
fun CloudBackupScreen() {
    val context = LocalContext.current
    val graph = context.appGraph
    val scope = rememberCoroutineScope()
    var remote by remember { mutableStateOf<RemoteBackupMetadata?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf<CloudBackupAction?>(null) }
    var expectedCurrentId by remember { mutableStateOf<String?>(null) }
    var confirmReplace by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun refresh() {
        if (busy || !graph.cloudBackupManager.configured) return
        scope.launch {
            busy = true
            runCatching { graph.cloudBackupManager.metadata(context) }
                .onSuccess {
                    remote = it
                    loaded = true
                    message = if (it == null) "현재 보관 중인 온라인 백업이 없습니다." else null
                }
                .onFailure { message = it.userMessage("백업 정보를 불러오지 못했습니다.") }
            busy = false
        }
    }

    fun prepareBackup() {
        if (busy || !graph.cloudBackupManager.configured) return
        scope.launch {
            busy = true
            runCatching { graph.cloudBackupManager.metadata(context) }
                .onSuccess {
                    remote = it
                    loaded = true
                    expectedCurrentId = it?.id
                    if (it == null) action = CloudBackupAction.BACKUP else confirmReplace = true
                }
                .onFailure { message = it.userMessage("현재 백업 상태를 확인하지 못했습니다.") }
            busy = false
        }
    }

    fun prepareRestore() {
        if (busy || !graph.cloudBackupManager.configured) return
        scope.launch {
            busy = true
            runCatching { graph.cloudBackupManager.metadata(context) }
                .onSuccess {
                    remote = it
                    loaded = true
                    if (it == null) message = "복원할 온라인 백업이 없습니다." else action = CloudBackupAction.RESTORE
                }
                .onFailure { message = it.userMessage("현재 백업 상태를 확인하지 못했습니다.") }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        ScreenHeader("온라인 백업", "Google 계정별로 암호화된 백업 1개를 임시 보관합니다.")
        RoundedSection {
            SectionHeading(
                "14일 임시 백업",
                "수동으로 백업할 때만 업로드합니다. 백업일로부터 약 14일 보관되며 15일째 0시(KST)에 자동 삭제됩니다.",
            )
            Spacer(Modifier.height(12.dp))

            if (!graph.cloudBackupManager.configured) {
                Text(
                    "온라인 백업 빌드 설정이 없습니다. 서버 URL과 Google Server Client ID를 빌드 환경에 설정해야 합니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                return@RoundedSection
            }

            when {
                busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 10.dp))
                    Text("처리 중…")
                }
                loaded && remote != null -> BackupInfo(remote!!)
                loaded -> Text("보관 중인 백업 없음", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Text("Google 계정으로 확인하면 현재 백업 상태를 볼 수 있습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            FilledTonalButton(
                onClick = ::refresh,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null)
                Text("Google 계정으로 백업 상태 확인", modifier = Modifier.padding(start = 8.dp))
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = ::prepareBackup,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.CloudUpload, contentDescription = null)
                    Text(if (remote == null) "백업" else "교체", modifier = Modifier.padding(start = 6.dp))
                }
                OutlinedButton(
                    onClick = ::prepareRestore,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.CloudDownload, contentDescription = null)
                    Text("복원", modifier = Modifier.padding(start = 6.dp))
                }
            }

            if (remote != null) {
                TextButton(
                    onClick = { confirmDelete = true },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    Icon(Icons.Rounded.Delete, contentDescription = null)
                    Text("온라인 백업 삭제", modifier = Modifier.padding(start = 6.dp))
                }
            }

            Text(
                "백업 비밀번호는 서버에 저장되지 않습니다. 비밀번호를 잊으면 온라인 백업을 복원할 수 없습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }

    if (action != null) {
        val currentAction = action!!
        AlertDialog(
            onDismissRequest = { action = null; expectedCurrentId = null; password = "" },
            title = { Text(if (currentAction == CloudBackupAction.BACKUP) "온라인 백업" else "온라인 백업 복원") },
            text = {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("백업 비밀번호 (8자 이상)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    enabled = password.length >= 8,
                    onClick = {
                        val chars = password.toCharArray()
                        password = ""
                        action = null
                        scope.launch {
                            busy = true
                            if (currentAction == CloudBackupAction.BACKUP) {
                                runCatching { graph.cloudBackupManager.backup(context, chars, expectedCurrentId) }
                                    .onSuccess {
                                        remote = it
                                        loaded = true
                                        message = "온라인 백업을 완료했습니다."
                                    }
                                    .onFailure { message = it.userMessage("온라인 백업에 실패했습니다.") }
                            } else {
                                runCatching { graph.cloudBackupManager.restore(context, chars) }
                                    .onSuccess {
                                        remote = it
                                        loaded = true
                                        graph.routineShortcutManager.refresh()
                                        message = "온라인 백업을 복원했습니다. 시스템 권한은 다시 확인하세요."
                                    }
                                    .onFailure { message = it.userMessage("복원에 실패했습니다. 비밀번호와 네트워크를 확인하세요.") }
                            }
                            expectedCurrentId = null
                            busy = false
                        }
                    },
                ) { Text(if (currentAction == CloudBackupAction.BACKUP) "백업" else "복원") }
            },
            dismissButton = { TextButton(onClick = { action = null; expectedCurrentId = null; password = "" }) { Text("취소") } },
        )
    }

    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false; expectedCurrentId = null },
            title = { Text("기존 온라인 백업 교체") },
            text = { Text("현재 보관 중인 백업을 새 백업으로 교체합니다. 새 백업 업로드가 성공하기 전까지 기존 백업은 유지됩니다.") },
            confirmButton = {
                Button(onClick = {
                    confirmReplace = false
                    action = CloudBackupAction.BACKUP
                }) { Text("교체") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReplace = false; expectedCurrentId = null }) { Text("취소") }
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("온라인 백업 삭제") },
            text = { Text("서버에 보관된 현재 백업을 즉시 삭제합니다. 이 작업은 되돌릴 수 없습니다.") },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = false
                    scope.launch {
                        busy = true
                        runCatching { graph.cloudBackupManager.delete(context) }
                            .onSuccess {
                                remote = null
                                loaded = true
                                message = "온라인 백업을 삭제했습니다."
                            }
                            .onFailure { message = it.userMessage("온라인 백업 삭제에 실패했습니다.") }
                        busy = false
                    }
                }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun BackupInfo(metadata: RemoteBackupMetadata) {
    val formatter = remember {
        DateTimeFormatter.ofPattern("yyyy. M. d. HH:mm").withZone(ZoneId.of("Asia/Seoul"))
    }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("최근 백업  ${formatter.format(metadata.createdAt)}", style = MaterialTheme.typography.titleMedium)
        Text("크기  ${formatBytes(metadata.sizeBytes)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("자동 삭제  ${formatter.format(metadata.expiresAt)} KST", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "${((bytes * 10.0 / (1024.0 * 1024.0 * 1024.0)).roundToInt() / 10.0)} GB"
    bytes >= 1024L * 1024L -> "${((bytes * 10.0 / (1024.0 * 1024.0)).roundToInt() / 10.0)} MB"
    else -> "${bytes / 1024L} KB"
}

private fun Throwable.userMessage(fallback: String): String = message?.takeIf { it.isNotBlank() } ?: fallback

private enum class CloudBackupAction { BACKUP, RESTORE }
