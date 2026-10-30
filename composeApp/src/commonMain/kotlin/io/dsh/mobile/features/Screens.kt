package io.dsh.mobile.features

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.dsh.mobile.AppGraph
import io.dsh.mobile.model.*
import io.dsh.mobile.sync.MobileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

typealias RunAction = (suspend () -> Unit) -> Unit
internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.array(key: String): List<JsonElement> = (this[key] as? JsonArray)?.toList().orEmpty()
internal fun parameters(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject { pairs.forEach { put(it.first, it.second) } }

@Composable
fun EmptyPanel(title: String, description: String, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineSmall); Spacer(Modifier.height(12.dp))
        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(20.dp)); action?.invoke()
    }
}
@Composable
internal fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); content() } }
}

@Composable
fun DevicesScreen(state: MobileState, repository: MobileRepository, busy: Boolean, run: RunAction) {
    val focus = LocalFocusManager.current
    var invitation by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("我的手机") }
    var forget by remember { mutableStateOf<DesktopHost?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { SectionCard("连接一台电脑") {
            Text("在电脑的 DeepSeek Harness 中打开“移动访问”，生成配对二维码。")
            OutlinedTextField(name, { name = it }, label = { Text("手机名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(enabled = !busy && AppGraph.scanner != null, onClick = { run { AppGraph.scanner?.scan()?.let { repository.pair(it, name) } } }, modifier = Modifier.fillMaxWidth()) { Text("扫描配对二维码") }
            OutlinedTextField(invitation, { invitation = it }, label = { Text("或粘贴配对链接") }, maxLines = 3, modifier = Modifier.fillMaxWidth())
            OutlinedButton(enabled = invitation.isNotBlank() && !busy, onClick = { val value = invitation; invitation = ""; focus.clearFocus(); run { repository.pair(value, name) } }, modifier = Modifier.fillMaxWidth()) { Text("连接并等待电脑确认") }
            if (state.connection == ConnectionStatus.AWAITING_APPROVAL) Text("配对请求已发送，请在电脑上核对手机名称并允许。", color = MaterialTheme.colorScheme.primary)
            if (state.connection == ConnectionStatus.PAIRING || state.connection == ConnectionStatus.AWAITING_APPROVAL) TextButton(onClick = { run { repository.disconnect() } }) { Text("取消配对") }
        } }
        item { Text("我的电脑", style = MaterialTheme.typography.titleLarge) }
        if (state.hosts.isEmpty()) item { Text("还没有配对的电脑", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(state.hosts, key = { it.id }) { host -> SectionCard(host.name) {
            SelectionContainer { Text(host.endpointId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(if (state.activeHostId == host.id && state.connected) "已连接 · ${state.path.name.lowercase()}" else "已配对")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = { run { repository.connect(host.id) } }) { Text(if (state.activeHostId == host.id && state.connected) "重新连接" else "连接") }
                TextButton(onClick = { forget = host }) { Text("移除") }
            }
        } }
    }
    forget?.let { host -> AlertDialog(onDismissRequest = { forget = null }, title = { Text("移除 ${host.name}？") }, text = { Text("清除本机保存的电脑入口。电脑端的授权可在“移动访问”中撤销。") }, confirmButton = { TextButton(onClick = { forget = null; run { repository.forgetHost(host.id) } }) { Text("移除") } }, dismissButton = { TextButton(onClick = { forget = null }) { Text("取消") } }) }
}

@Composable
fun SessionsScreen(state: MobileState, repository: MobileRepository, busy: Boolean, run: RunAction, onAddDevice: () -> Unit, onOpen: () -> Unit) {
    var query by remember { mutableStateOf("") }; var archived by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<SessionSummary?>(null) }; var title by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(query, state.activeHostId, state.connected) {
        matches = emptyMap(); searchError = null
        if (query.isBlank() || !state.connected) { searching = false; return@LaunchedEffect }
        searching = true
        try {
            delay(300)
            val result = repository.call("sessions.search", parameters("query" to query)).jsonObject
            matches = result.array("items").mapNotNull { value -> (value as? JsonObject)?.let { item -> item.string("sessionId")?.let { it to item.string("snippet").orEmpty() } } }.toMap()
        } catch (error: Throwable) { if (error is CancellationException) throw error; searchError = "全文搜索暂不可用，正在按标题筛选。" }
        finally { searching = false }
    }
    if (state.hosts.isEmpty()) { EmptyPanel("你的工作，随时继续", "连接电脑后，查看对话、确认操作和浏览文件。") { Button(onClick = onAddDevice) { Text("添加电脑") } }; return }
    val sessions = state.sessions.filter { it.archived == archived && (query.isBlank() || it.title.contains(query, ignoreCase = true) || matches.containsKey(it.id)) }.sortedWith(compareByDescending<SessionSummary> { it.pinned }.thenByDescending { it.updatedAt })
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query, { query = it }, label = { Text("搜索对话") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp))
        if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
        searchError?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp)) }
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !archived, onClick = { archived = false }, label = { Text("最近") })
            FilterChip(selected = archived, onClick = { archived = true }, label = { Text("已归档") })
            Spacer(Modifier.weight(1f)); TextButton(enabled = state.connected && !busy, onClick = { run { repository.refreshSessions() } }) { Text("刷新") }
        }
        if (sessions.isEmpty()) EmptyPanel(if (query.isNotBlank()) "没有匹配的对话" else "还没有对话", if (state.connected) "点右上角“新对话”开始。" else "在设备页连接电脑后继续。")
        else LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            items(sessions, key = { it.id }) { session ->
                var menu by remember { mutableStateOf(false) }
                ListItem(headlineContent = { Text(session.title.ifBlank { "未命名对话" }, maxLines = 1, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text(matches[session.id] ?: if (session.running) "正在处理…" else if (session.pinned) "已置顶" else "点击继续对话", maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (session.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingContent = { Box { TextButton(onClick = { menu = true }) { Text("···") }; DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("重命名") }, enabled = state.connected, onClick = { menu = false; title = session.title; rename = session })
                        DropdownMenuItem(text = { Text(if (session.pinned) "取消置顶" else "置顶") }, enabled = state.connected, onClick = { menu = false; run { repository.call(if (session.pinned) "sessions.unpin" else "sessions.pin", parameters("sessionId" to session.id)); repository.refreshSessions() } })
                        DropdownMenuItem(text = { Text("从这里创建分支") }, enabled = state.connected, onClick = { menu = false; run { val response = repository.call("sessions.fork", parameters("sessionId" to session.id)).jsonObject; repository.refreshSessions(); response.string("sessionId")?.let { repository.selectSession(it); onOpen() } } })
                        DropdownMenuItem(text = { Text(if (session.archived) "恢复对话" else "归档") }, enabled = state.connected, onClick = { menu = false; run { if (session.archived) repository.call("sessions.unarchive", parameters("sessionId" to session.id)) else repository.archiveSession(session.id); repository.refreshSessions() } })
                    } } }, modifier = Modifier.clickable { run { repository.selectSession(session.id); onOpen() } })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
            }
        }
    }
    rename?.let { session -> AlertDialog(onDismissRequest = { rename = null }, title = { Text("重命名对话") }, text = { OutlinedTextField(title, { title = it }, singleLine = true) }, confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = { rename = null; run { repository.renameSession(session.id, title) } }) { Text("保存") } }, dismissButton = { TextButton(onClick = { rename = null }) { Text("取消") } }) }
}

@Composable
fun SettingsScreen(state: MobileState, repository: MobileRepository, busy: Boolean, run: RunAction) {
    var permission by remember { mutableStateOf<String?>(null) }; var reset by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { SectionCard("消息通知") {
            Text("接收任务完成和待确认提醒。推送配置在电脑的“移动访问”中导入。")
            Button(enabled = !busy && state.connected, onClick = { run { repository.configurePush(); val allowed = AppGraph.notificationPermissions?.request() ?: repository.requestPushPermission(); permission = if (allowed) "已允许通知" else "通知权限未开启，可在系统设置中调整。" } }) { Text("开启通知") }
            permission?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            if (AppGraph.resetPushConfiguration != null) TextButton(onClick = { reset = true }) { Text("重置本机通知配置") }
        } }
        item { SectionCard("当前连接") {
            Text(state.activeHost?.name ?: "尚未连接电脑"); state.activeHost?.dshVersion?.let { Text("DeepSeek Harness $it", style = MaterialTheme.typography.bodySmall) }
            Text("会话和文件保留在电脑，手机缓存按电脑隔离。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(enabled = !busy && state.activeHostId != null, onClick = { run { repository.disconnect() } }) { Text("断开连接") }
        } }
        val unknown = state.operations.filter { it.status == OperationStatus.UNKNOWN }
        if (unknown.isNotEmpty()) item { SectionCard("需要核对的操作") { Text("连接中断时，以下操作可能已在电脑执行。请核对桌面状态，应用不会自动重发。"); unknown.forEach { operation ->
            Text("${operation.method} · ${operation.error.orEmpty()}", style = MaterialTheme.typography.bodySmall)
            if (operation.method == "sessions.prompt") OutlinedButton(enabled = state.connected && !busy, onClick = { run { repository.retryOperation(operation.id) } }) { Text("核对后重试此消息") }
        } } }
        item { Text("DSH Mobile · 设备之间的原生工作入口", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { Text("重置通知配置？") }, text = { Text("将删除此安装的推送令牌和 Firebase 配置。重置后请重新打开 App 并连接电脑。") }, confirmButton = { TextButton(onClick = { reset = false; run { AppGraph.resetPushConfiguration?.invoke(); permission = "已重置，请重新打开 App。" } }) { Text("重置") } }, dismissButton = { TextButton(onClick = { reset = false }) { Text("取消") } })
}
