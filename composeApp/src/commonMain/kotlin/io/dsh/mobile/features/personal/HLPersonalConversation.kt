package io.dsh.mobile.features.personal

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.dsh.mobile.features.*
import io.dsh.mobile.model.HLState
import io.dsh.mobile.sync.HLRepository
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
fun HLPersonalConversation(state: HLState, repository: HLRepository, busy: Boolean, run: HLRunAction,
    connect: () -> Unit, chooseTarget: () -> Unit, showTasks: () -> Unit) {
    val snapshot = state.personal
    val cachedConversation = state.activeHostId != null && snapshot?.profile?.mainSessionReady == true && state.selectedSessionId == snapshot.profile.mainSessionId
    when {
        !state.connected && !cachedConversation -> HLEmptyPanel("随时交办，持续跟进", "连接电脑后，助理可以管理多个目标、处理事务并继续已有工作区。") { TextButton(onClick = connect) { Text("连接电脑") } }
        state.connected && "personal-agent-v1" !in state.capabilities -> HLEmptyPanel("桌面需要更新", "请更新电脑端 Harness Link 插件，启用私人助理能力。")
        snapshot == null -> HLEmptyPanel("正在同步助理", state.personalError ?: "读取电脑上的目标、事务和个人上下文。")
        !snapshot.available -> HLEmptyPanel("助理暂未就绪", snapshot.unavailableReason ?: "请检查桌面插件状态。")
        !snapshot.profile.enabled -> HLEmptyPanel("你的长期私人助理", "记住你明确表达的偏好，同时推进多个目标，也能继续已有 DSH 会话。") {
            Button(enabled = !busy, onClick = { run { repository.enablePersonal() } }) { Text("启用私人助理") }
        }
        !snapshot.profile.mainSessionReady || state.selectedSessionId != snapshot.profile.mainSessionId -> HLEmptyPanel("正在准备主对话", "主对话准备好后，即可继续交流。") {
            TextButton(enabled = !busy, onClick = { run { repository.openPersonalConversation() } }) { Text("重新读取") }
        }
        else -> Column(Modifier.fillMaxSize()) {
            if (!state.connected) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("离线 · 可查看上次同步并编辑草稿", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(enabled = !busy, onClick = { run { state.activeHostId?.let { repository.connect(it) } } }) { Text("重新连接") }
            }
            snapshot.deliveryError?.let { failure -> Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(failure.message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(enabled = !busy && state.connected, onClick = { run { repository.personal.command("retryInput", buildJsonObject { put("inputId", failure.inputId) }) } }) { Text("重试") }
            } }
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = state.connected, onClick = chooseTarget, modifier = Modifier.weight(1f)) {
                    HLNavigationIcon("WORKSPACES", 16.dp); Spacer(Modifier.width(7.dp))
                    val target = state.personalTarget
                    Text(when (target?.kind) { "workspace-new" -> "已选择工作区 · 新建会话"; "session-continue" -> "已选择会话 · 继续执行"; else -> "工作区与会话" },
                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                }
                if (state.personalTarget != null) IconButton(onClick = { repository.selectPersonalTarget(null) }) { HLNavigationIcon("CLOSE", 16.dp) }
                TextButton(onClick = showTasks) { Text("${snapshot.counts.tasks} 项事务", style = MaterialTheme.typography.labelMedium) }
            }
            Box(Modifier.weight(1f)) { HLChatScreen(state, repository, busy, run, connect) }
        }
    }
}
