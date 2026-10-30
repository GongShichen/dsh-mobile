package io.dsh.mobile.features

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import io.dsh.mobile.sync.canonicalAssistantBlocks
import io.dsh.mobile.sync.reasoningDisplayText
import io.dsh.mobile.sync.reasoningSummary
import io.dsh.mobile.design.IconTile
import io.dsh.mobile.design.PlatformActionButton
import androidx.compose.ui.unit.dp
import io.dsh.mobile.design.keyboardInputArea
import io.dsh.mobile.AppGraph
import io.dsh.mobile.model.*
import io.dsh.mobile.sync.MobileRepository
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@Composable
fun ChatScreen(state: MobileState, repository: MobileRepository, busy: Boolean, run: RunAction, onConnect: () -> Unit) {
    var draft by remember(state.selectedSessionId) { mutableStateOf(state.draft) }
    var attachments by remember(state.selectedSessionId) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun dismissInput() { focus.clearFocus(); keyboard?.hide() }
    var attachmentMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val results = state.messages.filter { it.raw.string("type") == "tool/result" }.associateBy { toolResultId(it) }
    val calls = state.messages.filter { it.raw.string("type") == "tool/call" }.mapNotNull { it.raw.obj("data")?.get("callId")?.let { value -> (value as? JsonPrimitive)?.contentOrNull } }.toSet()
    val turnStart = state.messages.lastOrNull { it.raw.string("type") == "turn/start" }?.sequence
    val turnEnd = state.messages.lastOrNull { it.raw.string("type") == "turn/end" }?.sequence ?: -1
    val activeTurn = state.selectedSession?.running == true && turnStart != null && turnStart > turnEnd
    val messages = state.messages.filter { it.role in setOf("user", "assistant") || it.raw.string("type") in setOf("tool/call", "step/error", "turn/error", "turn/end") || (it.raw.string("type") == "tool/result" && toolResultId(it) !in calls) }
    var followTail by remember(state.selectedSessionId) { mutableStateOf(true) }
    val dragging by list.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragging) { if (dragging) followTail = false }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && !list.canScrollForward) followTail = true
        }
    }
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length, messages.lastOrNull()?.revision) {
        if (followTail && !dragging && messages.isNotEmpty()) {
            list.scrollToItem((messages.size - 1).coerceAtLeast(0))
            withFrameNanos { }
            val last = list.layoutInfo.visibleItemsInfo.lastOrNull()
            if (last != null) list.scrollBy((last.offset + last.size - list.layoutInfo.viewportEndOffset).coerceAtLeast(0).toFloat())
        }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            if (state.hasMoreHistory) item { TextButton(enabled = !busy, onClick = { run { repository.loadHistory() } }, modifier = Modifier.fillMaxWidth()) { Text("加载更早的消息") } }
            if (messages.isEmpty()) item { Column(Modifier.fillMaxWidth().padding(top = 72.dp, bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) { IconTile("CHATS"); Text(if (state.hosts.isEmpty()) "有什么可以帮你？" else "继续你的想法", style = MaterialTheme.typography.titleLarge); Text(if (state.hosts.isEmpty()) "连接电脑，继续对话与任务。" else "从一个问题开始，或让电脑继续完成任务。", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(max = 280.dp)); if (state.hosts.isEmpty()) TextButton(onClick = onConnect) { Text("添加电脑") } } }
            itemsIndexed(messages, key = { _, message -> message.presentationId ?: message.id }) { index, message ->
                val previous = messages.getOrNull(index - 1)
                val presentation = message.raw.obj("data")?.number("turn")?.let { state.turnPresentation[it] }
                val lastReply = presentation == null && message.role == "assistant" && !message.streaming && message.text.isNotBlank()
                    && (messages.getOrNull(index + 1) == null || messages.getOrNull(index + 1)?.role == "user")
                Box(Modifier.padding(top = when { index == 0 -> 0.dp; message.role == "user" -> 24.dp; previous?.role == "user" -> 16.dp; else -> 2.dp })) {
                    if (message.raw.string("type") == "turn/end") {
                        presentation?.let { Column { if (it.number("firstAssistantSeq") == null) TurnStatus(it); TurnActions(it, state, repository, busy, run) } }
                    } else Column {
                        if (presentation != null && message.sequence == presentation.number("firstAssistantSeq")) TurnStatus(presentation)
                        MessageCard(message, results[(message.raw.obj("data")?.get("callId") as? JsonPrimitive)?.contentOrNull], activeTurn && (message.sequence ?: -1) > (turnStart ?: Long.MAX_VALUE), lastReply)
                    }
                }
            }
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
        Surface(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shadowElevation = 0.dp, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                attachments.forEach { (name, receipt) -> Row(verticalAlignment = Alignment.CenterVertically) { Text(name, modifier = Modifier.weight(1f), maxLines = 1); TextButton(onClick = { attachments = attachments.filterNot { it.second == receipt } }) { Text("移除") } } }
                TextField(draft, { text -> draft = text; scope.launch { repository.setDraft(text) } }, modifier = Modifier.fillMaxWidth().keyboardInputArea("composer").semantics { contentDescription = "消息输入" }, placeholder = { Text(if (state.connected) "给 DeepSeek 发消息…" else "连接恢复后可发送") }, maxLines = 6, colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface, focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent), shape = MaterialTheme.shapes.large)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        PlatformActionButton(icon = "PLUS", label = "附件", enabled = state.connected && !busy && AppGraph.filePicker != null, onClick = { dismissInput(); attachmentMenu = true })
                        DropdownMenu(expanded = attachmentMenu, onDismissRequest = { attachmentMenu = false }) {
                            listOf(true to "照片图库", false to "选择文件").forEach { (photos, label) ->
                                DropdownMenuItem(text = { Text(label) }, leadingIcon = { NavigationIcon(if (photos) "PHOTO" else "FILE", 20.dp) }, onClick = {
                                    attachmentMenu = false
                                    run {
                                        val picker = AppGraph.filePicker ?: return@run
                                        val files = picker.pick(photos)
                                        if (files.isEmpty()) return@run
                                        try {
                                            if (state.selectedSessionId == null) repository.createSession()
                                            files.forEach { file ->
                                                val receipt = repository.uploadAttachment(file, picker)
                                                attachments = attachments + (file.name to receipt)
                                            }
                                        } finally { files.forEach { picker.release(it.handle) } }
                                    }
                                })
                            }
                        }
                    }
                    ModelPicker(state, repository, busy, run, Modifier.weight(1f))

                    Spacer(Modifier.width(8.dp))
                    val generating = state.selectedSession?.running == true || state.messages.any { it.streaming }
                    PlatformActionButton(icon = if (generating) "STOP" else "SEND", label = if (generating) "停止" else "发送", filled = true, enabled = state.connected && (!busy || generating) && (generating || draft.isNotBlank() || attachments.isNotEmpty()), onClick = { dismissInput(); if (generating) run { repository.cancelRun() } else { val text = draft; val receipts = attachments.map { it.second }; run { repository.sendMessage(text, receipts); draft = ""; attachments = emptyList() } } })
                }
            }
        }
    }
}

private fun toolResultId(message: MessageRecord): String? = ((message.raw.obj("data")?.obj("message")?.get("toolCallId") ?: message.raw.obj("data")?.get("callId")) as? JsonPrimitive)?.contentOrNull

@Composable
private fun MessageCard(message: MessageRecord, toolResult: MessageRecord?, sessionRunning: Boolean, showActions: Boolean) {
    val user = message.role == "user"
    val eventType = message.raw.string("type")
    val content = message.raw.obj("data")?.obj("message")?.array("content").orEmpty()
    val blocks = message.blocks.ifEmpty { canonicalAssistantBlocks(JsonArray(content)) }
    val key = message.presentationId ?: message.id
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (message.role == "event") {
            var expanded by remember(key) { mutableStateOf(false) }
            val running = eventType == "tool/call" && toolResult == null && sessionRunning
            val name = message.raw.obj("data")?.string("name").orEmpty()
            val title = when { eventType == "tool/call" && toolResult != null -> "工具已返回"; running -> "正在调用工具"; eventType == "tool/result" -> "工具结果"; eventType == "tool/call" -> "工具调用"; else -> "运行提示" }
            val resultMessage = (toolResult ?: message).raw.obj("data")?.obj("message")
            val failed = (resultMessage?.get("isError") as? JsonPrimitive)?.booleanOrNull == true || eventType in setOf("step/error", "turn/error")
            val color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            Column(Modifier.fillMaxWidth()) {
                TextButton(onClick = { expanded = !expanded }, colors = ButtonDefaults.textButtonColors(contentColor = color),
                    contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { stateDescription = if (expanded) "已展开" else "已收起" }) {
                    if (running) CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.5.dp, color = color) else NavigationIcon(if (toolResult != null && !failed) "CHECK" else "TOOL", 16.dp)
                    Text(if (failed) "调用失败" else if (running) "正在调用" else if (name.isEmpty()) title else name,
                        Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    NavigationIcon(if (expanded) "UP" else "DOWN", 14.dp)
                }
                if (expanded) Column(Modifier.padding(start = 22.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val arguments = message.raw.obj("data")?.get("arguments")
                    arguments?.let { CodeBlock(if (it is JsonPrimitive) it.content else Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), it), "参数") }
                    val resultText = resultMessage?.array("content")?.mapNotNull { (it as? JsonObject)?.string("text") }?.joinToString("\n")
                    if (!resultText.isNullOrBlank()) CodeBlock(resultText, "输出")
                    else if (arguments == null) CodeBlock(Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), message.raw.obj("data") ?: message.raw), "详情")
                }
            }
        } else if (user) {
            Box(Modifier.fillMaxWidth().padding(start = 28.dp), contentAlignment = Alignment.CenterEnd) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface, shape = RoundedCornerShape(20.dp), modifier = Modifier.widthIn(max = 520.dp)) {
                    SelectionContainer { Text(message.text, Modifier.padding(horizontal = 16.dp, vertical = 11.dp), style = MaterialTheme.typography.bodyLarge) }
                }
            }
        } else {
            if (blocks.isEmpty() && message.streaming) RunningState("思考中…")
            if (blocks.isEmpty() && message.text.isNotEmpty()) MarkdownText(message.text)
            blocks.forEach { block -> key(key, block.index) {
                when (block.kind) {
                    "reasoning" -> ReasoningDisclosure(block.text, message.streaming && block.streaming)
                    "text" -> if (block.text.isNotEmpty()) MarkdownText(block.text)
                    "tool-call" -> if (message.streaming) {
                        RunningState("正在准备 ${block.name ?: "工具调用"}…")
                    }
                }
            } }
            if (message.streaming && message.phase == AssistantPhase.WRITING) RunningState("正在生成…")
            if (showActions) {
                val clipboard = LocalClipboardManager.current
                var copied by remember(message.id) { mutableStateOf(false) }
                IconButton(onClick = { clipboard.setText(AnnotatedString(message.text)); copied = true }, modifier = Modifier.size(48.dp).semantics { contentDescription = if (copied) "已复制回答" else "复制回答" }) {
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) { NavigationIcon(if (copied) "CHECK" else "COPY", 16.dp) }
                }
            }
            if (message.phase == AssistantPhase.INTERRUPTED) Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.extraSmall) {
                Text("已停止生成", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RunningState(label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
        CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ReasoningDisclosure(raw: String, running: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val text = reasoningDisplayText(raw)
    if (text.isBlank() && !running) return
    val summary = reasoningSummary(raw, running)
    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick = { expanded = !expanded }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { stateDescription = if (expanded) "已展开" else "已收起" }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) { NavigationIcon("THINK", 16.dp) }
            Text(if (running) "思考中" else "思考", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!expanded && summary.isNotBlank()) {
                Text("·", modifier = Modifier.padding(end = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(summary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else Spacer(Modifier.weight(1f))
            NavigationIcon(if (expanded) "UP" else "DOWN", 16.dp)
        }
        if (expanded) Box(Modifier.fillMaxWidth().padding(start = 22.dp, top = 4.dp, bottom = 12.dp)) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) { MarkdownText(text.ifEmpty { "正在思考…" }, compact = true) }
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
            if (question.obj("intent")?.string("kind") != "approval") OutlinedTextField(custom[id].orEmpty(), { custom = custom + (id to it) }, label = { Text("补充回答") }, modifier = Modifier.fillMaxWidth().keyboardInputArea(), enabled = enabled)
        }
        Button(enabled = enabled && questions.all { question -> val id = question.string("id"); !selected[id].isNullOrEmpty() || !custom[id].isNullOrBlank() }, onClick = {
            submit(buildJsonObject { put("answers", buildJsonArray { questions.forEach { question -> val id = question.string("id") ?: return@forEach; add(buildJsonObject { put("id", id); put("selected", JsonArray(selected[id].orEmpty().map(::JsonPrimitive))); custom[id]?.takeIf { it.isNotBlank() }?.let { put("custom", it) } }) } }) })
        }) { Text("提交回答") }
    }
}
