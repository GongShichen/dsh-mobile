package io.dsh.mobile.features

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.dsh.mobile.design.HLKeyboardDismissalHost
import io.dsh.mobile.design.interactionDialogProperties
import io.dsh.mobile.design.keyboardInputArea
import io.dsh.mobile.model.HLState
import io.dsh.mobile.sync.HLInteractionPrompt
import io.dsh.mobile.sync.HLRepository
import io.dsh.mobile.sync.interactionPrompts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

private data class HLQuestionDraft(val selected: List<String> = emptyList(), val custom: String = "", val skipped: Boolean = false)

/** A single global queue, independent of the conversation or screen currently being viewed. */
@Composable
fun HLInteractionHost(state: HLState, repository: HLRepository, onOpenSession: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val bodyScroll = rememberScrollState()
    val prompts = interactionPrompts(state)
    var selected by remember(state.activeHostId) { mutableStateOf<String?>(null) }
    var hidden by remember(state.activeHostId) { mutableStateOf<Set<String>>(emptySet()) }
    var drafts by remember(state.activeHostId) { mutableStateOf<Map<String, Map<String, HLQuestionDraft>>>(emptyMap()) }
    var submitting by remember(state.activeHostId) { mutableStateOf<Set<String>>(emptySet()) }
    var errors by remember(state.activeHostId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    val keys = prompts.map { it.key }
    LaunchedEffect(keys, state.connected, state.interactionsReady) {
        hidden = hidden.intersect(keys.toSet())
        drafts = drafts.filterKeys { it in keys }
        errors = errors.filterKeys { it in keys }
        if (!state.connected || !state.interactionsReady) { selected = null; return@LaunchedEffect }
        if (selected !in keys) selected = prompts.firstOrNull { it.key !in hidden }?.key
    }
    val active = prompts.firstOrNull { it.key == selected }
    LaunchedEffect(active?.key) { if (active != null) { focus.clearFocus(); keyboard?.hide(); bodyScroll.scrollTo(0) } }
    fun hide(prompt: HLInteractionPrompt) { hidden = hidden + prompt.key; selected = null; keyboard?.hide() }
    fun submit(prompt: HLInteractionPrompt, answer: JsonElement) {
        if (prompt.key in submitting) return
        submitting = submitting + prompt.key
        errors = errors - prompt.key
        scope.launch {
            try { repository.answerInteraction(prompt, answer) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { errors = errors + (prompt.key to (error.message ?: "提交失败，请重试")) }
            finally { submitting = submitting - prompt.key }
        }
    }
    if (prompts.isNotEmpty() && state.connected) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, tonalElevation = 1.dp) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${prompts.size} 项待处理", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                TextButton(enabled = state.interactionsReady, onClick = { selected = prompts.first().key }) { Text("查看待处理") }
            }
        }
    }
    if (active != null && state.connected) {
        DisposableEffect(active.key, active.eventId, active.clientId) {
            active.eventId?.let { id -> scope.launch { repository.focusInteraction(id, true, active.clientId) } }
            onDispose { active.eventId?.let { id -> scope.launch { repository.focusInteraction(id, false, active.clientId) } } }
        }
        val pending = active.key in submitting
        val timed = active.request.obj("wait")?.get("timed") == JsonPrimitive(true)
        val canAnswer = !pending && state.interactionsReady && (active.eventId != null || active.continued) && active.timing?.expired != true
            && !(timed && "interaction-waits" in state.capabilities && active.timing?.attached != true)
        val intent = active.questions.singleOrNull()?.obj("intent")
        val fixed = intent?.string("kind") == "approval"
        val plan = intent?.string("kind") == "plan-review"
        val title = when { active.kind == "approval/request" || fixed -> "权限确认"; plan -> "计划确认"; else -> "需要你的回答" }
        val session = state.sessions.firstOrNull { it.id == active.sessionId }
        Dialog(onDismissRequest = { hide(active) }, properties = interactionDialogProperties()) {
            HLKeyboardDismissalHost {
                BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 24.dp), contentAlignment = Alignment.Center) {
                    Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight * .92f), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
                        Column {
                            Row(Modifier.fillMaxWidth().padding(start = 24.dp, top = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(title, style = MaterialTheme.typography.titleLarge)
                                    Text(session?.title?.takeIf { it.isNotBlank() }?.let { "来自：$it" } ?: "来自桌面会话", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = { hide(active) }) { Text("稍后处理") }
                            }
                            HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            if (prompts.size > 1) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                val index = prompts.indexOfFirst { it.key == active.key }
                                TextButton(enabled = index > 0, onClick = { selected = prompts[index - 1].key }) { Text("上一项") }
                                Text("${index + 1} / ${prompts.size}", style = MaterialTheme.typography.labelMedium)
                                TextButton(enabled = index < prompts.lastIndex, onClick = { selected = prompts[index + 1].key }) { Text("下一项") }
                            }
                            Column(Modifier.weight(1f, fill = false).verticalScroll(bodyScroll).semantics { contentDescription = "待处理请求内容" }.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                if (active.continued) Text("这条询问仍可补答，提交后会继续原会话。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (active.kind == "approval/request") HLApprovalDetails(active)
                                else if (fixed) {
                                    val question = active.questions.single()
                                    Text(localized(intent?.obj("display")?.obj("question")) ?: question.string("question").orEmpty(), style = MaterialTheme.typography.titleMedium)
                                    (localized(intent?.obj("display")?.obj("detail")) ?: question.string("detail"))?.let { HLMarkdownText(it) }
                                    intent?.obj("subject")?.string("name")?.let { Text("请求方：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                } else HLQuestionFields(active.questions, drafts[active.key].orEmpty(), canAnswer, plan) { id, value ->
                                    drafts = drafts + (active.key to (drafts[active.key].orEmpty() + (id to value)))
                                    active.eventId?.let { scope.launch { repository.engageInteraction(it) } }
                                }
                                active.timing?.let { timing ->
                                    Text(when {
                                        !timing.attached -> "正在同步等待状态…"
                                        timing.expired -> "正在同步桌面后续状态…"
                                        timing.remainingMs == null -> "已保留询问，等待你提交"
                                        timing.paused -> "等待你的回答，倒计时已暂停"
                                        else -> "剩余 ${(timing.remainingMs!! + 999) / 1000} 秒"
                                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (timing.attached && timing.remainingMs != null && !timing.expired) active.eventId?.let { id -> TextButton(onClick = { scope.launch { repository.holdInteraction(id) } }) { Text("我需要更多时间") } }
                                }
                                if (active.eventId == null && !active.continued) {
                                    Text("正在同步询问的处理通道。", style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = { scope.launch { repository.connect(active.hostId) } }) { Text("重新同步") }
                                }
                                if (session != null) TextButton(onClick = { hide(active); onOpenSession(session.id) }) { Text("查看所属会话") }
                                errors[active.key]?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (pending) LinearProgressIndicator(Modifier.fillMaxWidth())
                                when {
                                    active.kind == "approval/request" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        OutlinedButton(enabled = canAnswer, modifier = Modifier.weight(1f).heightIn(min = 48.dp), onClick = { submit(active, JsonPrimitive("rejected")) }) { Text("拒绝") }
                                        Button(enabled = canAnswer, modifier = Modifier.weight(1f).heightIn(min = 48.dp), onClick = { submit(active, JsonPrimitive("allowed-once")) }) { Text("允许这一次") }
                                    }
                                    fixed -> {
                                        val q = active.questions.single()
                                        fun answer(label: String) = buildJsonObject { put("answers", buildJsonArray { add(buildJsonObject { put("id", q.string("id")); put("selected", buildJsonArray { add(label) }) }) }) }
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            OutlinedButton(enabled = canAnswer, modifier = Modifier.weight(1f).heightIn(min = 48.dp), onClick = { intent?.string("reject")?.let { submit(active, answer(it)) } }) { Text("拒绝") }
                                            Button(enabled = canAnswer, modifier = Modifier.weight(1f).heightIn(min = 48.dp), onClick = { intent?.string("approve")?.let { submit(active, answer(it)) } }) { Text(localized(intent?.obj("display")?.obj("approve")) ?: "允许") }
                                        }
                                        intent?.string("always")?.let { label -> TextButton(enabled = canAnswer, onClick = { submit(active, answer(label)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("始终允许") } }
                                    }
                                    else -> {
                                        val values = drafts[active.key].orEmpty()
                                        Button(enabled = canAnswer && active.questions.isNotEmpty() && active.questions.all { q -> values[q.string("id")]?.let { it.selected.isNotEmpty() || it.custom.isNotBlank() || it.skipped } == true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = {
                                            submit(active, buildJsonObject { put("answers", buildJsonArray { active.questions.forEach { q ->
                                                val id = q.string("id") ?: return@forEach; val value = values[id] ?: return@forEach
                                                add(buildJsonObject { put("id", id); put("selected", JsonArray(value.selected.map(::JsonPrimitive))); value.custom.trim().takeIf { it.isNotEmpty() }?.let { put("custom", it) } })
                                            } }) })
                                        }) { Text(if (plan) "提交决定" else "提交回答") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun localized(value: JsonObject?): String? = value?.string("zh") ?: value?.string("zh-CN") ?: value?.string("en")

@Composable
private fun HLApprovalDetails(prompt: HLInteractionPrompt) {
    prompt.request.string("toolName")?.let { Text("执行工具：$it", style = MaterialTheme.typography.titleMedium) }
    (localized(prompt.request.obj("displayReason")) ?: prompt.request.string("reason"))?.let { HLMarkdownText(it) }
    val raw = prompt.context?.get("arguments") ?: prompt.context?.get("args")
    val args = (raw as? JsonObject) ?: (raw as? JsonPrimitive)?.contentOrNull?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
    args?.string("command")?.let { Text("执行命令", fontWeight = FontWeight.Medium); HLCodeBlock(it, "shell") }
    args?.string("path")?.let { Text("路径：$it", style = MaterialTheme.typography.bodyMedium) }
    (args?.string("cwd") ?: prompt.context?.string("cwd"))?.let { Text("工作目录：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    if (raw != null) {
        var expanded by remember(prompt.key) { mutableStateOf(false) }
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起完整参数" else "查看完整参数") }
        if (expanded) HLCodeBlock(Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), args ?: raw), "json")
    }
}

@Composable
private fun HLQuestionFields(questions: List<JsonObject>, drafts: Map<String, HLQuestionDraft>, enabled: Boolean, plan: Boolean, update: (String, HLQuestionDraft) -> Unit) {
    questions.forEachIndexed { index, question ->
        val id = question.string("id") ?: return@forEachIndexed
        val draft = drafts[id] ?: HLQuestionDraft()
        if (questions.size > 1) Text("问题 ${index + 1}/${questions.size}" + question.string("header")?.let { " · $it" }.orEmpty(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(question.string("question").orEmpty(), style = MaterialTheme.typography.titleMedium)
        question.string("detail")?.let { HLMarkdownText(it) }
        val multi = (question["multiSelect"] as? JsonPrimitive)?.booleanOrNull == true
        if (multi) Text("可以选择多项", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        question.array("options").mapNotNull { it as? JsonObject }.forEach { option ->
            val label = option.string("label") ?: return@forEach
            val checked = label in draft.selected
            Surface(Modifier.fillMaxWidth().clickable(enabled = enabled) {
                val selected = if (!multi) listOf(label) else if (checked) draft.selected - label else draft.selected + label
                update(id, draft.copy(selected = selected, custom = if (!multi && !plan) "" else draft.custom, skipped = false))
            }, color = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                Row(Modifier.padding(12.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (multi) Checkbox(checked, onCheckedChange = null, enabled = enabled) else RadioButton(checked, onClick = null, enabled = enabled)
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                        option.string("description")?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
        OutlinedTextField(draft.custom, { update(id, draft.copy(custom = it, selected = if (!multi && !plan && it.isNotBlank()) emptyList() else draft.selected, skipped = false)) },
            label = { Text(if (plan) "补充意见" else "补充回答") }, minLines = 2, maxLines = 5, enabled = enabled,
            modifier = Modifier.fillMaxWidth().keyboardInputArea("interaction-answer").semantics { contentDescription = "补充回答：${question.string("header") ?: question.string("question").orEmpty()}" })
        if (!plan) TextButton(enabled = enabled, onClick = { update(id, HLQuestionDraft(skipped = !draft.skipped)) }) { Text(if (draft.skipped) "已跳过此题 · 撤销" else "跳过此题") }
        if (index < questions.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
