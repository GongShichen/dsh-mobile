package io.dsh.mobile.features

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.dsh.mobile.model.MobileState
import io.dsh.mobile.platform.formatMessageTime
import io.dsh.mobile.sync.MobileRepository
import kotlinx.serialization.json.*
import kotlin.math.roundToLong

internal fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
internal fun durationText(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0) / 1000)
    return when { seconds >= 3600 -> "${seconds / 3600} 小时 ${seconds / 60 % 60} 分 ${seconds % 60} 秒"; seconds >= 60 -> "${seconds / 60} 分 ${seconds % 60} 秒"; else -> "$seconds 秒" }
}
private fun compactCount(value: Long): String = when {
    value >= 1_000_000 -> "${(value / 100_000.0).roundToLong() / 10.0}M"
    value >= 1_000 -> "${(value / 100.0).roundToLong() / 10.0}K"
    else -> value.toString()
}

@Composable
internal fun TurnStatus(presentation: JsonObject) {
    val status = presentation.string("status")
    val title = when (status) { "failed" -> "运行失败"; "stopped" -> "已停止"; else -> "已完成" }
    val duration = presentation.number("durationMs")?.takeIf { status == "completed" }?.coerceAtLeast(1000)
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp)) {
        Text(title + (duration?.let { "，用时 ${durationText(it)}" } ?: ""), style = MaterialTheme.typography.bodyMedium,
            color = if (status == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TurnActions(presentation: JsonObject, state: MobileState, repository: MobileRepository, busy: Boolean, run: RunAction) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(presentation.number("endSeq")) { mutableStateOf(false) }
    var usageOpen by remember { mutableStateOf(false) }
    val text = presentation.string("text").orEmpty()
    val messageId = presentation.string("messageId")
    val rating = messageId?.let { state.feedback[it] }
    val usage = presentation.obj("tokenUsage")
    val sessionId = state.selectedSessionId
    val canAct = state.connected && !busy && sessionId != null
    val rate: (String) -> Unit = { desired ->
        if (messageId != null && sessionId != null) run {
            val same = rating?.string("rating") == desired
            repository.call(if (same) "feedback.delete" else "feedback.put", buildJsonObject {
                put("sessionId", sessionId); put("messageId", messageId)
                put("ifVersion", rating?.get("version") ?: JsonNull)
                if (!same) {
                    put("rating", desired)
                    rating?.get("note")?.let { put("note", it) }
                    rating?.get("category")?.let { put("category", it) }
                }
            })
        }
    }
    FlowRow(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        IconButton(enabled = text.isNotBlank(), onClick = { clipboard.setText(AnnotatedString(text)); copied = true }, modifier = Modifier.size(44.dp).semantics { contentDescription = if (copied) "已复制回答" else "复制回答" }) { NavigationIcon(if (copied) "CHECK" else "COPY", 17.dp) }
        if (messageId != null && "feedback" in state.capabilities) {
            IconToggleButton(checked = rating?.string("rating") == "positive", onCheckedChange = { rate("positive") }, enabled = canAct,
                modifier = Modifier.size(44.dp).semantics { contentDescription = "好的回答"; stateDescription = if (rating?.string("rating") == "positive") "已赞" else "未评价" }) { NavigationIcon("LIKE", 18.dp) }
            IconToggleButton(checked = rating?.string("rating") == "negative", onCheckedChange = { rate("negative") }, enabled = canAct,
                modifier = Modifier.size(44.dp).semantics { contentDescription = "有问题的回答"; stateDescription = if (rating?.string("rating") == "negative") "已踩" else "未评价" }) { NavigationIcon("DISLIKE", 18.dp) }
        }
        IconButton(enabled = canAct && presentation.flag("branchAvailable"), onClick = { run {
            val result = repository.call("sessions.fork", buildJsonObject { put("sessionId", sessionId!!); put("atSeq", presentation.number("endSeq") ?: return@run) }).jsonObject
            repository.refreshSessions(); result.string("sessionId")?.let { repository.selectSession(it) }
        } }, modifier = Modifier.size(44.dp).semantics { contentDescription = "在新对话中分支" }) { NavigationIcon("BRANCH", 18.dp) }
        if (usage != null) TextButton(onClick = { usageOpen = true }, contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.height(44.dp), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
            NavigationIcon("DATABASE", 16.dp); Spacer(Modifier.width(4.dp)); Text("用量 ${compactCount(usage.number("totalTokens") ?: 0)} tok", style = MaterialTheme.typography.labelMedium)
        }
        presentation.number("time")?.let { Row(Modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically) { Text(formatMessageTime(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
    }
    if (usageOpen && usage != null) AlertDialog(onDismissRequest = { usageOpen = false }, title = { Text("本轮用量") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("totalTokens" to "总 Token", "uncachedInputTokens" to "未缓存输入", "cacheReadTokens" to "缓存读取", "cacheWriteTokens" to "缓存写入", "outputTokens" to "输出", "reasoningTokens" to "其中思考").forEach { (key, label) ->
                usage.number(key)?.let { Row(Modifier.fillMaxWidth()) { Text(label, Modifier.weight(1f)); Text(it.toString()) } }
            }
            usage.array("routes").mapNotNull { it as? JsonObject }.forEach { Text("${it.string("provider")} / ${it.string("model")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }, confirmButton = { TextButton(onClick = { usageOpen = false }) { Text("关闭") } })
}
