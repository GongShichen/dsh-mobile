package io.dsh.mobile.features

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.dsh.mobile.design.KeyboardDismissalHost
import io.dsh.mobile.design.keyboardInputArea
import io.dsh.mobile.model.MessageRecord
import io.dsh.mobile.model.MobileState
import io.dsh.mobile.platform.formatMessageTime
import io.dsh.mobile.sync.MobileRepository
import kotlinx.serialization.json.*

private fun traceKind(message: MessageRecord): String = when (message.raw.string("type")) {
    "system/message", "developer/message" -> "系统"
    "user/message" -> if (message.role == "user") "用户" else "上下文"
    "assistant/message" -> "助手"
    "tool/call", "tool/result" -> "工具"
    else -> if (message.streaming) "助手" else "事件"
}
private fun traceBody(message: MessageRecord): String {
    if (message.streaming) return message.blocks.filter { it.kind in setOf("text", "reasoning") }.joinToString("\n\n") { it.text }
    val data = message.raw.obj("data") ?: return message.text
    if (message.text.isNotBlank()) return message.text
    val content = data.obj("message")?.get("content") ?: data["content"]
    val text = when (content) {
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.mapNotNull { (it as? JsonObject)?.string("text") }.joinToString("\n\n")
        else -> ""
    }
    return text.ifBlank { Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), data) }
}

/** An independent view of the same desktop event stream; no separate trajectory database. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrajectoryDialog(state: MobileState, repository: MobileRepository, busy: Boolean, run: RunAction, close: () -> Unit) {
    var search by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("全部") }
    val stats = state.projections?.obj("sessionStats")
    val rows = remember(state.messages, search, category) {
        state.messages.filter { message ->
            val kind = traceKind(message)
            (if (category == "全部") kind != "事件" else category == kind) &&
                (search.isBlank() || traceBody(message).contains(search, ignoreCase = true) || message.raw.string("type").orEmpty().contains(search, ignoreCase = true))
        }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.94f).padding(horizontal = 8.dp), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.background) {
            KeyboardDismissalHost {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("对话轨迹", style = MaterialTheme.typography.titleLarge); Text(state.selectedSession?.title.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        IconButton(onClick = close, modifier = Modifier.semantics { contentDescription = "关闭轨迹" }) { NavigationIcon("CLOSE", 20.dp) }
                    }
                    if (stats != null) FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        stats.number("turns")?.let { Text("$it 轮", style = MaterialTheme.typography.labelMedium) }
                        stats.number("steps")?.let { Text("$it 步", style = MaterialTheme.typography.labelMedium) }
                        stats.number("llmMs")?.let { Text("模型 ${durationText(it)}", style = MaterialTheme.typography.labelMedium) }
                        stats.number("toolMs")?.let { Text("工具 ${durationText(it)}", style = MaterialTheme.typography.labelMedium) }
                    }
                    TextField(search, { search = it }, placeholder = { Text("搜索轨迹", style = MaterialTheme.typography.bodyMedium) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).keyboardInputArea(), leadingIcon = { NavigationIcon("SEARCH", 18.dp) },
                        shape = RoundedCornerShape(28.dp), colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
                    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("全部", "系统", "用户", "上下文", "助手", "工具", "事件").forEach { kind -> FilterChip(selected = category == kind, onClick = { category = kind }, label = { Text(kind, style = MaterialTheme.typography.labelSmall) }) }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                        if (state.hasMoreHistory) item { TextButton(enabled = state.connected && !busy, onClick = { run { repository.loadHistory() } }, modifier = Modifier.fillMaxWidth()) { Text("加载更早的轨迹") } }
                        if (rows.isEmpty()) item { Text(if (search.isBlank()) "此分类暂无记录" else "没有匹配的记录", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(rows, key = { it.presentationId ?: it.id }) { message ->
                            var expanded by remember(message.id) { mutableStateOf(false) }
                            val body = remember(message.raw, message.text, message.blocks) { traceBody(message) }
                            val kind = traceKind(message)
                            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.surfaceVariant) { Text(kind, Modifier.padding(horizontal = 8.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall) }
                                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                        val name = message.raw.obj("data")?.string("name") ?: if (message.streaming) "正在生成…" else message.raw.string("type").orEmpty()
                                        Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (!expanded) Text(body, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                    message.raw.number("time")?.let { Text(formatMessageTime(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    Spacer(Modifier.width(6.dp)); NavigationIcon(if (expanded) "UP" else "DOWN", 14.dp)
                                }
                                if (expanded) {
                                    if (kind in setOf("事件", "工具")) CodeBlock(body, "记录详情") else MarkdownText(body, compact = true)
                                    message.raw.obj("data")?.number("turn")?.let { Text("第 $it 轮 · 事件 ${message.sequence ?: "生成中"}", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
                            }
                        }
                    }
                }
            }
        }
    }
}
