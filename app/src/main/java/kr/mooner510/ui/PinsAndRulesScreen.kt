package kr.mooner510.ui

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import kr.mooner510.appGraph
import kr.mooner510.data.NotificationRule
import kr.mooner510.data.PinTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class InstalledApp(val label: String, val packageName: String)

@Composable
fun PinsAndRulesScreen() {
    val context = LocalContext.current
    val graph = context.appGraph
    val scope = rememberCoroutineScope()
    var rules by remember { mutableStateOf(emptyList<NotificationRule>()) }
    var templates by remember { mutableStateOf(emptyList<PinTemplate>()) }
    var editingRule by remember { mutableStateOf<NotificationRule?>(null) }
    var ruleDialogOpen by remember { mutableStateOf(false) }
    var templateDialogOpen by remember { mutableStateOf(false) }

    suspend fun reload() {
        rules = graph.repository.notificationRules()
        templates = graph.repository.pinTemplates()
    }
    LaunchedEffect(Unit) { reload() }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenHeader("압정", "직접 남기는 기록과 자동으로 만들어지는 기록 규칙을 관리합니다.") }
        item {
            RoundedSection {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                        Icon(
                            Icons.Rounded.PushPin,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(11.dp).size(26.dp),
                        )
                    }
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        SectionHeading("루틴 압정 템플릿", "Galaxy 모드 및 루틴의 앱 동작에서 바로 실행할 수 있습니다.")
                    }
                }
                FilledTonalButton(
                    onClick = { templateDialogOpen = true },
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(22.dp))
                    Text("템플릿 추가", modifier = Modifier.padding(start = 7.dp))
                }
                if (templates.isEmpty()) {
                    EmptyHint("아직 만든 템플릿이 없습니다.")
                } else {
                    templates.forEachIndexed { index, template ->
                        if (index == 0) HorizontalDivider(Modifier.padding(top = 14.dp))
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(template.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    template.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        graph.repository.deletePinTemplate(template.id)
                                        graph.routineShortcutManager.refresh()
                                        reload()
                                    }
                                },
                            ) {
                                Icon(Icons.Rounded.Delete, contentDescription = "삭제", modifier = Modifier.size(21.dp))
                            }
                        }
                    }
                }
            }
        }

        item {
            RoundedSection {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                        Icon(
                            Icons.Rounded.Notifications,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(11.dp).size(26.dp),
                        )
                    }
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        SectionHeading("알림 기록 규칙", "제외 regex가 먼저 적용되고, 포함 regex가 비어 있으면 선택 앱의 모든 알림을 기록합니다.")
                    }
                }
                FilledTonalButton(
                    onClick = {
                        editingRule = null
                        ruleDialogOpen = true
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(22.dp))
                    Text("규칙 추가", modifier = Modifier.padding(start = 7.dp))
                }
                if (rules.isEmpty()) {
                    EmptyHint("아직 알림 기록 규칙이 없습니다.")
                } else {
                    rules.forEachIndexed { index, rule ->
                        if (index == 0) HorizontalDivider(Modifier.padding(top = 14.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    editingRule = rule
                                    ruleDialogOpen = true
                                }
                                .padding(vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(rule.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "앱 ${rule.packageNames.size}개 · 포함 ${rule.includeRegex.ifBlank { "전체" }} · 제외 ${rule.excludeRegex.ifBlank { "없음" }}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = rule.enabled,
                                onCheckedChange = { enabled ->
                                    scope.launch {
                                        graph.repository.upsertNotificationRule(rule.copy(enabled = enabled))
                                        reload()
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(18.dp)) }
    }

    if (templateDialogOpen) {
        TemplateDialog(
            onDismiss = { templateDialogOpen = false },
            onSave = { name, text ->
                scope.launch {
                    graph.repository.upsertPinTemplate(PinTemplate(name = name, text = text))
                    graph.routineShortcutManager.refresh()
                    templateDialogOpen = false
                    reload()
                }
            },
        )
    }
    if (ruleDialogOpen) {
        RuleDialog(
            initial = editingRule,
            onDismiss = { ruleDialogOpen = false },
            onDelete = editingRule?.let { rule ->
                {
                    scope.launch {
                        graph.repository.deleteNotificationRule(rule.id)
                        ruleDialogOpen = false
                        reload()
                    }
                }
            },
            onSave = { rule ->
                scope.launch {
                    graph.repository.upsertNotificationRule(rule)
                    ruleDialogOpen = false
                    reload()
                }
            },
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun TemplateDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("루틴 압정 템플릿") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("이름") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(text, { text = it }, label = { Text("기록 내용") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(onClick = { onSave(name.trim(), text.trim()) }, enabled = name.isNotBlank()) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun RuleDialog(
    initial: NotificationRule?,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (NotificationRule) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var include by remember(initial) { mutableStateOf(initial?.includeRegex ?: "") }
    var exclude by remember(initial) { mutableStateOf(initial?.excludeRegex ?: "") }
    var selected by remember(initial) { mutableStateOf(initial?.packageNames ?: emptySet()) }
    var pickerOpen by remember { mutableStateOf(false) }
    val includeValid = include.isBlank() || runCatching { Regex(include) }.isSuccess
    val excludeValid = exclude.isBlank() || runCatching { Regex(exclude) }.isSuccess

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "알림 규칙 추가" else "알림 규칙 수정") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("규칙 이름") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { pickerOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Apps, contentDescription = null, modifier = Modifier.size(21.dp))
                    Text("대상 앱 ${selected.size}개 선택", modifier = Modifier.padding(start = 7.dp))
                }
                OutlinedTextField(
                    value = include,
                    onValueChange = { include = it },
                    label = { Text("포함 regex (빈 값 = 전체)") },
                    isError = !includeValid,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = exclude,
                    onValueChange = { exclude = it },
                    label = { Text("제외 regex") },
                    isError = !excludeValid,
                    modifier = Modifier.fillMaxWidth(),
                )
                onDelete?.let { delete ->
                    TextButton(onClick = delete) {
                        Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text("규칙 삭제", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        NotificationRule(
                            id = initial?.id ?: java.util.UUID.randomUUID().toString(),
                            name = name.trim().ifBlank { "알림 규칙" },
                            packageNames = selected,
                            includeRegex = include,
                            excludeRegex = exclude,
                            enabled = initial?.enabled ?: true,
                        ),
                    )
                },
                enabled = selected.isNotEmpty() && includeValid && excludeValid,
            ) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )

    if (pickerOpen) {
        AppPicker(
            initial = selected,
            onDismiss = { pickerOpen = false },
            onDone = {
                selected = it
                pickerOpen = false
            },
        )
    }
}

@Composable
private fun AppPicker(
    initial: Set<String>,
    onDismiss: () -> Unit,
    onDone: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf(emptyList<InstalledApp>()) }
    var chosen by remember { mutableStateOf(initial) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            context.packageManager.getInstalledApplications(0)
                .filter {
                    it.flags and ApplicationInfo.FLAG_SYSTEM == 0 ||
                        it.packageName == context.packageName
                }
                .map {
                    InstalledApp(
                        context.packageManager.getApplicationLabel(it).toString(),
                        it.packageName,
                    )
                }
                .sortedBy { it.label.lowercase() }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("대상 앱") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("검색") },
                    leadingIcon = {
                        Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(22.dp))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 420.dp).padding(top = 8.dp)) {
                    items(
                        apps.filter {
                            query.isBlank() ||
                                it.label.contains(query, true) ||
                                it.packageName.contains(query, true)
                        },
                        key = { it.packageName },
                    ) { app ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    chosen = if (app.packageName in chosen) {
                                        chosen - app.packageName
                                    } else {
                                        chosen + app.packageName
                                    }
                                }
                                .padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = app.packageName in chosen, onCheckedChange = null)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(app.label, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    app.packageName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onDone(chosen) }) { Text("완료") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
