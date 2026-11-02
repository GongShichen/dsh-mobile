package io.dsh.mobile.features.personal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.dsh.mobile.features.*
import io.dsh.mobile.design.keyboardInputArea
import io.dsh.mobile.model.*
import io.dsh.mobile.sync.HLRepository
import kotlinx.serialization.json.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HLPersonalPanel(state: HLState, repository: HLRepository, initialTab: String, busy: Boolean, run: HLRunAction, close: () -> Unit) {
    var tab by remember(initialTab) { mutableStateOf(initialTab) }
    var form by remember { mutableStateOf<String?>(null) }
    var targetPicker by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf(state.personalTarget ?: HLExecutionTarget()) }
    var detail by remember { mutableStateOf<Pair<String, String>?>(null) }
    var editMemory by remember { mutableStateOf<HLPersonalMemory?>(null) }
    var forget by remember { mutableStateOf<HLPersonalMemory?>(null) }
    val snapshot = state.personal
    fun action(method: String, params: JsonObject = JsonObject(emptyMap())) = run { repository.personal.command(method, params) }
    if (targetPicker) HLExecutionTargetPicker(repository, target, { target = it ?: HLExecutionTarget() }) { targetPicker = false }
    forget?.let { memory -> HLPersonalDialog(title = "遗忘这条记忆？", close = { forget = null }, primaryLabel = "遗忘", destructive = true,
        primary = { forget = null; action("forgetMemory", buildJsonObject { put("memoryId", memory.id); put("revision", memory.revision) }) }, secondaryLabel = "保留", secondary = { forget = null }) { Text(memory.text, style = MaterialTheme.typography.bodyLarge) } }
    detail?.let { (title, text) -> HLPersonalDialog(title = title, close = { detail = null }) { Text(text, style = MaterialTheme.typography.bodyLarge) } }
    HLPersonalOverlay(title = "私人助理", close = close, maxWidth = 640.dp, avoidKeyboard = true) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(if (form != null) when (form) { "task" -> "交办事务"; "goal" -> "添加目标"; "memory" -> "个人记忆"; else -> "每天跟进" } else snapshot?.profile?.name ?: "私人助理", style = MaterialTheme.typography.titleLarge)
                    if (form == null) Text("${snapshot?.counts?.goals ?: 0} 个目标 · ${snapshot?.counts?.attention ?: 0} 项需要处理", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                TextButton(onClick = { if (form != null) { form = null; editMemory = null } else close() }) { Text(if (form == null) "完成" else "返回") }
            }
            if (snapshot == null) { HLEmptyPanel("正在读取", state.personalError ?: "请保持与电脑连接。"); return@Column }
            if (form != null) {
                HLPersonalRecordForm(form!!, snapshot, target, editMemory, busy, { targetPicker = true }) { method, params ->
                    run { repository.personal.command(method, params); form = null; editMemory = null }
                }
                return@Column
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("tasks" to "事务", "goals" to "目标", "memory" to "记忆", "routines" to "规则", "connections" to "账户", "activity" to "活动").forEach { (id, title) ->
                    FilterChip(selected = tab == id, onClick = { tab = id; detail = null }, label = { Text(title) })
                }
            }
            if (snapshot.profile.enabled && tab in setOf("tasks", "goals", "memory", "routines")) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(enabled = !busy && state.connected, onClick = { form = when (tab) { "tasks" -> "task"; "goals" -> "goal"; "memory" -> "memory"; else -> "routine" } }) { Text(when (tab) { "tasks" -> "交办新事务"; "goals" -> "添加目标"; "memory" -> "添加记忆"; else -> "添加规则" }) }
            }
            LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (tab == "tasks") {
                    if (snapshot.tasks.isEmpty()) item { Text("还没有事务。可以在主对话中交办，也可以选择已有工作区继续。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(snapshot.tasks, key = { it.id }) { task -> HLPersonalCard {
                        HLPersonalCardHeader(task.title, task.statusLabel())
                        Text((task.goalId?.let { id -> snapshot.goals.firstOrNull { it.id == id }?.title } ?: "独立事务") + " · " + when (task.target.kind) { "workspace-new" -> "工作区新会话"; "session-continue" -> "继续已有会话"; else -> personalResultLabel(task.resultKind) }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (task.summary.isNotBlank()) Text(task.summary, style = MaterialTheme.typography.bodyMedium)
                        task.binding?.let { binding -> Text(if (binding.ownership == "internal") "助理内部会话" else binding.workspaceTitle ?: binding.cwd?.substringAfterLast('/') ?: "已关联 DSH 会话", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (task.state !in setOf("completed", "cancelled", "failed") && snapshot.connections.any { it.state == "connected" }) {
                            Text("此事务可使用的账户", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                snapshot.connections.filter { it.state == "connected" }.forEach { account -> FilterChip(selected = account.id in task.accountIds, enabled = !busy,
                                    onClick = { action("setTaskAccounts", buildJsonObject { put("taskId", task.id); put("revision", task.revision); put("accountIds", JsonArray((if (account.id in task.accountIds) task.accountIds - account.id else task.accountIds + account.id).map(::JsonPrimitive))) }) }, label = { Text(account.email) }) }
                            }
                        }
                        val prepared = snapshot.actions.filter { it.taskId == task.id && it.state == "prepared" }
                        prepared.forEach { action -> Text(action.preview, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth()) }
                        prepared.groupBy { it.accountId }.forEach { (_, actions) ->
                            Text("此事务、相同账户与对象，最多 10 次写入，有效期 24 小时。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FilledTonalButton(enabled = !busy && state.connected, onClick = { action("authorizeTask", buildJsonObject { put("taskId", task.id); put("actionIds", JsonArray(actions.map { JsonPrimitive(it.id) })) }) }) { Text("批准任务范围") }
                            if (task.source["kind"]?.jsonPrimitive?.contentOrNull == "routine") TextButton(enabled = !busy && state.connected,
                                onClick = { action("authorizeTask", buildJsonObject { put("taskId", task.id); put("scope", "routine"); put("actionIds", JsonArray(actions.map { JsonPrimitive(it.id) })) }) }) { Text("按此规则授权 30 天 · 每次最多 10 次写入") }
                        }
                        snapshot.actions.filter { it.taskId == task.id && it.state == "unknown" }.forEach { pending -> TextButton(enabled = !busy, onClick = { action("reconcileAction", buildJsonObject { put("actionId", pending.id) }) }) { Text("重新核对外部操作") } }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            task.binding?.let { binding -> TextButton(onClick = { close(); run { repository.openDelegatedSession(binding.sessionId) } }) { Text("打开执行会话") } }
                            TextButton(onClick = { run { val result = repository.personal.read("getTask", buildJsonObject { put("taskId", task.id) }).jsonObject; (result["task"] as? JsonObject)?.get("instruction")?.jsonPrimitive?.contentOrNull?.let { detail = "交办内容" to it } } }) { Text("交办内容") }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (task.state == "waiting" && task.waitReason == "verification" && task.result != null) FilledTonalButton(enabled = !busy, onClick = { action("confirmTask", buildJsonObject { put("taskId", task.id); put("revision", task.revision) }) }) { Text("确认成果") }
                            if (task.state in setOf("waiting", "interrupted", "failed", "cancelled")) TextButton(enabled = !busy, onClick = { action("resumeTask", buildJsonObject { put("taskId", task.id); put("revision", task.revision) }) }) { Text("继续") }
                            if (task.state !in setOf("completed", "failed", "cancelled")) TextButton(enabled = !busy, onClick = { action("cancelTask", buildJsonObject { put("taskId", task.id); put("revision", task.revision) }) }) { Text("停止委托") }
                        }
                    } }
                }
                if (tab == "goals") items(snapshot.goals, key = { it.id }) { goal -> HLPersonalCard {
                    HLPersonalCardHeader(goal.title, personalStatusLabel(goal.state)); Text(goal.objective, style = MaterialTheme.typography.bodyMedium)
                    goal.milestones.forEach { milestone -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = milestone.state == "completed", enabled = !busy, onCheckedChange = { checked -> action("updateGoal", buildJsonObject {
                            put("goalId", goal.id); put("revision", goal.revision); put("milestones", JsonArray(goal.milestones.map { item -> buildJsonObject { put("id", item.id); put("title", item.title); put("state", if (item.id == milestone.id) if (checked) "completed" else "open" else item.state) } }))
                        }) }); Text(milestone.title, style = MaterialTheme.typography.bodyMedium)
                    } }
                    Row { TextButton(enabled = !busy, onClick = { action("updateGoal", buildJsonObject { put("goalId", goal.id); put("revision", goal.revision); put("state", if (goal.state == "active") "paused" else "active") }) }) { Text(if (goal.state == "active") "暂停目标" else "继续目标") }
                        TextButton(enabled = !busy, onClick = { action("updateGoal", buildJsonObject { put("goalId", goal.id); put("revision", goal.revision); put("state", "completed") }) }) { Text("确认目标完成") } }
                } }
                if (tab == "memory") items(snapshot.memories, key = { it.id }) { memory -> HLPersonalCard {
                    Text(if (memory.kind == "preference") "个人偏好" else "已确认事实", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(memory.text, style = MaterialTheme.typography.bodyMedium); memory.quote?.let { Text("来自你的原话：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Row { TextButton(onClick = { editMemory = memory; form = "memory" }) { Text("修改") }; TextButton(enabled = !busy, onClick = { forget = memory }) { Text("遗忘") } }
                } }
                if (tab == "routines") items(snapshot.routines, key = { it.id }) { routine -> HLPersonalCard {
                    HLPersonalCardHeader(routine.title, personalStatusLabel(routine.state)); Text(routine.instruction, style = MaterialTheme.typography.bodyMedium)
                    if (routine.errorCode != null) Text("创建或更新结果待核实，请在 DSH 自动化任务中核对。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (routine.state == "unknown") TextButton(enabled = !busy, onClick = { action("reconcileRoutine", buildJsonObject { put("routineId", routine.id); put("revision", routine.revision) }) }) { Text("核对规则") }
                    TextButton(enabled = !busy && routine.state != "unknown", onClick = { action("editRoutine", buildJsonObject { put("routineId", routine.id); put("revision", routine.revision); put("active", routine.state != "active") }) }) { Text(if (routine.state == "active") "暂停规则" else "恢复规则") }
                } }
                if (tab == "connections") {
                    item { HLPersonalCard {
                        Text("Google 日历与 Gmail · 可选", style = MaterialTheme.typography.titleMedium)
                        Text(if (snapshot.googleConfigured) "这台电脑已配置 OAuth" else "尚未配置", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { detail = "配置 Google" to "使用自己的 Google Cloud 项目启用 Gmail 和 Calendar API，配置同意屏幕并添加自己的测试账号，创建“桌面应用”OAuth 客户端。在电脑私人助理的“账户”页导入下载的 JSON 后登录。配置和账户 token 留在电脑钥匙串。" }) { Text("查看配置方法") }
                        if (state.personalGoogleReminderSuppressed) TextButton(onClick = { run { repository.setGoogleSetupReminderSuppressed(false) } }) { Text("恢复配置提醒") }
                    } }
                    items(snapshot.connections, key = { it.id }) { connection -> HLPersonalCard {
                        HLPersonalCardHeader(connection.email, when (connection.state) { "connected" -> "已连接"; "reauth-required" -> "需要重新登录"; else -> "已断开" })
                        TextButton(enabled = !busy && connection.state != "disconnected", onClick = { action("disconnectGoogle", buildJsonObject { put("accountId", connection.id) }) }) { Text("断开账户并撤销授权") }
                    } }
                    item { HLPersonalCard { Text("执行设置", style = MaterialTheme.typography.titleMedium); TextButton(enabled = !busy, onClick = { action("configure", buildJsonObject { put("revision", snapshot.profile.revision); put("paused", !snapshot.profile.paused) }) }) { Text(if (snapshot.profile.paused) "恢复私人助理执行" else "暂停所有私人事务") } } }
                    items(snapshot.grants.filter { it.state == "active" && it.parentGrantId == null }, key = { it.id }) { grant -> HLPersonalCard {
                        Text(if (grant.scope == "routine") "周期规则授权" else "事务授权", style = MaterialTheme.typography.titleSmall)
                        Text((grant.operations + grant.recipients + grant.calendarIds).joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (grant.scope == "routine") "每次最多 ${grant.maxWrites} 次写入" else "剩余 ${grant.maxWrites - grant.usedWrites} 次写入", style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = !busy, onClick = { action("revokeGrant", buildJsonObject { put("grantId", grant.id); put("revision", grant.revision) }) }) { Text("撤销此授权") }
                    } }
                }
                if (tab == "activity") items(snapshot.activity, key = { it.id }) { activity -> HLPersonalCard { Text(activity.summary, style = MaterialTheme.typography.bodyMedium) } }
            }
        }
    }
}

@Composable
private fun HLPersonalCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }
}
@Composable
private fun HLPersonalCardHeader(title: String, status: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) { Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall); Spacer(Modifier.width(10.dp)); Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}
@Composable
private fun HLPersonalRecordForm(kind: String, snapshot: HLPersonalSnapshot, target: HLExecutionTarget, memory: HLPersonalMemory?, busy: Boolean,
    chooseTarget: () -> Unit, submit: (String, JsonObject) -> Unit) {
    var title by remember(kind, memory?.id) { mutableStateOf("") }
    var text by remember(kind, memory?.id) { mutableStateOf(memory?.text.orEmpty()) }
    var goalId by remember { mutableStateOf<String?>(null) }
    var accountIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var resultKind by remember { mutableStateOf("text") }
    var milestones by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (kind != "memory") OutlinedTextField(title, { title = it.take(120) }, Modifier.fillMaxWidth().keyboardInputArea("personal-form-title"), label = { Text("名称") }, singleLine = true)
        OutlinedTextField(text, { text = it.take(if (kind == "memory") 2000 else 8000) }, Modifier.fillMaxWidth().heightIn(min = 140.dp).keyboardInputArea("personal-form-body"),
            label = { Text(if (kind == "memory") "记住的内容" else "希望达到的结果") }, minLines = 4)
        if (kind == "goal") OutlinedTextField(milestones, { milestones = it.take(2400) }, Modifier.fillMaxWidth().keyboardInputArea("personal-goal-milestones"), label = { Text("阶段目标 · 可选，每行一项") }, minLines = 2)
        if (kind in setOf("task", "routine")) {
            TextButton(onClick = chooseTarget) { HLNavigationIcon("WORKSPACES", 18.dp); Spacer(Modifier.width(8.dp)); Text(when (target.kind) { "workspace-new" -> "所选工作区 · 新建会话"; "session-continue" -> "所选会话 · 继续执行"; else -> "执行位置：内部会话" }) }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = goalId == null, onClick = { goalId = null }, label = { Text("独立事务") })
                snapshot.goals.filter { it.state == "active" }.forEach { goal -> FilterChip(selected = goalId == goal.id, onClick = { goalId = goal.id }, label = { Text(goal.title) }) }
            }
            if (target.kind == "internal") {
                Text("交付结果", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("text", "files", "email", "calendar").forEach { id -> FilterChip(selected = resultKind == id, onClick = { resultKind = id }, label = { Text(personalResultLabel(id)) }) }
                }
            }
        }
        if (kind == "routine") Text("从现在起每 24 小时执行一次。其他日期、时区和周期可以在主对话中交办。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (kind in setOf("task", "routine") && snapshot.connections.any { it.state == "connected" }) {
            Text("允许使用的账户 · 写入仍需另行授权", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            snapshot.connections.filter { it.state == "connected" }.forEach { account -> FilterChip(selected = account.id in accountIds, onClick = { accountIds = if (account.id in accountIds) accountIds - account.id else accountIds + account.id }, label = { Text(account.email) }) }
        }
        Button(enabled = !busy && text.isNotBlank() && (kind == "memory" || title.isNotBlank()), onClick = {
            val method = when (kind) { "task" -> "createTask"; "goal" -> "createGoal"; "memory" -> "putMemory"; else -> "createRoutine" }
            val params = buildJsonObject {
                if (kind != "memory") put("title", title)
                when (kind) {
                    "memory" -> { put("text", text); put("kind", memory?.kind ?: "fact"); memory?.let { put("memoryId", it.id); put("revision", it.revision) } }
                    "goal" -> { put("objective", text); if (milestones.isNotBlank()) put("milestones", JsonArray(milestones.lines().map(String::trim).filter(String::isNotBlank).map(::JsonPrimitive))) }
                    else -> { put("instruction", text); put("target", target.toJson()); put("resultKind", if (target.kind == "internal") resultKind else "session"); put("accountIds", JsonArray(accountIds.map(::JsonPrimitive))); goalId?.let { put("goalId", it) }; if (kind == "routine") put("timing", buildJsonObject { put("kind", "every"); put("every_seconds", 86400) }) }
                }
            }
            submit(method, params)
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) { Text(if (kind == "memory") "保存记忆" else if (kind == "task") "交给助理" else "创建") }
    }
}
