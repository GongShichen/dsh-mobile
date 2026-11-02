package io.dsh.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.dsh.mobile.features.*
import io.dsh.mobile.features.personal.*
import io.dsh.mobile.design.HLTheme
import io.dsh.mobile.design.HLKeyboardDismissalHost
import io.dsh.mobile.design.HLPlatformActionButton
import io.dsh.mobile.model.HLState
import io.dsh.mobile.model.HLConversationMode
import io.dsh.mobile.model.HLPersonalServiceRequest
import io.dsh.mobile.sync.HLGoogleSetupReminderPolicy
import io.dsh.mobile.platform.reportFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

enum class HLTab(val title: String) { CHATS("对话"), WORKSPACES("工作区"), DEVICES("设备"), SETTINGS("设置") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HLApp() {
    val repository = HLAppGraph.repository
    HLTheme {
        if (repository == null) {
            Surface(Modifier.fillMaxSize()) { HLEmptyPanel("正在准备 Harness Link", "连接服务初始化后即可添加电脑。") }
            return@HLTheme
        }
        val state by repository.state.collectAsState()
        val googleReminderPolicy = remember { HLGoogleSetupReminderPolicy() }
        var googleReminder by remember(state.activeHostId) { mutableStateOf<HLPersonalServiceRequest?>(null) }
        var tab by remember { mutableStateOf(HLTab.CHATS) }
        var traceOpen by remember { mutableStateOf(false) }
        var changesOpen by remember { mutableStateOf(false) }
        var personalPanel by remember(state.activeHostId) { mutableStateOf<String?>(null) }
        var targetPicker by remember(state.activeHostId) { mutableStateOf(false) }
        var more by remember { mutableStateOf(false) }
        val busyScopes = remember { mutableStateMapOf<String, Int>() }
        val operationScope = "${state.activeHostId}:${state.conversationMode}:${state.selectedSessionId}"
        val busy = (busyScopes[operationScope] ?: 0) > 0
        var uiError by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        val drawer = rememberDrawerState(DrawerValue.Closed)
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        fun dismissKeyboard() { focus.clearFocus(); keyboard?.hide() }
        @Suppress("DEPRECATION")
        suspend fun closeDrawer() { dismissKeyboard(); drawer.animateTo(DrawerValue.Closed, tween(260, easing = FastOutSlowInEasing)) }
        val snackbar = remember { SnackbarHostState() }
        val run: HLRunAction = { work -> val origin = operationScope; scope.launch {
            busyScopes[origin] = (busyScopes[origin] ?: 0) + 1
            try { work() } catch (error: Throwable) {
                if (error is CancellationException) throw error
                reportFailure("ui.action.failed", error)
                uiError = error.message ?: "操作失败，请重试"
            } finally { val count = (busyScopes[origin] ?: 1) - 1; if (count <= 0) busyScopes.remove(origin) else busyScopes[origin] = count }
        } }
        fun navigate(destination: HLTab) { scope.launch { closeDrawer(); tab = destination } }
        LaunchedEffect(HLAppGraph.conversationRequest) { if (HLAppGraph.conversationRequest > 0) { closeDrawer(); tab = HLTab.CHATS } }
        LaunchedEffect(state.conversationMode, state.personal?.profile?.mainSessionReady, state.personal?.profile?.mainSessionId, state.connected) {
            if (state.connected && state.conversationMode == HLConversationMode.PERSONAL && state.personal?.profile?.mainSessionReady == true) {
                runCatching { repository.openPersonalConversation() }.onFailure { if (it !is CancellationException) uiError = it.message }
            }
        }
        LaunchedEffect(state.personalRequestedTaskId) { if (state.personalRequestedTaskId != null) { personalPanel = "tasks"; tab = HLTab.CHATS } }
        LaunchedEffect(state.activeHostId, state.personal, state.conversationMode, state.connected, tab, state.personalPreferencesReady, state.personalGoogleReminderSuppressed) {
            if (state.personalPreferencesReady) {
                val visible = state.connected && tab == HLTab.CHATS && state.conversationMode == HLConversationMode.PERSONAL
                if (!visible || state.personalGoogleReminderSuppressed || state.personal?.let { it.googleConfigured && it.connections.any { account -> account.state == "connected" } } == true) googleReminder = null
                googleReminderPolicy.observe(state.activeHostId, state.personal, visible, state.personalGoogleReminderSuppressed)?.let { googleReminder = it }
            }
        }
        LaunchedEffect(state.error, uiError) {
            (uiError ?: state.error)?.let { snackbar.showSnackbar(it); uiError = null; repository.clearError() }
        }
        if (traceOpen) HLTrajectoryDialog(state, repository, busy, run) { traceOpen = false }
        if (changesOpen) HLChangesDialog(state, repository) { changesOpen = false }
        if (targetPicker) HLExecutionTargetPicker(repository, state.personalTarget, repository::selectPersonalTarget) { targetPicker = false }
        personalPanel?.let { panel -> key(state.activeHostId) { HLPersonalPanel(state, repository, panel, busy || !state.connected, run) { personalPanel = null; repository.clearPersonalNavigationRequest() } } }
        if (personalPanel == null && !targetPicker && !traceOpen && !changesOpen && !drawer.isOpen && state.interactions.isEmpty() && state.pendingQuestions.isEmpty()) googleReminder?.let { request ->
            HLPersonalDialog(title = if (request.service == "gmail") "连接 Gmail" else "连接 Google 日历", close = { googleReminder = null }, primaryLabel = "暂不配置",
                secondaryLabel = "不再提示", secondary = { googleReminder = null; scope.launch { runCatching { repository.setGoogleSetupReminderSuppressed(true) }.onFailure { if (it !is CancellationException) uiError = it.message } } }) {
                Text("本次请求需要 Google 账户", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(if (state.personal?.googleConfigured == true) "请在电脑的私人助理“账户”页连接 Google 账户，完成后继续这项事务。" else "请在电脑的私人助理“账户”页导入你自己的桌面应用 OAuth JSON 并登录。", style = MaterialTheme.typography.bodyLarge)
                Text("其他私人助理功能可继续使用。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HLKeyboardDismissalHost {
        ModalNavigationDrawer(drawerState = drawer, drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(320.dp).fillMaxHeight(), drawerContainerColor = MaterialTheme.colorScheme.background) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Harness Link", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { scope.launch { closeDrawer() } }, modifier = Modifier.semantics { contentDescription = "收起菜单" }) { HLNavigationIcon("BACK") }
                }
                TextButton(onClick = { if (state.conversationMode == HLConversationMode.PERSONAL) scope.launch { closeDrawer(); personalPanel = "tasks" }
                    else if (state.connected) scope.launch { closeDrawer(); tab = HLTab.CHATS; run { repository.createSession() } } else navigate(HLTab.DEVICES) }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) {
                    HLNavigationIcon("NEW_CHAT", 20.dp); Spacer(Modifier.width(12.dp)); Text(if (state.conversationMode == HLConversationMode.PERSONAL) "交办事务" else "新对话", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
                Box(Modifier.weight(1f)) {
                    if (state.conversationMode == HLConversationMode.PERSONAL) Column(Modifier.fillMaxWidth().padding(8.dp)) {
                        listOf("tasks" to "事务", "goals" to "目标", "memory" to "记忆", "routines" to "周期规则", "connections" to "账户与授权", "activity" to "活动").forEach { (id, title) ->
                            NavigationDrawerItem(label = { Text(title) }, selected = false, onClick = { scope.launch { closeDrawer(); personalPanel = id } }, modifier = Modifier.height(48.dp), shape = MaterialTheme.shapes.small,
                                colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = MaterialTheme.colorScheme.background))
                        }
                    } else HLSessionsScreen(state, repository, busy, run, onAddDevice = { navigate(HLTab.DEVICES) }) { id -> scope.launch { closeDrawer(); tab = HLTab.CHATS; run { repository.openDelegatedSession(id) } } }
                }
                HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.padding(8.dp)) {
                    listOf(HLTab.WORKSPACES, HLTab.DEVICES, HLTab.SETTINGS).forEach { item ->
                        NavigationDrawerItem(label = { Text(item.title, style = MaterialTheme.typography.bodyMedium) }, selected = tab == item,
                            icon = { HLNavigationIcon(item.name, 20.dp) }, onClick = { navigate(item) }, modifier = Modifier.height(48.dp),
                            shape = MaterialTheme.shapes.small, colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = MaterialTheme.colorScheme.background))
                    }
                }

            }
        }) {
            Scaffold(modifier = Modifier.imePadding(), containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = { CenterAlignedTopAppBar(modifier = Modifier.padding(horizontal = 12.dp), title = {
                    if (tab == HLTab.CHATS) HLModeSwitch(state.conversationMode, state.personal?.counts?.attention ?: 0) { mode ->
                        dismissKeyboard(); scope.launch { runCatching { repository.switchConversationMode(mode) }.onFailure { if (it !is CancellationException) uiError = it.message } }
                    } else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (tab == HLTab.CHATS) state.selectedSession?.title?.takeIf { it.isNotBlank() } ?: "新对话" else tab.title,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        if (tab == HLTab.CHATS && state.selectedSessionId != null) Text(workspaceLabel(state), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }, navigationIcon = {
                    HLPlatformActionButton(icon = if (tab == HLTab.CHATS) "MENU" else "BACK", label = if (tab == HLTab.CHATS) "菜单" else "返回") { dismissKeyboard(); if (tab == HLTab.CHATS) scope.launch { drawer.open() } else tab = HLTab.CHATS }
                }, actions = {
                    if (tab == HLTab.CHATS && state.conversationMode == HLConversationMode.NORMAL) HLPlatformActionButton(icon = "NEW_CHAT", label = "新对话", enabled = !busy) { dismissKeyboard(); if (state.connected) run { repository.createSession() } else navigate(HLTab.DEVICES) }
                    if (tab == HLTab.CHATS && state.conversationMode == HLConversationMode.PERSONAL) HLPlatformActionButton(icon = "TIMELINE", label = "事务") { dismissKeyboard(); personalPanel = "tasks" }
                }, colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background)) },
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    if (tab == HLTab.CHATS) Row(Modifier.fillMaxWidth().height(42.dp).padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (state.conversationMode == HLConversationMode.PERSONAL) state.personal?.profile?.name ?: "私人助理" else state.selectedSession?.title?.takeIf { it.isNotBlank() } ?: "新对话", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val subtitle = if (state.conversationMode == HLConversationMode.PERSONAL) if (state.personal?.profile?.paused == true) "已暂停执行" else "长期对话 · 多项事务" else workspaceLabel(state)
                            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Box {
                            IconButton(onClick = { dismissKeyboard(); more = true }, modifier = Modifier.semantics { contentDescription = "对话更多操作" }) { HLNavigationIcon("MORE", 19.dp) }
                            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                                DropdownMenuItem(text = { Text("对话轨迹") }, enabled = state.selectedSessionId != null, onClick = { more = false; traceOpen = true })
                                DropdownMenuItem(text = { Text("文件改动") }, enabled = state.selectedSessionId != null, onClick = { more = false; changesOpen = true })
                                if (state.conversationMode == HLConversationMode.NORMAL) DropdownMenuItem(text = { Text("交给私人助理") }, enabled = state.selectedSessionId != null && "personal-agent-v1" in state.capabilities,
                                    onClick = { more = false; run { repository.delegateCurrentConversation() } })
                            }
                        }
                    }
                    val privateSessions = state.personal?.tasks?.mapNotNull { it.binding?.sessionId }?.toSet().orEmpty() + listOfNotNull(state.personal?.profile?.mainSessionId)
                    val interactionState = if (state.conversationMode == HLConversationMode.NORMAL) state.copy(
                        interactions = state.interactions.filter { it.sessionId !in privateSessions || it.sessionId == state.selectedSessionId },
                        pendingQuestions = state.pendingQuestions.filter { it.sessionId !in privateSessions || it.sessionId == state.selectedSessionId }) else state
                    HLInteractionHost(interactionState, repository, autoOpen = state.conversationMode == HLConversationMode.NORMAL) { id -> scope.launch { closeDrawer(); tab = HLTab.CHATS; run { repository.openDelegatedSession(id) } } }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                    when (tab) {
                        HLTab.CHATS -> key(state.activeHostId, state.conversationMode, state.selectedSessionId) {
                            if (state.conversationMode == HLConversationMode.NORMAL) HLChatScreen(state, repository, busy, run) { navigate(HLTab.DEVICES) }
                            else HLPersonalConversation(state, repository, busy, run, { navigate(HLTab.DEVICES) }, { dismissKeyboard(); targetPicker = true }, { dismissKeyboard(); personalPanel = "tasks" })
                        }
                        HLTab.DEVICES -> HLDevicesScreen(state, repository, busy, run)
                        HLTab.SETTINGS -> HLSettingsScreen(state, repository, busy, run)
                        HLTab.WORKSPACES -> HLWorkspacesScreen(state, repository, busy, run) { val sid = repository.state.value.selectedSessionId; tab = HLTab.CHATS; if (sid != null) run { repository.openDelegatedSession(sid) } }
                    }
                }
            }
        }
        }
    }
}

private fun workspaceLabel(state: HLState): String {
    val id = state.selectedSessionId ?: return ""
    val workspace = state.workspaces.firstOrNull { row ->
        (state.selectedSession?.workspaceId?.let { row.string("workspaceId") == it } == true) || row.array("sessionIds").any { (it as? JsonPrimitive)?.contentOrNull == id }
    } ?: return "未关联工作区"
    return workspace.string("title")?.takeIf { it.isNotBlank() }
        ?: workspace.string("path")?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "工作区"
}
