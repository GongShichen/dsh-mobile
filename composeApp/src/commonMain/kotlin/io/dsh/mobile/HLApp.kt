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
import io.dsh.mobile.design.HLTheme
import io.dsh.mobile.design.HLKeyboardDismissalHost
import io.dsh.mobile.design.HLPlatformActionButton
import io.dsh.mobile.model.HLState
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
        var tab by remember { mutableStateOf(HLTab.CHATS) }
        var traceOpen by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var uiError by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        val drawer = rememberDrawerState(DrawerValue.Closed)
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        fun dismissKeyboard() { focus.clearFocus(); keyboard?.hide() }
        @Suppress("DEPRECATION")
        suspend fun closeDrawer() { dismissKeyboard(); drawer.animateTo(DrawerValue.Closed, tween(260, easing = FastOutSlowInEasing)) }
        val snackbar = remember { SnackbarHostState() }
        val run: HLRunAction = { work -> scope.launch {
            busy = true
            try { work() } catch (error: Throwable) {
                if (error is CancellationException) throw error
                reportFailure("ui.action.failed", error)
                uiError = error.message ?: "操作失败，请重试"
            } finally { busy = false }
        } }
        fun navigate(destination: HLTab) { scope.launch { closeDrawer(); tab = destination } }
        LaunchedEffect(HLAppGraph.conversationRequest) { if (HLAppGraph.conversationRequest > 0) { closeDrawer(); tab = HLTab.CHATS } }
        LaunchedEffect(state.error, uiError) {
            (uiError ?: state.error)?.let { snackbar.showSnackbar(it); uiError = null; repository.clearError() }
        }
        if (traceOpen) HLTrajectoryDialog(state, repository, busy, run) { traceOpen = false }
        HLKeyboardDismissalHost {
        ModalNavigationDrawer(drawerState = drawer, drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(320.dp).fillMaxHeight(), drawerContainerColor = MaterialTheme.colorScheme.background) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Harness Link", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { scope.launch { closeDrawer() } }, modifier = Modifier.semantics { contentDescription = "收起菜单" }) { HLNavigationIcon("BACK") }
                }
                TextButton(onClick = { if (state.connected) scope.launch { closeDrawer(); tab = HLTab.CHATS; run { repository.createSession() } } else navigate(HLTab.DEVICES) }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) {
                    HLNavigationIcon("NEW_CHAT", 20.dp); Spacer(Modifier.width(12.dp)); Text("新对话", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
                Box(Modifier.weight(1f)) { HLSessionsScreen(state, repository, busy, run, onAddDevice = { navigate(HLTab.DEVICES) }) { id -> scope.launch { closeDrawer(); tab = HLTab.CHATS; run { repository.selectSession(id) } } } }
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
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (tab == HLTab.CHATS) state.selectedSession?.title?.takeIf { it.isNotBlank() } ?: "新对话" else tab.title,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        if (tab == HLTab.CHATS && state.selectedSessionId != null) Text(workspaceLabel(state), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }, navigationIcon = {
                    HLPlatformActionButton(icon = if (tab == HLTab.CHATS) "MENU" else "BACK", label = if (tab == HLTab.CHATS) "菜单" else "返回") { dismissKeyboard(); if (tab == HLTab.CHATS) scope.launch { drawer.open() } else tab = HLTab.CHATS }
                }, actions = {
                    if (tab == HLTab.CHATS && state.selectedSessionId != null) IconButton(onClick = { dismissKeyboard(); traceOpen = true }, modifier = Modifier.semantics { contentDescription = "查看对话轨迹" }) { HLNavigationIcon("TIMELINE", 19.dp) }
                    if (tab == HLTab.CHATS) HLPlatformActionButton(icon = "NEW_CHAT", label = "新对话", enabled = !busy) { dismissKeyboard(); if (state.connected) run { repository.createSession() } else navigate(HLTab.DEVICES) }
                }, colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background)) },
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    HLInteractionHost(state, repository) { id -> scope.launch { closeDrawer(); tab = HLTab.CHATS; run { repository.selectSession(id) } } }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                    when (tab) {
                        HLTab.CHATS -> HLChatScreen(state, repository, busy, run) { navigate(HLTab.DEVICES) }
                        HLTab.DEVICES -> HLDevicesScreen(state, repository, busy, run)
                        HLTab.SETTINGS -> HLSettingsScreen(state, repository, busy, run)
                        HLTab.WORKSPACES -> HLWorkspacesScreen(state, repository, busy, run) { tab = HLTab.CHATS }
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
