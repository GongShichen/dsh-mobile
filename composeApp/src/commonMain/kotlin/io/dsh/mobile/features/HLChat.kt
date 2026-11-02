package io.dsh.mobile.features

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import io.dsh.mobile.sync.canonicalAssistantBlocks
import io.dsh.mobile.sync.reasoningDisplayText
import io.dsh.mobile.sync.reasoningSummary
import io.dsh.mobile.sync.HLConversationProjector
import io.dsh.mobile.sync.HLConversationRowKind
import io.dsh.mobile.sync.visibleConversationRows
import androidx.compose.animation.core.tween
import io.dsh.mobile.design.HLBrandMark
import io.dsh.mobile.design.HLDisclosureButton
import io.dsh.mobile.design.HLPlatformActionButton
import androidx.compose.ui.unit.dp
import io.dsh.mobile.design.keyboardInputArea
import io.dsh.mobile.HLAppGraph
import io.dsh.mobile.model.*
import io.dsh.mobile.sync.HLRepository
import io.dsh.mobile.sync.HLConversationViewKey
import io.dsh.mobile.sync.HLConversationViewSnapshot
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@Composable
fun HLChatScreen(state: HLState, repository: HLRepository, busy: Boolean, run: HLRunAction, onConnect: () -> Unit) {
    val viewKey = HLConversationViewKey(state.activeHostId.orEmpty(), state.conversationMode, state.selectedSessionId)
    val retained = remember(viewKey) { repository.conversationViews.get(viewKey) }
    var draft by remember(viewKey) { mutableStateOf(retained.draft.ifEmpty { state.draft }) }
    var attachments by remember(viewKey) { mutableStateOf(retained.attachments) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun dismissInput() { focus.clearFocus(); keyboard?.hide() }
    var attachmentMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val list = remember(viewKey) { LazyListState(retained.scrollIndex, retained.scrollOffset) }
    val projector = remember(state.activeHostId, state.selectedSessionId) { HLConversationProjector() }
    val disclosure = remember(viewKey) { HLConversationDisclosure().apply { turns.putAll(retained.turns); groups.putAll(retained.groups); leaves.putAll(retained.leaves) } }
    val timeline = projector.project(state.messages, state.turnPresentation, state.selectedSession?.running == true)
    val rows = visibleConversationRows(timeline,
        timeline.turns.values.filter(disclosure::turnOpen).map { it.key }.toSet(),
        timeline.groups.values.filter(disclosure::groupOpen).map { it.key }.toSet())
    var followTail by remember(viewKey) { mutableStateOf(retained.followTail) }
    LaunchedEffect(state.composerRevision, viewKey) {
        val stored = repository.conversationViews.get(viewKey)
        attachments = stored.attachments
        if (state.composerRevision > 0) draft = stored.draft
    }
    DisposableEffect(viewKey) {
        onDispose { repository.conversationViews.save(viewKey, HLConversationViewSnapshot(draft, attachments, list.firstVisibleItemIndex,
            list.firstVisibleItemScrollOffset, followTail, disclosure.turns.toMap(), disclosure.groups.toMap(), disclosure.leaves.toMap())) }
    }
    val dragging by list.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragging) { if (dragging) followTail = false }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && !list.canScrollForward) followTail = true
        }
    }
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text?.length, state.messages.lastOrNull()?.revision) {
        if (followTail && !dragging && rows.isNotEmpty()) {
            list.scrollToItem((rows.size - 1 + if (state.hasMoreHistory) 1 else 0).coerceAtLeast(0))
            withFrameNanos { }
            val last = list.layoutInfo.visibleItemsInfo.lastOrNull()
            if (last != null) list.scrollBy((last.offset + last.size - list.layoutInfo.viewportEndOffset).coerceAtLeast(0).toFloat())
        }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            if (state.hasMoreHistory) item { TextButton(enabled = !busy, onClick = { run { repository.loadHistory() } }, modifier = Modifier.fillMaxWidth()) { Text("加载更早的消息") } }
            if (rows.isEmpty()) item { Column(Modifier.fillMaxWidth().padding(top = 72.dp, bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) { HLBrandMark(Modifier.size(72.dp)); Text(if (state.hosts.isEmpty()) "有什么可以帮你？" else "继续你的想法", style = MaterialTheme.typography.titleLarge); Text(if (state.hosts.isEmpty()) "连接电脑，继续对话与任务。" else "从一个问题开始，或让电脑继续完成任务。", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(max = 280.dp)); if (state.hosts.isEmpty()) TextButton(onClick = onConnect) { Text("添加电脑") } } }
            itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                val turn = timeline.turns[row.turnKey]
                val previous = rows.getOrNull(index - 1)
                Box(Modifier.animateItem(fadeInSpec = tween(140), fadeOutSpec = tween(100), placementSpec = tween(180))
                    .padding(start = if (row.process) 18.dp else 0.dp,
                        top = when { index == 0 -> 0.dp; row.message?.role == "user" -> 24.dp; previous?.message?.role == "user" -> 16.dp; else -> 2.dp })) {
                    when (row.kind) {
                        HLConversationRowKind.TURN_HEADER -> turn?.presentation?.let { presentation ->
                            HLTurnStatus(presentation, turn.number, if (turn.foldable) disclosure.turnOpen(turn) else null,
                                if (turn.foldable) ({ followTail = false; disclosure.toggleTurn(turn, timeline) }) else null)
                        }
                        HLConversationRowKind.PROCESS_HEADER -> timeline.groups[row.groupKey]?.let { group ->
                            HLProcessGroupHeader(group, disclosure.groupOpen(group)) { followTail = false; disclosure.groups[group.key] = !disclosure.groupOpen(group) }
                        }
                        HLConversationRowKind.TURN_ACTIONS -> turn?.presentation?.let { HLTurnActions(it, state, repository, busy, run) }
                        HLConversationRowKind.MESSAGE -> row.message?.let { message ->
                            HLMessageCard(message, row.toolResult, turn?.running == true, row.copyReply, disclosure, "${row.turnKey}|${row.key}")
                        }
                    }
                }
            }

        }
        Surface(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp), shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shadowElevation = 0.dp, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                attachments.forEach { (name, receipt) -> Row(verticalAlignment = Alignment.CenterVertically) { Text(name, modifier = Modifier.weight(1f), maxLines = 1); TextButton(onClick = { attachments = attachments.filterNot { it.second == receipt }; repository.conversationViews.save(viewKey, repository.conversationViews.get(viewKey).copy(attachments = attachments)) }) { Text("移除") } } }
                TextField(draft, { text -> draft = text; repository.conversationViews.updateDraft(viewKey, text); scope.launch { repository.setDraft(text, state.selectedSessionId, state.activeHostId) } }, modifier = Modifier.fillMaxWidth().keyboardInputArea("composer").semantics { contentDescription = "消息输入" }, placeholder = { Text(if (!state.connected) "连接恢复后可发送" else if (state.conversationMode == HLConversationMode.PERSONAL) "交办事务，或继续聊聊…" else "给 DeepSeek 发消息…") }, maxLines = 6, colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface, focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent), shape = MaterialTheme.shapes.large)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        HLPlatformActionButton(icon = "PLUS", label = "附件", enabled = state.connected && !busy && HLAppGraph.filePicker != null, onClick = { dismissInput(); attachmentMenu = true })
                        DropdownMenu(expanded = attachmentMenu, onDismissRequest = { attachmentMenu = false }) {
                            listOf(true to "照片图库", false to "选择文件").forEach { (photos, label) ->
                                DropdownMenuItem(text = { Text(label) }, leadingIcon = { HLNavigationIcon(if (photos) "PHOTO" else "FILE", 20.dp) }, onClick = {
                                    attachmentMenu = false
                                    run {
                                        val picker = HLAppGraph.filePicker ?: return@run
                                        val files = picker.pick(photos)
                                        if (files.isEmpty()) return@run
                                        try {
                                            if (repository.state.value.activeHostId != state.activeHostId || repository.state.value.conversationMode != state.conversationMode) throw kotlinx.coroutines.CancellationException("Composer changed during file selection")
                                            val uploadSession = state.selectedSessionId ?: repository.createSession()
                                            files.forEach { file ->
                                                val receipt = repository.uploadAttachment(file, picker, uploadSession)
                                                repository.retainAttachment(HLConversationViewKey(state.activeHostId.orEmpty(), state.conversationMode, uploadSession), file.name, receipt)
                                            }
                                        } finally { files.forEach { picker.release(it.handle) } }
                                    }
                                })
                            }
                        }
                    }
                    HLModelPicker(state, repository, busy, run, Modifier.weight(1f))

                    Spacer(Modifier.width(8.dp))
                    val generating = state.selectedSession?.running == true || state.messages.any { it.streaming }
                    HLPlatformActionButton(icon = if (generating) "STOP" else "SEND", label = if (generating) "停止" else "发送", filled = true, enabled = state.connected && (!busy || generating) && (generating || draft.isNotBlank() || attachments.isNotEmpty()), onClick = { dismissInput(); if (generating) run { state.selectedSessionId?.let { repository.cancelRun(it, state.activeHostId) } } else { val text = draft; val receipts = attachments.map { it.second }; run { repository.sendMessage(text, receipts, state.selectedSessionId, state.conversationMode, state.activeHostId, state.personalTarget); repository.clearComposer(viewKey); draft = ""; attachments = emptyList() } } })
                }
            }
        }
        HLComposerDock(state, repository, busy, run)
    }
}

@Composable
private fun HLMessageCard(message: HLMessageRecord, toolResult: HLMessageRecord?, sessionRunning: Boolean, showActions: Boolean, disclosure: HLConversationDisclosure, disclosureKey: String) {
    val user = message.role == "user"
    val eventType = message.raw.string("type")
    val content = message.raw.obj("data")?.obj("message")?.array("content").orEmpty()
    val blocks = message.blocks.ifEmpty { canonicalAssistantBlocks(JsonArray(content)) }
    val key = message.presentationId ?: message.id
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (message.role == "event") {
            val expanded = disclosure.leaves[disclosureKey] == true
            val running = eventType == "tool/call" && toolResult == null && sessionRunning
            val preparing = running && message.raw.obj("data")?.get("preparing") == JsonPrimitive(true)
            val name = message.raw.obj("data")?.string("name").orEmpty()
            val title = when { eventType == "tool/call" && toolResult != null -> "工具已返回"; running -> "正在调用工具"; eventType == "tool/result" -> "工具结果"; eventType == "tool/call" -> "工具调用"; else -> "运行提示" }
            val resultMessage = (toolResult ?: message).raw.obj("data")?.obj("message")
            val failed = (resultMessage?.get("isError") as? JsonPrimitive)?.booleanOrNull == true || eventType in setOf("step/error", "turn/error")
            val color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            Column(Modifier.fillMaxWidth()) {
                HLDisclosureButton(onClick = { disclosure.leaves[disclosureKey] = !expanded }, contentColor = color,
                    contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { stateDescription = if (expanded) "已展开" else "已收起" }) {
                    if (running) CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.5.dp, color = color) else HLNavigationIcon(if (toolResult != null && !failed) "CHECK" else "TOOL", 16.dp)
                    Text(if (failed) "调用失败" else if (preparing) "正在准备 ${name.ifEmpty { "工具调用" }}" else if (running) "正在调用 ${name.ifEmpty { "工具" }}" else if (name.isEmpty()) title else name,
                        Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    HLNavigationIcon(if (expanded) "UP" else "DOWN", 14.dp)
                }
                if (expanded) Column(Modifier.padding(start = 22.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val arguments = message.raw.obj("data")?.get("arguments")
                    arguments?.let { HLCodeBlock(if (it is JsonPrimitive) it.content else Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), it), "参数") }
                    val resultText = resultMessage?.array("content")?.mapNotNull { (it as? JsonObject)?.string("text") }?.joinToString("\n")
                    if (!resultText.isNullOrBlank()) HLCodeBlock(resultText, "输出")
                    else if (arguments == null) HLCodeBlock(Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), message.raw.obj("data") ?: message.raw), "详情")
                }
            }
        } else if (user) {
            Box(Modifier.fillMaxWidth().padding(start = 28.dp), contentAlignment = Alignment.CenterEnd) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface, shape = RoundedCornerShape(20.dp), modifier = Modifier.widthIn(max = 520.dp)) {
                    SelectionContainer { Text(message.text, Modifier.padding(horizontal = 16.dp, vertical = 11.dp), style = MaterialTheme.typography.bodyLarge) }
                }
            }
        } else {
            if (blocks.isEmpty() && message.streaming) HLRunningState("思考中…")
            if (blocks.isEmpty() && message.text.isNotEmpty()) HLMarkdownText(message.text)
            blocks.forEach { block -> key(key, block.index) {
                when (block.kind) {
                    "reasoning" -> {
                        val leafKey = "$disclosureKey:${block.index}"
                        HLReasoningDisclosure(block.text, message.streaming && block.streaming, disclosure.leaves[leafKey] == true) {
                            disclosure.leaves[leafKey] = disclosure.leaves[leafKey] != true
                        }
                    }
                    "text" -> if (block.text.isNotEmpty()) HLMarkdownText(block.text)
                    "tool-call" -> if (message.streaming) {
                        HLRunningState("正在准备 ${block.name ?: "工具调用"}…")
                    }
                }
            } }
            if (message.streaming && message.phase == HLAssistantPhase.WRITING) HLRunningState("正在生成…")
            if (showActions) {
                val clipboard = LocalClipboardManager.current
                var copied by remember(message.id) { mutableStateOf(false) }
                IconButton(onClick = { clipboard.setText(AnnotatedString(message.text)); copied = true }, modifier = Modifier.size(48.dp).semantics { contentDescription = if (copied) "已复制回答" else "复制回答" }) {
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) { HLNavigationIcon(if (copied) "CHECK" else "COPY", 16.dp) }
                }
            }
            if (message.phase == HLAssistantPhase.INTERRUPTED) Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.extraSmall) {
                Text("已停止生成", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun HLRunningState(label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
        CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HLReasoningDisclosure(raw: String, running: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    val text = reasoningDisplayText(raw)
    if (text.isBlank() && !running) return
    val summary = reasoningSummary(raw, running)
    Column(Modifier.fillMaxWidth()) {
        HLDisclosureButton(onClick = onToggle, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "思考内容"; stateDescription = if (expanded) "已展开" else "已收起" }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) { HLNavigationIcon("THINK", 16.dp) }
            Text(if (running) "思考中" else "思考", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!expanded && summary.isNotBlank()) {
                Text("·", modifier = Modifier.padding(end = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(summary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else Spacer(Modifier.weight(1f))
            HLNavigationIcon(if (expanded) "UP" else "DOWN", 16.dp)
        }
        if (expanded) Box(Modifier.fillMaxWidth().padding(start = 22.dp, top = 4.dp, bottom = 12.dp)) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) { HLMarkdownText(text.ifEmpty { "正在思考…" }, compact = true) }
        }
    }
}
