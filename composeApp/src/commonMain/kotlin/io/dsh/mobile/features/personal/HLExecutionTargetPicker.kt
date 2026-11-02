package io.dsh.mobile.features.personal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.dsh.mobile.features.HLNavigationIcon
import io.dsh.mobile.model.*
import io.dsh.mobile.protocol.WireJson
import io.dsh.mobile.sync.HLRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Reads metadata only. A real session is created after the user submits a task. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HLExecutionTargetPicker(repository: HLRepository, initial: HLExecutionTarget?, selected: (HLExecutionTarget?) -> Unit, close: () -> Unit) {
    var kind by remember { mutableStateOf(initial?.kind ?: "internal") }
    var workspace by remember { mutableStateOf(initial?.workspaceId) }
    var session by remember { mutableStateOf(initial?.sessionId) }
    var workspaces by remember { mutableStateOf<List<HLPersonalWorkspace>>(emptyList()) }
    var sessions by remember { mutableStateOf<List<HLPersonalSession>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(repository.state.value.activeHostId) {
        try {
            val result = repository.personal.read("listWorkspaces").jsonObject["items"] ?: JsonArray(emptyList())
            workspaces = WireJson.decodeFromJsonElement<List<HLPersonalWorkspace>>(result)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (reason: Throwable) { error = reason.message }
        finally { loading = false }
    }
    LaunchedEffect(kind, workspace) {
        sessions = emptyList()
        if (kind != "session-continue" || workspace == null) return@LaunchedEffect
        loading = true
        try {
            val result = repository.personal.read("listSessions", buildJsonObject { put("workspaceId", workspace) }).jsonObject["items"] ?: JsonArray(emptyList())
            sessions = WireJson.decodeFromJsonElement<List<HLPersonalSession>>(result)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (reason: Throwable) { error = reason.message }
        finally { loading = false }
    }
    HLPersonalOverlay(title = "执行位置", close = close, maxWidth = 640.dp, avoidKeyboard = true) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text("执行位置", style = MaterialTheme.typography.titleLarge)
            Text("直接使用电脑上的工作区和真实会话。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("internal" to "内部会话", "workspace-new" to "新建", "session-continue" to "继续").forEach { (id, label) ->
                    FilterChip(selected = kind == id, onClick = { kind = id }, label = { Text(label) })
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (kind == "internal") item { Text("由助理在独立任务目录中处理，不改变已有工作区。", style = MaterialTheme.typography.bodyMedium) }
                if (kind != "internal") items(workspaces, key = { it.workspaceId }) { row ->
                    Surface(Modifier.fillMaxWidth().clickable { workspace = row.workspaceId; session = null }, shape = MaterialTheme.shapes.medium,
                        color = if (workspace == row.workspaceId) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            HLNavigationIcon("WORKSPACES", 19.dp); Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) { Text(row.title, style = MaterialTheme.typography.bodyMedium); Text(row.path, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
                if (kind == "session-continue" && workspace != null) {
                    item { Text("已有会话", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp)) }
                    items(sessions, key = { it.sessionId }) { row ->
                        Surface(Modifier.fillMaxWidth().clickable(enabled = !row.archived && row.formatStatus != "migration-required") { session = row.sessionId },
                            shape = MaterialTheme.shapes.medium, color = if (session == row.sessionId) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column(Modifier.padding(14.dp)) { Text(row.title, style = MaterialTheme.typography.bodyMedium); Text(when { row.archived -> "已归档，请先恢复"; row.running -> "原会话执行中，将排队等待"; row.formatStatus == "migration-required" -> "需要先在桌面完成迁移"; else -> "保留原模型、预设和历史" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
            Button(enabled = kind == "internal" || workspace != null && (kind != "session-continue" || session != null),
                onClick = { selected(if (kind == "internal") null else HLExecutionTarget(kind, workspace, if (kind == "session-continue") session else null)); close() },
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).heightIn(min = 44.dp)) { Text("选择此位置") }
        }
    }
}
