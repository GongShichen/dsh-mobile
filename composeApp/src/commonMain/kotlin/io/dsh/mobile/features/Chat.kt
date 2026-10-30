package io.dsh.mobile.features

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.dsh.mobile.AppGraph
import io.dsh.mobile.model.*
import io.dsh.mobile.sync.MobileRepository
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@Composable
fun ChatScreen(state: MobileState, repository: MobileRepository, busy: Boolean, run: RunAction) {
    var draft by remember(state.selectedSessionId) { mutableStateOf(state.draft) }
    var attachments by remember(state.selectedSessionId) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var modelMenu by remember { mutableStateOf(false) }
    var selectedModel by remember(state.selectedSessionId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val messages = state.messages.filter { it.role in setOf("user", "assistant") || it.raw.string("type") in setOf("tool/call", "tool/result", "step/error", "turn/error") }
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (!list.canScrollForward || (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= messages.size - 3) list.animateScrollToItem((messages.size - 1).coerceAtLeast(0))
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(enabled = state.connected && !busy && state.models.isNotEmpty(), onClick = { modelMenu = true }) { Text(if (state.models.isEmpty()) "暂无可用模型" else selectedModel ?: "选择模型 ▾") }
                DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                    state.models.forEach { model ->
                        val id = model.string("model") ?: model.string("id")
                        val provider = model.string("provider")
                        val label = model.string("name") ?: id ?: ""
                        if (id != null && provider != null) DropdownMenuItem(text = { Text(label) }, onClick = { modelMenu = false; run {
                            val sessionId = state.selectedSessionId ?: repository.createSession()
                            repository.call("models.select", parameters("sessionId" to sessionId, "provider" to provider, "model" to id)); selectedModel = label
                        } })
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (state.selectedSession?.running == true || state.messages.any { it.streaming }) TextButton(enabled = state.connected, onClick = { run { repository.cancelRun() } }) { Text("停止") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (state.hasMoreHistory) item { TextButton(enabled = !busy, onClick = { run { repository.loadHistory() } }, modifier = Modifier.fillMaxWidth()) { Text("加载更早的消息") } }
            if (messages.isEmpty()) item { Column(Modifier.fillMaxWidth().padding(vertical = 52.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text("继续你的想法", style = MaterialTheme.typography.headlineSmall); Spacer(Modifier.height(12.dp)); Text("消息将在这台电脑的工作区中执行。", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            items(messages, key = { it.id }) { message -> MessageCard(message) }
            items(state.interactions.filter { it.sessionId == null || it.sessionId == state.selectedSessionId }, key = { "interaction:${it.id}" }) { interaction -> InteractionCard(interaction, state.connected && !busy, repository, run) }
            val forwardedCalls = state.interactions.mapNotNull { findWaitCall(it.raw) }.toSet()
            val activeQuestions = state.projections?.obj("userQuestions")?.array("active").orEmpty().filter { (it as? JsonObject)?.string("callId") !in forwardedCalls }
            items(activeQuestions.mapNotNull { it as? JsonObject }) { question ->
                val callId = question.string("callId")
                if (callId != null) QuestionForm(question.array("questions").mapNotNull { it as? JsonObject }, state.connected && !busy) { answer -> run {
                    repository.call("questions.answer", buildJsonObject { put("sessionId", state.selectedSessionId ?: return@run); put("callId", callId); put("answer", answer) })
                } }
            }
        }
        Surface(tonalElevation = 1.dp, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                attachments.forEach { (name, receipt) -> Row(verticalAlignment = Alignment.CenterVertically) { Text(name, modifier = Modifier.weight(1f), maxLines = 1); TextButton(onClick = { attachments = attachments.filterNot { it.second == receipt } }) { Text("移除") } } }
                OutlinedTextField(draft, { text -> draft = text; scope.launch { repository.setDraft(text) } }, modifier = Modifier.fillMaxWidth(), placeholder = { Text(if (state.connected) "给 DeepSeek 发消息…" else "连接恢复后可发送") }, maxLines = 6, shape = MaterialTheme.shapes.large)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = state.connected && !busy && AppGraph.filePicker != null, onClick = { run {
                        val picker = AppGraph.filePicker ?: return@run
                        if (state.selectedSessionId == null) repository.createSession()
                        picker.pick().forEach { file ->
                            val receipt = repository.uploadAttachment(file, picker)
                            attachments = attachments + (file.name to receipt)
                        }
                    } }) { Text("＋ 附件") }
                    Spacer(Modifier.weight(1f))
                    Button(enabled = state.connected && !busy && (draft.isNotBlank() || attachments.isNotEmpty()), onClick = { val text = draft; val receipts = attachments.map { it.second }; run { repository.sendMessage(text, receipts); draft = ""; attachments = emptyList() } }) { Text("发送") }
                }
            }
        }
    }
}

@Composable
private fun MessageCard(message: MessageRecord) {
    val user = message.role == "user"
    val eventType = message.raw.string("type")
    val content = message.raw.obj("data")?.obj("message")?.array("content").orEmpty()
    val reasoning = content.mapNotNull { (it as? JsonObject)?.takeIf { value -> value.string("type") == "reasoning" }?.string("text") }.joinToString("\n")
    var thinkingOpen by remember(message.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (message.role == "event") {
            var expanded by remember(message.id) { mutableStateOf(false) }
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                TextButton(onClick = { expanded = !expanded }) { Text(when (eventType) { "tool/call" -> "工具调用"; "tool/result" -> "工具结果"; else -> "运行提示" } + "  ${message.raw.obj("data")?.string("name").orEmpty()}  ${if (expanded) "▴" else "▾"}") }
                if (expanded) CodeBlock(Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), message.raw.obj("data") ?: message.raw), "JSON")
            } }
        } else {
            if (!user) Text("DeepSeek", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            if (reasoning.isNotBlank()) {
                TextButton(onClick = { thinkingOpen = !thinkingOpen }) { Text(if (thinkingOpen) "收起思考 ▴" else "查看思考 ▾") }
                if (thinkingOpen) Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) { Box(Modifier.padding(12.dp)) { MarkdownText(reasoning) } }
            }
            Surface(color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background, shape = MaterialTheme.shapes.large) {
                Box(if (user) Modifier.padding(14.dp) else Modifier.fillMaxWidth()) { MarkdownText(message.text) }
            }
            content.mapNotNull { it as? JsonObject }.filter { it.string("type") == "tool-call" }.forEach { tool ->
                var expanded by remember(tool.string("id")) { mutableStateOf(false) }
                OutlinedCard { Column(Modifier.padding(10.dp)) { TextButton(onClick = { expanded = !expanded }) { Text("工具 · ${tool.string("name").orEmpty()} ${if (expanded) "▴" else "▾"}") }; if (expanded) CodeBlock(tool.string("arguments") ?: tool.toString()) } }
            }
            if (message.streaming) Text("正在生成…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun findQuestions(value: JsonElement, depth: Int = 0): List<JsonObject> {
    if (depth > 8) return emptyList()
    return when (value) {
        is JsonObject -> if (value.containsKey("questions")) value.array("questions").mapNotNull { it as? JsonObject } else value.values.flatMap { findQuestions(it, depth + 1) }
        is JsonArray -> value.flatMap { findQuestions(it, depth + 1) }
        else -> emptyList()
    }
}

private fun findWaitCall(value: JsonElement, depth: Int = 0): String? {
    if (depth > 8) return null
    return when (value) {
        is JsonObject -> value.obj("wait")?.string("callId") ?: value.values.firstNotNullOfOrNull { findWaitCall(it, depth + 1) }
        is JsonArray -> value.firstNotNullOfOrNull { findWaitCall(it, depth + 1) }
        else -> null
    }
}

private fun approvalDetails(value: JsonElement, depth: Int = 0): JsonObject? {
    if (depth > 8) return null
    return when (value) {
        is JsonObject -> value.takeIf { it.containsKey("toolName") } ?: value.values.firstNotNullOfOrNull { approvalDetails(it, depth + 1) }
        is JsonArray -> value.firstNotNullOfOrNull { approvalDetails(it, depth + 1) }
        else -> null
    }
}

@Composable
private fun InteractionCard(interaction: PendingInteraction, enabled: Boolean, repository: MobileRepository, run: RunAction) {
    val questions = remember(interaction.raw) { findQuestions(interaction.raw) }
    if (questions.isNotEmpty()) QuestionForm(questions, enabled) { answer -> run { repository.replyInteraction(interaction.id, buildJsonObject { put("kind", "result"); put("value", answer) }) } }
    else SectionCard(if (interaction.kind.contains("approval")) "有一项操作需要确认" else "等待你的响应") {
        var expanded by remember { mutableStateOf(false) }
        val details = remember(interaction.raw) { approvalDetails(interaction.raw) }
        details?.string("toolName")?.let { Text("工具：$it", fontWeight = FontWeight.SemiBold) }
        (details?.obj("displayReason")?.string("zh") ?: details?.string("reason"))?.let { MarkdownText(it) }
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起请求详情" else "查看请求详情") }
        if (expanded) CodeBlock(Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), interaction.raw), "JSON")
        if (interaction.kind.contains("approval")) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = enabled, onClick = { run { repository.replyInteraction(interaction.id, parameters("kind" to "result", "value" to "rejected")) } }) { Text("拒绝") }
            Button(enabled = enabled, onClick = { run { repository.replyInteraction(interaction.id, parameters("kind" to "result", "value" to "allowed-once")) } }) { Text("允许这一次") }
        } else Text("请在电脑端处理此类型的交互。")
    }
}

@Composable
private fun QuestionForm(questions: List<JsonObject>, enabled: Boolean, submit: (JsonObject) -> Unit) {
    var selected by remember(questions) { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var custom by remember(questions) { mutableStateOf<Map<String, String>>(emptyMap()) }
    SectionCard("需要你的意见") {
        questions.forEach { question ->
            val id = question.string("id") ?: return@forEach
            Text(question.string("question").orEmpty(), fontWeight = FontWeight.SemiBold)
            question.string("detail")?.let { MarkdownText(it) }
            val multi = (question["multiSelect"] as? JsonPrimitive)?.booleanOrNull == true
            question.array("options").mapNotNull { it as? JsonObject }.forEach { option ->
                val label = option.string("label") ?: return@forEach
                FilterChip(selected = selected[id]?.contains(label) == true, onClick = {
                    val previous = selected[id].orEmpty()
                    selected = selected + (id to if (!multi) listOf(label) else if (label in previous) previous - label else previous + label)
                }, enabled = enabled, label = { Text(label) })
                option.string("description")?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            if (question.obj("intent")?.string("kind") != "approval") OutlinedTextField(custom[id].orEmpty(), { custom = custom + (id to it) }, label = { Text("补充回答") }, modifier = Modifier.fillMaxWidth(), enabled = enabled)
        }
        Button(enabled = enabled && questions.all { question -> val id = question.string("id"); !selected[id].isNullOrEmpty() || !custom[id].isNullOrBlank() }, onClick = {
            submit(buildJsonObject { put("answers", buildJsonArray { questions.forEach { question -> val id = question.string("id") ?: return@forEach; add(buildJsonObject { put("id", id); put("selected", JsonArray(selected[id].orEmpty().map(::JsonPrimitive))); custom[id]?.takeIf { it.isNotBlank() }?.let { put("custom", it) } }) } }) })
        }) { Text("提交回答") }
    }
}
