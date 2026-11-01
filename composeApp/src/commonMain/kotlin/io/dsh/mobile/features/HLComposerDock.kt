package io.dsh.mobile.features

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.dsh.mobile.design.HLDisclosureButton
import io.dsh.mobile.design.HLFloatingPanel
import io.dsh.mobile.design.LocalHLCompactPanel
import io.dsh.mobile.design.HLTelemetryPalette
import io.dsh.mobile.design.telemetryPalette
import io.dsh.mobile.model.*
import io.dsh.mobile.sync.*
import kotlinx.serialization.json.*

/** A single compact row; full labels and measurements live in its anchored panels. */
@Composable
internal fun HLComposerDock(state: HLState, repository: HLRepository, busy: Boolean, run: HLRunAction) {
    if (!state.connected || state.selectedSessionId == null) return
    key(state.activeHostId, state.selectedSessionId, state.connectionGeneration) {
        val telemetry = remember(state.projections) { composerTelemetry(state.projections) }
        val current = (state.projections?.get("permissions") as? JsonObject)?.string("currentValue")
        val focus = LocalFocusManager.current; val keyboard = LocalSoftwareKeyboardController.current
        fun dismissInput() { focus.clearFocus(); keyboard?.hide() }
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            val panelWidth = minOf(maxWidth, 400.dp)
            val largeText = LocalDensity.current.fontScale > 1.3f
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (current != null) Box(Modifier.weight(1.2f)) { HLPermissionControl(current, state, repository, busy, run, panelWidth, largeText, ::dismissInput) }
                telemetry.statistics?.let { statistics -> Box(Modifier.weight(1.05f)) { HLStatisticsControl(statistics, panelWidth, largeText, ::dismissInput) } }
                telemetry.usage?.let { usage -> Box(Modifier.weight(1.1f)) { HLUsageControl(usage, panelWidth, largeText, ::dismissInput) } }
                telemetry.context?.let { context -> Box(Modifier.weight(.75f), contentAlignment = Alignment.CenterEnd) { HLContextControl(context, panelWidth, largeText, ::dismissInput) } }
            }
        }
    }
}

@Composable
private fun HLReadingButton(label: String, accessible: String, icon: String, showIcon: Boolean = true, click: () -> Unit) {
    HLDisclosureButton(click, Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp), accessibilityLabel = accessible) {
        if (showIcon) { HLNavigationIcon(icon, 16.dp); Spacer(Modifier.width(4.dp)) }
        Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HLMetricRow(label: String, value: String) {
    val style = if (LocalHLCompactPanel.current) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium
    FlowRow(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = style)
    }
}

@Composable
private fun HLStatisticsControl(stats: HLSessionStatistics, width: Dp, largeText: Boolean, dismissInput: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val counts = "${stats.turns}轮 ${stats.steps}步"
    val speed = tokensPerSecond(stats.decodeTokens, stats.decodeMs)
    val label = if (largeText) "${stats.turns}轮" else "${stats.turns}轮${stats.steps}步"
    HLReadingButton(label, "会话统计，$counts${speed?.let { "，输出速度 $it" }.orEmpty()}", "GAUGE", showIcon = !largeText) { dismissInput(); open = !open }
    if (open) HLFloatingPanel("会话统计", counts, "GAUGE", width, { open = false }) {
        if (stats.modelMs > 0) HLMetricRow("模型用时", statisticDuration(stats.modelMs))
        if (stats.toolMs > 0) HLMetricRow("工具调用用时", statisticDuration(stats.toolMs))
        if (stats.ttftSteps > 0) HLMetricRow("首 token 平均（TTFT）", statisticDuration(stats.ttftMs / stats.ttftSteps))
        if (speed != null) HLMetricRow("输出速度（TPS）", speed)
        if (stats.modelMs <= 0 && stats.toolMs <= 0 && stats.ttftSteps == 0L && speed == null) Text("桌面尚未提供计时数据", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HLUsageControl(usage: HLTokenUsage, width: Dp, largeText: Boolean, dismissInput: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val hit = cacheHitPercent(usage.cacheRead, usage.billedInput)
    val total = "${compactTokens(usage.total)} tok"
    HLReadingButton(compactTokens(usage.total), "Token 用量，$total${hit?.let { "，缓存命中 $it%" }.orEmpty()}", "DATABASE", showIcon = !largeText) { dismissInput(); open = !open }
    if (open) HLFloatingPanel("Token 用量", "${exactTokens(usage.total)} tok", "DATABASE", width, { open = false }) {
        if (hit != null) HLMetricRow("缓存命中", "$hit%")
        HLMetricRow("未缓存输入", "${exactTokens(usage.uncachedInput)} tok")
        HLMetricRow("缓存读取", "${exactTokens(usage.cacheRead)} tok")
        if (usage.cacheWrite != 0L) HLMetricRow("缓存写入", "${exactTokens(usage.cacheWrite)} tok")
        HLMetricRow("输出", "${exactTokens(usage.output)} tok")
    }
}

@Composable
private fun HLContextControl(context: HLContextUsage, width: Dp, largeText: Boolean, dismissInput: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val palette = telemetryPalette()
    Box(Modifier.fillMaxWidth()) {
        HLDisclosureButton({ dismissInput(); open = !open }, Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp), accessibilityLabel = "上下文已用 ${context.percent}%") {
            val track = MaterialTheme.colorScheme.outlineVariant; val color = MaterialTheme.colorScheme.onSurfaceVariant
            if (!largeText) Canvas(Modifier.size(16.dp)) {
                val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(track, style = stroke)
                if (context.percent > 0) drawArc(color, -90f, 360f * context.percent / 100, false, style = stroke)
            }
            if (!largeText) Spacer(Modifier.width(4.dp))
            Text("${context.percent}%", maxLines = 1, overflow = TextOverflow.Ellipsis, style = if (largeText) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium)
        }
        if (open) HLFloatingPanel("上下文已用 ${context.percent}%", "~${compactTokens(context.used)} / ${compactTokens(context.capacity)}", width = width, close = { open = false }) {
            HLContextBar(context, palette)
            context.breakdown?.let {
                HLContextRow("系统提示词", it.system, palette.system)
                HLContextRow("工具定义", it.tools, palette.tools)
                HLContextRow("对话消息", it.messages, palette.messages)
            }
            Text("来自桌面的上下文参考值；分项用于展示构成，不是精确合计。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HLContextBar(context: HLContextUsage, palette: HLTelemetryPalette) {
    val parts = context.breakdown?.let { listOf(it.system to palette.system, it.tools to palette.tools, it.messages to palette.messages) }.orEmpty()
    val total = parts.sumOf { it.first }
    Row(Modifier.fillMaxWidth().height(6.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp))) {
        if (context.percent > 0) Row(Modifier.fillMaxWidth(context.percent / 100f).fillMaxHeight()) {
            if (total > 0) parts.filter { it.first > 0 }.forEach { (value, color) -> Box(Modifier.weight((value / total).toFloat()).fillMaxHeight().background(color)) }
            else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary))
        }
    }
}

@Composable
private fun HLContextRow(label: String, count: Double, color: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, RoundedCornerShape(2.dp))); Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) { HLMetricRow(label, "~${compactTokens(count)}") }
    }
}

@Composable
private fun HLPermissionControl(current: String, state: HLState, repository: HLRepository, busy: Boolean, run: HLRunAction, width: Dp, largeText: Boolean, dismissInput: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<HLPermissionOption?>(null) }
    var acknowledged by remember { mutableStateOf(false) }
    val catalog = state.permissionCatalog
    val catalogError = state.permissionCatalogError
    val option = catalog?.options?.firstOrNull { it.value == current }
    val label = permissionLabel(current, option?.name ?: current)
    val shortLabel = when (current) { "read-only" -> "只读"; "workspace-write" -> "工作区"; "danger-full-access" -> "全权"; "auto" -> "审查"; "custom" -> "自定"; else -> label }
    LaunchedEffect(catalog, current, busy) {
        if (confirmation?.let { choice -> catalog?.options?.none { it.value == choice.value } != false || choice.value == current } == true) { confirmation = null; acknowledged = false }
        if (busy) open = false
    }
    fun submit(choice: HLPermissionOption, confirmed: Boolean) {
        val host = state.activeHostId ?: return; val session = state.selectedSessionId ?: return
        open = false; confirmation = null; acknowledged = false
        run { repository.selectPermission(host, session, state.connectionGeneration, choice.value, confirmed) }
    }
    Box {
        HLDisclosureButton({ dismissInput(); open = !open }, Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp), enabled = !busy, accessibilityLabel = "访问模式，当前：$label",
            contentColor = if (current == "danger-full-access") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) {
            if (!largeText) { HLNavigationIcon("SHIELD", 16.dp); Spacer(Modifier.width(4.dp)) }
            Text(shortLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(2.dp)); HLNavigationIcon("DOWN", 9.dp)
        }
        if (open) HLFloatingPanel("当前会话权限", icon = "SHIELD", width = width, close = { open = false }) {
            when {
                "permissions" !in state.capabilities -> Text("更新桌面插件后可在手机切换权限", style = MaterialTheme.typography.bodyMedium)
                catalogError != null -> { Text(catalogError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall); TextButton(onClick = { run { repository.refreshPermissionCatalog() } }) { Text("重新读取权限选项") } }
                catalog == null -> { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Text("正在读取桌面权限选项…", style = MaterialTheme.typography.bodySmall) }
                catalog.options.isEmpty() -> Text("桌面没有提供可切换的权限模式", style = MaterialTheme.typography.bodyMedium)
                else -> catalog.options.forEach { choice ->
                    HLDisclosureButton({ if (choice.value == current) open = false else if (choice.value in setOf("danger-full-access", "auto")) { open = false; acknowledged = false; confirmation = choice } else submit(choice, false) }, Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 8.dp), enabled = !busy,
                        accessibilityLabel = "选择${permissionLabel(choice.value, choice.name)}${choice.description?.let { "。$it" }.orEmpty()}",
                        accessibilityState = if (choice.value == current) "当前模式" else "未选择") {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(permissionLabel(choice.value, choice.name), style = MaterialTheme.typography.bodyMedium)
                                if (choice.value == "auto") { Spacer(Modifier.width(8.dp)); Text("EXP", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall) }
                            }
                            choice.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        if (choice.value == current) { Spacer(Modifier.width(8.dp)); HLNavigationIcon("CHECK", 16.dp) }
                    }
                }
            }
        }
    }
    confirmation?.let { choice ->
        val auto = choice.value == "auto"
        AlertDialog(onDismissRequest = { confirmation = null; acknowledged = false }, title = { Text(if (auto) "确认启用自动审查（实验）？" else "确认启用完全权限？") }, text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (auto) "自动审查不使用沙箱。每次原生工具调用和 PTC 内层调用前，由与当前 Agent 相同的模型进行审查；审查拒绝的调用由你批准或拒绝。此功能仍属实验性，可能误放行或误拒绝，并会消耗额外 token。"
                    else "启用完全权限后，智能体将减少确认步骤，并且可以直接执行更多操作，包括敏感操作、文件修改或外部命令。仅建议在你信任当前任务时使用。", style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(acknowledged, role = Role.Checkbox, onValueChange = { acknowledged = it }), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(acknowledged, onCheckedChange = null); Spacer(Modifier.width(8.dp)); Text("我已了解风险，并愿意继续", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }, confirmButton = { TextButton(enabled = acknowledged && !busy, onClick = { submit(choice, true) }) { Text(if (auto) "启用自动审查" else "启用完全权限") } }, dismissButton = { TextButton(onClick = { confirmation = null; acknowledged = false }) { Text("取消") } })
    }
}
