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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.dsh.mobile.model.HLState
import io.dsh.mobile.platform.formatMessageTime
import io.dsh.mobile.sync.HLRepository
import io.dsh.mobile.design.HLDisclosureButton
import io.dsh.mobile.design.HLFloatingPanel
import io.dsh.mobile.sync.cacheHitPercent
import io.dsh.mobile.sync.exactTokens
import kotlinx.serialization.json.*
import kotlin.math.roundToLong

internal fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
internal fun durationText(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0) / 1000)
    return when { seconds >= 3600 -> "${seconds / 3600}小时${seconds / 60 % 60}分${seconds % 60}秒"; seconds >= 60 -> "${seconds / 60}分${seconds % 60}秒"; else -> "${seconds}秒" }
}
private fun compactCount(value: Long): String = when {
    value >= 1_000_000 -> "${(value / 100_000.0).roundToLong() / 10.0}M"
    value >= 1_000 -> "${(value / 100.0).roundToLong() / 10.0}K"
    else -> value.toString()
}

@Composable
internal fun HLTurnStatus(presentation: JsonObject, turn: Long? = null, expanded: Boolean? = null, onToggle: (() -> Unit)? = null) {
    val status = presentation.string("status")
    val title = when (status) { "failed" -> "处理失败"; "stopped" -> "已停止"; else -> "已完成" }
    val duration = presentation.number("durationMs")?.takeIf { status == "completed" }?.coerceAtLeast(1000)
    val label = title + (duration?.let { "，用时 ${durationText(it)}" } ?: "")
    val color = if (status == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val rotation by animateFloatAsState(if (expanded == true) 180f else 0f, tween(160))
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp)) {
        if (onToggle != null) HLDisclosureButton(onClick = onToggle, contentColor = color,
            contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                contentDescription = (turn?.let { "第 $it 轮执行过程，" } ?: "本轮执行过程，") + label
                stateDescription = if (expanded == true) "已展开" else "已收起"
            }) {
            Text(label, modifier = Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(4.dp))
            Box(Modifier.graphicsLayer { rotationZ = rotation }) { HLNavigationIcon("DOWN", 14.dp) }
        } else Text(label, style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.padding(vertical = 8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HLTurnActions(presentation: JsonObject, state: HLState, repository: HLRepository, busy: Boolean, run: HLRunAction) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(presentation.number("endSeq")) { mutableStateOf(false) }
    var usageOpen by remember(state.activeHostId, state.selectedSessionId, state.connectionGeneration, presentation.number("endSeq")) { mutableStateOf(false) }
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
        IconButton(enabled = text.isNotBlank(), onClick = { clipboard.setText(AnnotatedString(text)); copied = true }, modifier = Modifier.size(44.dp).semantics { contentDescription = if (copied) "已复制回答" else "复制回答" }) { HLNavigationIcon(if (copied) "CHECK" else "COPY", 17.dp) }
        if (messageId != null && "feedback" in state.capabilities) {
            IconToggleButton(checked = rating?.string("rating") == "positive", onCheckedChange = { rate("positive") }, enabled = canAct,
                modifier = Modifier.size(44.dp).semantics { contentDescription = "好的回答"; stateDescription = if (rating?.string("rating") == "positive") "已赞" else "未评价" }) { HLNavigationIcon("LIKE", 18.dp) }
            IconToggleButton(checked = rating?.string("rating") == "negative", onCheckedChange = { rate("negative") }, enabled = canAct,
                modifier = Modifier.size(44.dp).semantics { contentDescription = "有问题的回答"; stateDescription = if (rating?.string("rating") == "negative") "已踩" else "未评价" }) { HLNavigationIcon("DISLIKE", 18.dp) }
        }
        IconButton(enabled = canAct && presentation.flag("branchAvailable"), onClick = { run {
            val result = repository.call("sessions.fork", buildJsonObject { put("sessionId", sessionId!!); put("atSeq", presentation.number("endSeq") ?: return@run) }).jsonObject
            repository.refreshSessions(); result.string("sessionId")?.let { repository.selectSession(it) }
        } }, modifier = Modifier.size(44.dp).semantics { contentDescription = "在新对话中分支" }) { HLNavigationIcon("BRANCH", 18.dp) }
        if (usage != null) TextButton(onClick = { usageOpen = true }, contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.height(44.dp), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
            HLNavigationIcon("DATABASE", 16.dp); Spacer(Modifier.width(4.dp)); Text("用量 ${compactCount(usage.number("totalTokens") ?: 0)} tok", style = MaterialTheme.typography.labelMedium)
        }
        presentation.number("time")?.let { Row(Modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically) { Text(formatMessageTime(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
    }
    if (usageOpen && usage != null) HLFloatingPanel("本轮用量", usage.number("totalTokens")?.let { "${exactTokens(it)} tok" }, "DATABASE", 360.dp, { usageOpen = false }) {
            val total = usage.number("totalTokens"); val output = usage.number("outputTokens"); val cached = usage.number("cacheReadTokens")
            if (total != null && output != null && cached != null && total >= output && cached in 0..(total - output)) {
                cacheHitPercent(cached, total - output, 1)?.let { HLMetricRow("缓存命中", "$it%") }
            }
            listOf("uncachedInputTokens" to "未缓存输入", "cacheReadTokens" to "缓存读取", "cacheWriteTokens" to "缓存写入", "outputTokens" to "输出", "reasoningTokens" to "其中思考").forEach { (key, label) ->
                usage.number(key)?.let { HLMetricRow(label, "${exactTokens(it)} tok") }
            }
            usage.array("routes").mapNotNull { it as? JsonObject }.forEach { Text("${it.string("provider")} / ${it.string("model")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
