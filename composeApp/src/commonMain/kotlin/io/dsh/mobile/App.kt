package io.dsh.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.dsh.mobile.features.*
import io.dsh.mobile.model.MobileState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

enum class MobileTab(val title: String, val glyph: String) { CHATS("对话", "◌"), WORKSPACES("工作区", "▤"), DEVICES("设备", "▣"), SETTINGS("设置", "⚙") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val repository = AppGraph.repository
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF356AE6), onPrimary = Color.White,
        primaryContainer = Color(0xFFEAF0FF), secondary = Color(0xFF356AE6), secondaryContainer = Color(0xFFEAF0FF), background = Color(0xFFFAFBFD), surface = Color.White,
        surfaceVariant = Color(0xFFF0F3F8), outlineVariant = Color(0xFFE1E6EF))) {
        if (repository == null) {
            Surface(Modifier.fillMaxSize()) { EmptyPanel("正在准备 DSH Mobile", "连接服务初始化后即可添加电脑。") }
            return@MaterialTheme
        }
        val state by repository.state.collectAsState()
        var tab by remember { mutableStateOf(MobileTab.CHATS) }
        var chatOpen by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var uiError by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        val run: RunAction = { work -> scope.launch {
            busy = true
            try { work() } catch (error: Throwable) {
                if (error is CancellationException) throw error
                uiError = error.message ?: "操作失败，请重试"
            } finally { busy = false }
        } }
        LaunchedEffect(state.selectedSessionId) { if (state.selectedSessionId != null && tab == MobileTab.CHATS) chatOpen = true }
        LaunchedEffect(AppGraph.conversationRequest) { if (AppGraph.conversationRequest > 0) { tab = MobileTab.CHATS; chatOpen = true } }
        LaunchedEffect(state.error, uiError) {
            (uiError ?: state.error)?.let { snackbar.showSnackbar(it); uiError = null; repository.clearError() }
        }
        Scaffold(modifier = Modifier.imePadding(), containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = { TopAppBar(title = { Column {
                Text(if (chatOpen && tab == MobileTab.CHATS) state.selectedSession?.title ?: "新对话" else "DSH Mobile", maxLines = 1)
                Text(connectionLabel(state), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }, navigationIcon = { if (chatOpen && tab == MobileTab.CHATS) TextButton(onClick = { chatOpen = false }) { Text("返回") } },
                actions = { if (tab == MobileTab.CHATS && state.connected) TextButton(enabled = !busy, onClick = { run { repository.createSession(); chatOpen = true } }) { Text("新对话") } }) },
            bottomBar = { if (!chatOpen || tab != MobileTab.CHATS) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                MobileTab.entries.forEach { item -> NavigationBarItem(selected = tab == item, onClick = { tab = item }, icon = { NavigationIcon(item.name) }, label = { Text(item.title) }) }
            } },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                when (tab) {
                    MobileTab.DEVICES -> DevicesScreen(state, repository, busy, run)
                    MobileTab.SETTINGS -> SettingsScreen(state, repository, busy, run)
                    MobileTab.WORKSPACES -> WorkspacesScreen(state, repository, busy, run) { chatOpen = true; tab = MobileTab.CHATS }
                    MobileTab.CHATS -> if (chatOpen) ChatScreen(state, repository, busy, run)
                    else SessionsScreen(state, repository, busy, run, onAddDevice = { tab = MobileTab.DEVICES }) { chatOpen = true }
                }
            }
        }
    }
}

private fun connectionLabel(state: MobileState): String = when (state.connection.name) {
    "CONNECTED" -> "${state.activeHost?.name ?: "电脑"} · ${when (state.path.name) { "DIRECT" -> "直连"; "RELAY" -> "加密中继"; else -> "已连接" }}"
    "AWAITING_APPROVAL" -> "请在电脑上确认配对"
    "CONNECTING", "PAIRING", "SYNCING" -> "正在连接电脑…"
    "RECONNECTING" -> "正在恢复连接…"
    else -> state.activeHost?.let { "${it.name} · 离线" } ?: "从你的电脑继续"
}
