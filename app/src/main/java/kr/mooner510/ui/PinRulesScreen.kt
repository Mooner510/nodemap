package kr.mooner510.ui

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import kr.mooner510.data.AppSelectionMode
import kr.mooner510.data.PinIcon
import kr.mooner510.data.PinRule
import kr.mooner510.data.PinRuleSource
import kr.mooner510.data.PinType
import kr.mooner510.data.RuleCondition
import kr.mooner510.data.RuleConditionField
import kr.mooner510.data.RuleConditionJoin
import kr.mooner510.data.RuleConditionOperator
import kr.mooner510.data.SYSTEM_TYPE_GENERAL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class RuleInstalledApp(val label: String, val packageName: String)

@Composable
fun PinRulesScreen() {
    val context = LocalContext.current
    val graph = context.appGraph
    val scope = rememberCoroutineScope()
    var rules by remember { mutableStateOf(emptyList<PinRule>()) }
    var types by remember { mutableStateOf(emptyList<PinType>()) }
    var usage by remember { mutableStateOf(emptyMap<String, Long>()) }
    var typeUsage by remember { mutableStateOf(emptyMap<String, Long>()) }
    var editingRule by remember { mutableStateOf<PinRule?>(null) }
    var ruleDialogOpen by remember { mutableStateOf(false) }
    var editingType by remember { mutableStateOf<PinType?>(null) }
    var typeDialogOpen by remember { mutableStateOf(false) }

    suspend fun reload() {
        types = graph.repository.pinTypes()
        rules = graph.repository.pinRules().sortedWith(compareBy<PinRule> { it.source.ordinal }.thenBy { it.priority })
        usage = rules.associate { it.id to graph.repository.pinRuleUsageCount(it.id) }
        typeUsage = types.associate { it.id to graph.repository.pinTypeUsageCount(it.id) }
    }

    LaunchedEffect(Unit) { reload() }
    LaunchedEffect(Unit) { graph.repository.changes.collect { reload() } }

    suspend fun refreshRoutineShortcutsIfNeeded(rule: PinRule?) {
        if (rule?.source == PinRuleSource.ROUTINE) graph.routineShortcutManager.refresh()
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenHeader("압정 룰", "압정 타입과 자동 생성 규칙을 분리해서 관리합니다.") }
        item {
            RoundedSection {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                        Icon(
                            Icons.Rounded.PushPin,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(9.dp).size(23.dp),
                        )
                    }
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        SectionHeading("압정 타입", "룰은 타입을 참조합니다. 타입을 바꾸면 과거 압정에도 즉시 반영됩니다.")
                    }
                }
                FilledTonalButton(
                    onClick = { editingType = null; typeDialogOpen = true },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text("새 타입", modifier = Modifier.padding(start = 6.dp))
                }
                types.forEachIndexed { index, type ->
                    if (index == 0) HorizontalDivider(Modifier.padding(top = 12.dp))
                    Row(
                        Modifier.fillMaxWidth().clickable { editingType = type; typeDialogOpen = true }.padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(modifier = Modifier.size(14.dp), shape = CircleShape, color = parsePinColor(type.colorHex)) {}
                        Column(Modifier.weight(1f).padding(start = 9.dp)) {
                            Text(type.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${type.icon.name.lowercase()} · ${type.colorHex}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(Icons.Rounded.Edit, contentDescription = "수정", modifier = Modifier.size(19.dp))
                    }
                }
            }
        }

        item {
            RoundedSection {
                SectionHeading(
                    "자동 압정 생성 룰",
                    "같은 원본 이벤트가 여러 룰과 일치하면 우선순위가 높은 첫 룰 하나만 사용합니다.",
                )
                FilledTonalButton(
                    onClick = { editingRule = null; ruleDialogOpen = true },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text("룰 추가", modifier = Modifier.padding(start = 6.dp))
                }
                if (rules.isEmpty()) {
                    Text("룰을 준비하는 중입니다.", modifier = Modifier.padding(top = 12.dp))
                } else {
                    rules.forEachIndexed { index, rule ->
                        if (index == 0) HorizontalDivider(Modifier.padding(top = 12.dp))
                        RuleRow(
                            rule = rule,
                            type = types.firstOrNull { it.id == rule.pinTypeId },
                            usage = usage[rule.id] ?: 0L,
                            onEdit = { editingRule = rule; ruleDialogOpen = true },
                            onEnabled = { enabled ->
                                scope.launch {
                                    graph.repository.upsertPinRule(rule.copy(enabled = enabled, hidden = if (enabled) false else rule.hidden))
                                    refreshRoutineShortcutsIfNeeded(rule)
                                }
                            },
                            onHidden = { hidden ->
                                scope.launch { graph.repository.upsertPinRule(rule.copy(hidden = hidden)) }
                            },
                            onMove = { delta ->
                                scope.launch {
                                    val sameSource = rules.filter { it.source == rule.source }.sortedBy { it.priority }
                                    val currentIndex = sameSource.indexOfFirst { it.id == rule.id }
                                    val targetIndex = (currentIndex + delta).coerceIn(0, sameSource.lastIndex)
                                    if (currentIndex >= 0 && targetIndex != currentIndex) {
                                        val other = sameSource[targetIndex]
                                        graph.repository.upsertPinRule(rule.copy(priority = other.priority))
                                        graph.repository.upsertPinRule(other.copy(priority = rule.priority))
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(18.dp)) }
    }

    if (typeDialogOpen) {
        PinTypeDialog(
            initial = editingType,
            canDelete = editingType?.let { type ->
                !type.id.startsWith("system:type:") && (typeUsage[type.id] ?: 0L) == 0L
            } ?: false,
            onDismiss = { typeDialogOpen = false },
            onDelete = editingType?.let { type ->
                {
                    scope.launch {
                        if (graph.repository.deletePinType(type.id)) typeDialogOpen = false
                    }
                }
            },
            onSave = { type ->
                scope.launch {
                    graph.repository.upsertPinType(type)
                    typeDialogOpen = false
                }
            },
        )
    }

    if (ruleDialogOpen) {
        val currentUsage = editingRule?.let { usage[it.id] ?: 0L } ?: 0L
        PinRuleDialog(
            initial = editingRule,
            usageCount = currentUsage,
            types = types,
            onDismiss = { ruleDialogOpen = false },
            onAddType = { editingType = null; typeDialogOpen = true },
            onDelete = editingRule?.takeIf { !it.system && currentUsage == 0L }?.let { rule ->
                {
                    scope.launch {
                        if (graph.repository.deletePinRule(rule.id)) {
                            refreshRoutineShortcutsIfNeeded(rule)
                            ruleDialogOpen = false
                        }
                    }
                }
            },
            onSave = { rule ->
                scope.launch {
                    val saved = if (editingRule == null) {
                        val nextPriority = (rules
                            .filter { it.source == rule.source && !it.system && it.priority < 10_000 }
                            .maxOfOrNull { it.priority } ?: 90) + 10
                        rule.copy(priority = nextPriority)
                    } else {
                        rule
                    }
                    graph.repository.upsertPinRule(saved)
                    refreshRoutineShortcutsIfNeeded(saved)
                    ruleDialogOpen = false
                }
            },
        )
    }
}

@Composable
private fun RuleRow(
    rule: PinRule,
    type: PinType?,
    usage: Long,
    onEdit: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onHidden: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(12.dp),
                shape = CircleShape,
                color = parsePinColor(type?.colorHex ?: "#3182F6"),
            ) {}
            Column(Modifier.weight(1f).padding(start = 9.dp).clickable(onClick = onEdit)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(rule.name, style = MaterialTheme.typography.titleMedium)
                    if (rule.system) {
                        Text(
                            "시스템",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 7.dp),
                        )
                    }
                }
                Text(
                    "${sourceLabel(rule.source)} · ${type?.name ?: "타입 없음"} · 생성 $usage개" +
                        if (rule.hidden) " · 숨김" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = rule.enabled, onCheckedChange = onEnabled)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onMove(-1) }) {
                Icon(Icons.Rounded.ArrowUpward, contentDescription = "우선순위 올리기", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = { onMove(1) }) {
                Icon(Icons.Rounded.ArrowDownward, contentDescription = "우선순위 내리기", modifier = Modifier.size(18.dp))
            }
            if (!rule.enabled) {
                TextButton(onClick = { onHidden(!rule.hidden) }) {
                    Icon(
                        if (rule.hidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(if (rule.hidden) "다시 표시" else "숨기기", modifier = Modifier.padding(start = 5.dp))
                }
            }
            TextButton(onClick = onEdit) { Text("설정") }
        }
    }
}

@Composable
private fun PinTypeDialog(
    initial: PinType?,
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (PinType) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var color by remember(initial) { mutableStateOf(initial?.colorHex ?: "#3182F6") }
    var icon by remember(initial) { mutableStateOf(initial?.icon ?: PinIcon.PIN) }
    val colorValid = remember(color) { Regex("^#[0-9A-Fa-f]{6}$").matches(color.trim()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "압정 타입 추가" else "압정 타입 수정") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("타입 이름") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    color,
                    { color = it },
                    label = { Text("색상 (#RRGGBB)") },
                    isError = !colorValid,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("아이콘", style = MaterialTheme.typography.titleMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(PinIcon.entries, key = { it.name }) { candidate ->
                        FilterChip(
                            selected = icon == candidate,
                            onClick = { icon = candidate },
                            label = { Text(iconLabel(candidate)) },
                        )
                    }
                }
                if (canDelete && onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("타입 삭제", modifier = Modifier.padding(start = 5.dp))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        PinType(
                            id = initial?.id ?: java.util.UUID.randomUUID().toString(),
                            name = name.trim(),
                            colorHex = color.trim().uppercase(),
                            icon = icon,
                        ),
                    )
                },
                enabled = name.isNotBlank() && colorValid,
            ) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun PinRuleDialog(
    initial: PinRule?,
    usageCount: Long,
    types: List<PinType>,
    onDismiss: () -> Unit,
    onAddType: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (PinRule) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var source by remember(initial) { mutableStateOf(initial?.source ?: PinRuleSource.NOTIFICATION) }
    var typeId by remember(initial, types) { mutableStateOf(initial?.pinTypeId ?: types.firstOrNull()?.id ?: SYSTEM_TYPE_GENERAL) }
    var titleOverride by remember(initial) { mutableStateOf(initial?.titleOverride ?: "") }
    var bodyTemplate by remember(initial) { mutableStateOf(initial?.bodyTemplate ?: "") }
    var appMode by remember(initial) { mutableStateOf(initial?.appSelectionMode ?: AppSelectionMode.INCLUDE_SELECTED) }
    var packages by remember(initial) { mutableStateOf(initial?.packageNames ?: emptySet()) }
    var conditionJoin by remember(initial) { mutableStateOf(initial?.conditionJoin ?: RuleConditionJoin.ALL) }
    var conditions by remember(initial) { mutableStateOf(initial?.conditions ?: emptyList()) }
    var appPickerOpen by remember { mutableStateOf(false) }
    val sourceLocked = initial?.system == true || usageCount > 0
    val validConditions = conditions.all { it.value.isNotBlank() && conditionValueValid(it) }
    val appsValid = source != PinRuleSource.NOTIFICATION ||
        appMode == AppSelectionMode.EXCLUDE_SELECTED || packages.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "압정 룰 추가" else "압정 룰 수정") },
        text = {
            LazyColumn(Modifier.heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                item {
                    OutlinedTextField(
                        name,
                        { name = it },
                        label = { Text("룰 이름") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Text("감지 원본", style = MaterialTheme.typography.titleMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(PinRuleSource.entries, key = { it.name }) { candidate ->
                            FilterChip(
                                selected = source == candidate,
                                onClick = { if (!sourceLocked) source = candidate },
                                enabled = !sourceLocked || source == candidate,
                                label = { Text(sourceLabel(candidate)) },
                            )
                        }
                    }
                    if (sourceLocked) {
                        Text(
                            if (initial?.system == true) "시스템 룰의 감지 원본은 변경할 수 없습니다."
                            else "이미 압정을 생성한 룰의 감지 원본은 변경할 수 없습니다.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item {
                    Text("압정 타입", style = MaterialTheme.typography.titleMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(types, key = { it.id }) { type ->
                            FilterChip(
                                selected = typeId == type.id,
                                onClick = { typeId = type.id },
                                leadingIcon = {
                                    Surface(modifier = Modifier.size(9.dp), shape = CircleShape, color = parsePinColor(type.colorHex)) {}
                                },
                                label = { Text(type.name) },
                            )
                        }
                        item {
                            FilterChip(selected = false, onClick = onAddType, label = { Text("+ 새 타입") })
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        titleOverride,
                        { titleOverride = it },
                        label = { Text("표시 제목 (빈 값 = 원본 제목)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (source == PinRuleSource.ROUTINE) {
                    item {
                        OutlinedTextField(
                            bodyTemplate,
                            { bodyTemplate = it },
                            label = { Text("루틴 압정 내용") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                        )
                    }
                }
                if (source == PinRuleSource.NOTIFICATION) {
                    item {
                        Text("앱 범위", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            FilterChip(
                                selected = appMode == AppSelectionMode.INCLUDE_SELECTED,
                                onClick = { appMode = AppSelectionMode.INCLUDE_SELECTED },
                                label = { Text("선택한 앱만") },
                            )
                            FilterChip(
                                selected = appMode == AppSelectionMode.EXCLUDE_SELECTED,
                                onClick = { appMode = AppSelectionMode.EXCLUDE_SELECTED },
                                label = { Text("선택한 앱 제외") },
                            )
                        }
                        OutlinedButton(onClick = { appPickerOpen = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.Apps, contentDescription = null, modifier = Modifier.size(19.dp))
                            Text("앱 ${packages.size}개 선택", modifier = Modifier.padding(start = 6.dp))
                        }
                        if (!appsValid) {
                            Text("‘선택한 앱만’은 한 개 이상의 앱을 선택해야 합니다.", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                item {
                    Text("조건 결합", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        FilterChip(
                            selected = conditionJoin == RuleConditionJoin.ALL,
                            onClick = { conditionJoin = RuleConditionJoin.ALL },
                            label = { Text("모든 조건") },
                        )
                        FilterChip(
                            selected = conditionJoin == RuleConditionJoin.ANY,
                            onClick = { conditionJoin = RuleConditionJoin.ANY },
                            label = { Text("하나 이상") },
                        )
                    }
                }
                items(conditions, key = { it.id }) { condition ->
                    RuleConditionEditor(
                        condition = condition,
                        onChange = { next -> conditions = conditions.map { if (it.id == condition.id) next else it } },
                        onDelete = { conditions = conditions.filterNot { it.id == condition.id } },
                    )
                }
                item {
                    OutlinedButton(
                        onClick = { conditions = conditions + RuleCondition() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("조건 추가", modifier = Modifier.padding(start = 5.dp))
                    }
                }
                if (onDelete != null) {
                    item {
                        TextButton(onClick = onDelete) {
                            Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("룰 삭제", modifier = Modifier.padding(start = 5.dp))
                        }
                    }
                } else if (initial != null) {
                    item {
                        Text(
                            if (initial.system) "시스템 룰은 삭제할 수 없습니다. 비활성화할 수 있습니다."
                            else if (usageCount > 0) "압정을 $usageCount개 생성한 룰은 삭제할 수 없습니다. 비활성화 후 숨길 수 있습니다."
                            else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        PinRule(
                            id = initial?.id ?: java.util.UUID.randomUUID().toString(),
                            name = name.trim().ifBlank { "압정 룰" },
                            source = source,
                            system = initial?.system ?: false,
                            enabled = initial?.enabled ?: true,
                            hidden = initial?.hidden ?: false,
                            priority = initial?.priority ?: 100,
                            pinTypeId = typeId,
                            titleOverride = titleOverride.trim(),
                            bodyTemplate = bodyTemplate.trim(),
                            appSelectionMode = appMode,
                            packageNames = packages,
                            conditionJoin = conditionJoin,
                            conditions = conditions,
                        ),
                    )
                },
                enabled = name.isNotBlank() && typeId.isNotBlank() && appsValid && validConditions,
            ) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )

    if (appPickerOpen) {
        RuleAppPicker(
            initial = packages,
            onDismiss = { appPickerOpen = false },
            onDone = { packages = it; appPickerOpen = false },
        )
    }
}

@Composable
private fun RuleConditionEditor(
    condition: RuleCondition,
    onChange: (RuleCondition) -> Unit,
    onDelete: () -> Unit,
) {
    var fieldDialog by remember { mutableStateOf(false) }
    var operatorDialog by remember { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { fieldDialog = true }, modifier = Modifier.weight(1f)) {
                    Text(conditionFieldLabel(condition.field))
                }
                OutlinedButton(onClick = { operatorDialog = true }, modifier = Modifier.weight(1f)) {
                    Text(conditionOperatorLabel(condition.operator))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Rounded.Delete, contentDescription = "조건 삭제", modifier = Modifier.size(19.dp))
                }
            }
            OutlinedTextField(
                value = condition.value,
                onValueChange = { onChange(condition.copy(value = it)) },
                label = { Text("조건 값") },
                isError = !conditionValueValid(condition),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
    }
    if (fieldDialog) {
        SimpleChoiceDialog(
            title = "조건 항목",
            values = RuleConditionField.entries,
            label = ::conditionFieldLabel,
            onDismiss = { fieldDialog = false },
            onSelected = { onChange(condition.copy(field = it)); fieldDialog = false },
        )
    }
    if (operatorDialog) {
        SimpleChoiceDialog(
            title = "비교 방식",
            values = RuleConditionOperator.entries,
            label = ::conditionOperatorLabel,
            onDismiss = { operatorDialog = false },
            onSelected = { onChange(condition.copy(operator = it)); operatorDialog = false },
        )
    }
}

@Composable
private fun <T> SimpleChoiceDialog(
    title: String,
    values: List<T>,
    label: (T) -> String,
    onDismiss: () -> Unit,
    onSelected: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(values) { value ->
                    Text(
                        label(value),
                        modifier = Modifier.fillMaxWidth().clickable { onSelected(value) }.padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun RuleAppPicker(
    initial: Set<String>,
    onDismiss: () -> Unit,
    onDone: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf(emptyList<RuleInstalledApp>()) }
    var chosen by remember { mutableStateOf(initial) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            context.packageManager.getInstalledApplications(0)
                .map {
                    RuleInstalledApp(
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    FilledTonalButton(
                        onClick = { chosen = apps.mapTo(mutableSetOf()) { it.packageName } },
                        modifier = Modifier.weight(1f),
                    ) { Text("모두 선택") }
                    OutlinedButton(onClick = { chosen = emptySet() }, modifier = Modifier.weight(1f)) { Text("모두 해제") }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("검색") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                LazyColumn(Modifier.heightIn(max = 390.dp).padding(top = 7.dp)) {
                    items(
                        apps.filter {
                            query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true)
                        },
                        key = { it.packageName },
                    ) { app ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                chosen = if (app.packageName in chosen) chosen - app.packageName else chosen + app.packageName
                            }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = app.packageName in chosen, onCheckedChange = null)
                            Column(Modifier.padding(start = 7.dp)) {
                                Text(app.label, style = MaterialTheme.typography.titleMedium)
                                Text(app.packageName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

private fun sourceLabel(source: PinRuleSource): String = when (source) {
    PinRuleSource.PHONE_CALL -> "통화"
    PinRuleSource.SMS -> "SMS"
    PinRuleSource.MMS -> "MMS"
    PinRuleSource.NOTIFICATION -> "알림"
    PinRuleSource.ROUTINE -> "Galaxy 루틴"
}

private fun iconLabel(icon: PinIcon): String = when (icon) {
    PinIcon.PIN -> "압정"
    PinIcon.PHONE -> "통화"
    PinIcon.MESSAGE -> "메시지"
    PinIcon.NOTIFICATION -> "알림"
    PinIcon.ROUTINE -> "루틴"
    PinIcon.STAR -> "별"
    PinIcon.PLACE -> "장소"
    PinIcon.HOME -> "집"
    PinIcon.WORK -> "업무"
}

private fun conditionFieldLabel(field: RuleConditionField): String = when (field) {
    RuleConditionField.TEXT -> "전체 텍스트"
    RuleConditionField.TITLE -> "제목"
    RuleConditionField.BODY -> "내용"
    RuleConditionField.PACKAGE_NAME -> "앱 패키지"
    RuleConditionField.APP_LABEL -> "앱 이름"
    RuleConditionField.CONTACT_NAME -> "연락처 이름"
    RuleConditionField.PHONE_NUMBER -> "전화번호"
    RuleConditionField.ADDRESS -> "발신/수신 주소"
    RuleConditionField.DIRECTION -> "수신/발신 방향"
    RuleConditionField.DURATION_SECONDS -> "통화 시간(초)"
    RuleConditionField.SOURCE -> "원본 소스"
}

private fun conditionOperatorLabel(operator: RuleConditionOperator): String = when (operator) {
    RuleConditionOperator.CONTAINS -> "포함"
    RuleConditionOperator.NOT_CONTAINS -> "포함하지 않음"
    RuleConditionOperator.EQUALS -> "같음"
    RuleConditionOperator.NOT_EQUALS -> "같지 않음"
    RuleConditionOperator.REGEX -> "정규식 일치"
    RuleConditionOperator.NOT_REGEX -> "정규식 불일치"
    RuleConditionOperator.GREATER_OR_EQUAL -> "이상"
    RuleConditionOperator.LESS_OR_EQUAL -> "이하"
}

private fun conditionValueValid(condition: RuleCondition): Boolean {
    if (condition.value.isBlank()) return false
    return when (condition.operator) {
        RuleConditionOperator.REGEX, RuleConditionOperator.NOT_REGEX -> runCatching { Regex(condition.value) }.isSuccess
        RuleConditionOperator.GREATER_OR_EQUAL, RuleConditionOperator.LESS_OR_EQUAL -> condition.value.toDoubleOrNull() != null
        else -> true
    }
}
