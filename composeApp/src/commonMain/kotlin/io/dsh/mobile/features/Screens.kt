package io.dsh.mobile.features

import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.dsh.mobile.design.IconTile
import io.dsh.mobile.design.StatusPill
import androidx.compose.ui.unit.dp
import io.dsh.mobile.design.keyboardInputArea
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
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 380.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            IconTile("CHATS", Modifier.padding(bottom = 8.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            action?.invoke()
        }
    }
}
@Composable
internal fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun DevicesScreen(state: MobileState, repository: MobileRepository, busy: Boolean, run: RunAction) {
    val focus = LocalFocusManager.current
    var invitation by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("我的手机") }
    var manual by remember { mutableStateOf(false) }
    var forget by remember { mutableStateOf<DesktopHost?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (state.hosts.isNotEmpty()) item { Text("已配对的电脑", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(state.hosts, key = { it.id }) { host ->
            var details by remember(host.id) { mutableStateOf(false) }
            var menu by remember(host.id) { mutableStateOf(false) }
            val online = state.activeHostId == host.id && state.connected
            Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, if (online) MaterialTheme.colorScheme.primary.copy(alpha = .3f) else MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconTile("LAPTOP")
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(host.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if (online) "你的工作区已同步" else "打开连接，继续电脑上的工作", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box {
                            IconButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = "电脑操作" }) { NavigationIcon("MORE") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("设备详情") }, onClick = { menu = false; details = !details })
                                DropdownMenuItem(text = { Text("移除电脑") }, onClick = { menu = false; forget = host })
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(if (online) "已连接 · ${if (state.path == ConnectionPath.DIRECT) "直连" else if (state.path == ConnectionPath.RELAY) "加密中继" else "同步中"}" else "离线", online)
                        Spacer(Modifier.weight(1f))
                        if (online) TextButton(enabled = !busy, onClick = { run { repository.disconnect() } }) { Text("断开") }
                        else FilledTonalButton(enabled = !busy, onClick = { run { repository.connect(host.id) } }) { Text("连接") }
                    }
                    if (details) SelectionContainer { Text(host.endpointId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        item {
            Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconTile("SCAN")
                        Column(Modifier.weight(1f)) {
                            Text("连接一台电脑", style = MaterialTheme.typography.titleLarge)
                            Text("一次配对，随时继续", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text("在电脑的 DeepSeek Harness 中打开「移动访问」，扫描二维码即可连接。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(enabled = !busy && AppGraph.scanner != null, onClick = { focus.clearFocus(); run { AppGraph.scanner?.scan()?.let { repository.pair(it, name) } } }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) {
                        NavigationIcon("SCAN"); Spacer(Modifier.width(8.dp)); Text("扫描配对二维码")
                    }
                    TextButton(onClick = { manual = true }, modifier = Modifier.fillMaxWidth()) { Text("或粘贴配对链接") }
                }
            }
        }
        if (state.connection == ConnectionStatus.AWAITING_APPROVAL || state.connection == ConnectionStatus.PAIRING) item {
            SectionCard(if (state.connection == ConnectionStatus.AWAITING_APPROVAL) "等待电脑确认" else "正在建立加密连接") {
                Text("请在电脑上核对手机名称并允许连接。", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { run { repository.disconnect() } }) { Text("取消配对") }
            }
        }
        item { Text("通过端到端加密连接，继续电脑上的会话与工作。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp)) }
    }
    if (state.connection in setOf(ConnectionStatus.PAIRING, ConnectionStatus.AWAITING_APPROVAL)) AlertDialog(
        onDismissRequest = { run { repository.disconnect() } },
        title = { Text(if (state.connection == ConnectionStatus.AWAITING_APPROVAL) "请在电脑上确认" else "正在连接电脑") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(if (state.connection == ConnectionStatus.AWAITING_APPROVAL) "电脑已收到配对请求，请在移动访问面板确认这台手机。" else "正在建立加密连接，最长等待约 30 秒。", style = MaterialTheme.typography.bodyMedium)
        } },
        confirmButton = { TextButton(onClick = { run { repository.disconnect() } }) { Text("取消连接") } },
    )
    if (manual) AlertDialog(onDismissRequest = { manual = false }, title = { Text("使用配对链接") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("在电脑的移动访问面板复制链接，然后粘贴到这里。", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(name, { name = it }, label = { Text("手机名称") }, singleLine = true, modifier = Modifier.fillMaxWidth().keyboardInputArea("device-name"), shape = MaterialTheme.shapes.small)
            OutlinedTextField(invitation, { invitation = it }, label = { Text("或粘贴配对链接") }, maxLines = 3, modifier = Modifier.fillMaxWidth().keyboardInputArea("pairing-link"), shape = MaterialTheme.shapes.small)
        }
    }, confirmButton = { TextButton(enabled = invitation.isNotBlank() && !busy, onClick = { val value = invitation; invitation = ""; manual = false; focus.clearFocus(); run { repository.pair(value, name) } }) { Text("连接并等待电脑确认") } }, dismissButton = { TextButton(onClick = { manual = false }) { Text("取消") } })
    forget?.let { host -> AlertDialog(onDismissRequest = { forget = null }, title = { Text("移除 ${host.name}？") }, text = { Text("清除本机保存的电脑入口。电脑端的授权可在“移动访问”中撤销。") }, confirmButton = { TextButton(onClick = { forget = null; run { repository.forgetHost(host.id) } }) { Text("移除") } }, dismissButton = { TextButton(onClick = { forget = null }) { Text("取消") } }) }
}

@Composable
fun SessionsScreen(state: MobileState, repository: MobileRepository, busy: Boolean, run: RunAction, onAddDevice: () -> Unit, openSession: (String) -> Unit) {
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
    if (state.hosts.isEmpty()) { Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("连接电脑后，对话会显示在这里", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); TextButton(onClick = onAddDevice) { Text("添加电脑") } }; return }
    val sessions = state.sessions.filter { it.archived == archived && (query.isBlank() || it.title.contains(query, ignoreCase = true) || matches.containsKey(it.id)) }.sortedWith(compareByDescending<SessionSummary> { it.pinned }.thenByDescending { it.updatedAt })
    Column(Modifier.fillMaxSize()) {
        TextField(query, { query = it }, placeholder = { Text("搜索对话", style = MaterialTheme.typography.bodyMedium) }, leadingIcon = { NavigationIcon("SEARCH", 18.dp) }, singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium, shape = RoundedCornerShape(28.dp),
            colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).keyboardInputArea("session-search").semantics { contentDescription = "搜索对话" })
        if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        searchError?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp)) }
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (archived) "已归档" else "最近对话", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            var listMenu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { listMenu = true }, modifier = Modifier.semantics { contentDescription = "对话列表选项" }) { NavigationIcon("MORE", 18.dp) }
                DropdownMenu(listMenu, onDismissRequest = { listMenu = false }) {
                    DropdownMenuItem(text = { Text(if (archived) "查看最近对话" else "查看已归档") }, onClick = { archived = !archived; listMenu = false })
                    DropdownMenuItem(text = { Text("刷新列表") }, enabled = state.connected && !busy, onClick = { listMenu = false; run { repository.refreshSessions() } })
                }
            }
        }
        if (sessions.isEmpty()) Text(if (query.isNotBlank()) "没有匹配的对话" else if (archived) "没有已归档对话" else "点上方新对话开始", Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            items(sessions, key = { it.id }) { session ->
                var menu by remember { mutableStateOf(false) }
                ListItem(headlineContent = { Text(session.title.ifBlank { "未命名对话" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) },
                    supportingContent = if (query.isNotBlank() && !matches[session.id].isNullOrBlank()) ({ Text(matches[session.id].orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }) else null,
                    leadingContent = if (session.running || session.pinned) ({ if (session.running) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp) else NavigationIcon("PIN", 14.dp) }) else null,
                    trailingContent = { Box { IconButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = "会话操作" }) { NavigationIcon("MORE", 18.dp) }; DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("重命名") }, enabled = state.connected, onClick = { menu = false; title = session.title; rename = session })
                        DropdownMenuItem(text = { Text(if (session.pinned) "取消置顶" else "置顶") }, enabled = state.connected, onClick = { menu = false; run { repository.call(if (session.pinned) "sessions.unpin" else "sessions.pin", parameters("sessionId" to session.id)); repository.refreshSessions() } })
                        DropdownMenuItem(text = { Text("从这里创建分支") }, enabled = state.connected, onClick = { menu = false; run { val response = repository.call("sessions.fork", parameters("sessionId" to session.id)).jsonObject; repository.refreshSessions(); response.string("sessionId")?.let(openSession) } })
                        DropdownMenuItem(text = { Text(if (session.archived) "恢复对话" else "归档") }, enabled = state.connected, onClick = { menu = false; run { if (session.archived) repository.call("sessions.unarchive", parameters("sessionId" to session.id)) else repository.archiveSession(session.id); repository.refreshSessions() } })
                    } } }, colors = ListItemDefaults.colors(containerColor = if (session.id == state.selectedSessionId) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background), modifier = Modifier.clip(MaterialTheme.shapes.small).clickable { openSession(session.id) })
            }
        }
    }
    rename?.let { session -> AlertDialog(onDismissRequest = { rename = null }, title = { Text("重命名对话") }, text = { OutlinedTextField(title, { title = it }, singleLine = true, modifier = Modifier.keyboardInputArea()) }, confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = { rename = null; run { repository.renameSession(session.id, title) } }) { Text("保存") } }, dismissButton = { TextButton(onClick = { rename = null }) { Text("取消") } }) }
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
            Text("会话与文件以电脑端为准，手机不保存聊天历史。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(enabled = !busy && state.activeHostId != null, onClick = { run { repository.disconnect() } }) { Text("断开连接") }
        } }
        val unknown = state.operations.filter { it.status == OperationStatus.UNKNOWN }
        if (unknown.isNotEmpty()) item { SectionCard("需要核对的操作") { Text("连接中断时，以下操作可能已在电脑执行。请核对桌面状态，应用不会自动重发。"); unknown.forEach { operation ->
            Text("${operation.method} · ${operation.error.orEmpty()}", style = MaterialTheme.typography.bodySmall)
            if (operation.method == "sessions.prompt") OutlinedButton(enabled = state.connected && !busy, onClick = { run { repository.retryOperation(operation.id) } }) { Text("核对后重试此消息") }
        } } }
        item { Text("Harness Link · 设备之间的原生工作入口", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { Text("重置通知配置？") }, text = { Text("将删除此安装的推送令牌和 Firebase 配置。重置后请重新打开 App 并连接电脑。") }, confirmButton = { TextButton(onClick = { reset = false; run { AppGraph.resetPushConfiguration?.invoke(); permission = "已重置，请重新打开 App。" } }) { Text("重置") } }, dismissButton = { TextButton(onClick = { reset = false }) { Text("取消") } })
}
