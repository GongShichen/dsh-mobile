package io.dsh.mobile.features

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.dsh.mobile.design.keyboardInputArea
import io.dsh.mobile.HLAppGraph
import io.dsh.mobile.model.HLState
import io.dsh.mobile.sync.HLRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.jetbrains.compose.resources.decodeToImageBitmap

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HLWorkspacesScreen(state: HLState, repository: HLRepository, busy: Boolean, run: HLRunAction, openChat: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    var path by remember { mutableStateOf("") }
    var rename by remember { mutableStateOf<JsonObject?>(null) }
    var title by remember { mutableStateOf("") }
    var expandedWorkspace by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background, divider = {}) {
            listOf("工作区", "文件", "任务").forEachIndexed { index, label ->
                Tab(selected = tab == index, onClick = { tab = index }, selectedContentColor = MaterialTheme.colorScheme.primary, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant, text = { Text(label, style = MaterialTheme.typography.bodyMedium) })
            }
        }
        when (tab) {
            0 -> LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
                item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("电脑上的工作区", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    IconButton(enabled = state.connected && !busy, onClick = { adding = true }, modifier = Modifier.semantics { contentDescription = "添加电脑上的工作区" }) { HLNavigationIcon("PLUS", 20.dp) }
                } }
                if (state.workspaces.isEmpty()) item { Text(if (state.connected) "还没有工作区，添加电脑中的文件夹开始。" else "连接电脑后查看工作区。", Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(state.workspaces) { workspace ->
                    val id = workspace.string("workspaceId") ?: workspace.string("id").orEmpty()
                    val folderPath = workspace.string("path").orEmpty()
                    val label = workspace.string("title")?.takeIf { it.isNotBlank() } ?: folderPath.substringAfterLast('/').ifBlank { "工作区" }
                    val sessions = state.sessions.filter { session -> session.workspaceId == id || workspace.array("sessionIds").any { (it as? JsonPrimitive)?.contentOrNull == session.id } }
                    val expanded = expandedWorkspace == id
                    var menu by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { expandedWorkspace = if (expanded) null else id }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) { HLNavigationIcon("WORKSPACES", 24.dp) }
                        Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (folderPath.isNotBlank()) folderPath else "${sessions.size} 段对话", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Box {
                            IconButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = "$label 的操作" }) { HLNavigationIcon("MORE", 18.dp) }
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("重命名") }, enabled = state.connected && id.isNotBlank(), onClick = { menu = false; title = workspace.string("title").orEmpty(); rename = workspace })
                            }
                        }
                    }
                    if (expanded) Column(Modifier.padding(start = 38.dp, bottom = 12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(enabled = state.connected && !busy && id.isNotBlank(), onClick = { run {
                                val created = repository.call("sessions.create", parameters("workspaceId" to id)).jsonObject
                                repository.refreshSessions(); created.string("sessionId")?.let { repository.selectSession(it); openChat() }
                            } }) { HLNavigationIcon("NEW_CHAT", 16.dp); Spacer(Modifier.width(6.dp)); Text("新对话") }
                            TextButton(enabled = sessions.isNotEmpty() && state.connected && !busy, onClick = { run { repository.selectSession(sessions.first().id); tab = 1 } }) { HLNavigationIcon("FILE", 16.dp); Spacer(Modifier.width(6.dp)); Text("浏览文件") }
                        }
                        if (sessions.isEmpty()) Text("创建对话后即可浏览文件", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        sessions.take(8).forEach { session -> Row(Modifier.fillMaxWidth().clickable { openChat(); run { repository.selectSession(session.id) } }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(session.title.ifBlank { "未命名对话" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) { HLNavigationIcon("RIGHT", 14.dp) }
                        } }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                }
            }
            1 -> key(state.activeHostId, state.selectedSessionId) { HLFileBrowser(state, repository, busy, run) }
            else -> LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.jobs.isEmpty()) item { Text("没有后台任务", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(state.jobs) { job -> HLSectionCard(job.string("label") ?: job.string("title") ?: job.string("name") ?: job.string("jobId") ?: "后台任务") {
                    Text(job.string("status") ?: job.string("state").orEmpty())
                    job.string("command")?.let { HLCodeBlock(it, "shell") }
                    val id = job.string("jobId") ?: job.string("id")
                    val sessionId = state.selectedSessionId
                    val killable = job.string("status") in setOf("running", "stopping")
                    if (id != null && sessionId != null && killable) OutlinedButton(enabled = state.connected && !busy && job.string("status") == "running", onClick = { run { repository.call("jobs.kill", parameters("sessionId" to sessionId, "jobId" to id)) } }) { Text(if (job.string("status") == "stopping") "正在停止" else "停止任务") }
                } }
            }
        }
    }
    if (adding) AlertDialog(onDismissRequest = { adding = false }, title = { Text("添加工作区") }, text = { Column { Text("输入电脑上的文件夹完整路径。"); OutlinedTextField(path, { path = it }, label = { Text("电脑文件夹路径") }, modifier = Modifier.keyboardInputArea()) } }, confirmButton = { TextButton(enabled = path.isNotBlank(), onClick = { adding = false; run { repository.call("workspaces.create", parameters("path" to path)) } }) { Text("添加") } }, dismissButton = { TextButton(onClick = { adding = false }) { Text("取消") } })
    rename?.let { workspace -> AlertDialog(onDismissRequest = { rename = null }, title = { Text("重命名工作区") }, text = { OutlinedTextField(title, { title = it }, singleLine = true, modifier = Modifier.keyboardInputArea()) }, confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = { rename = null; run { repository.call("workspaces.rename", parameters("workspaceId" to (workspace.string("workspaceId") ?: workspace.string("id").orEmpty()), "title" to title)) } }) { Text("保存") } }, dismissButton = { TextButton(onClick = { rename = null }) { Text("取消") } }) }
}

@Composable
private fun HLFileBrowser(state: HLState, repository: HLRepository, busy: Boolean, run: HLRunAction) {
    val sessionId = state.selectedSessionId
    if (sessionId == null) { HLEmptyPanel("先选择一段对话", "文件浏览使用对话所属工作区。请先打开对话，再查看文件。"); return }
    val workspace = state.workspaces.firstOrNull { row -> (state.selectedSession?.workspaceId?.let { row.string("workspaceId") == it } == true) || row.array("sessionIds").any { (it as? JsonPrimitive)?.contentOrNull == sessionId } }
    val workspaceName = workspace?.string("title")?.takeIf { it.isNotBlank() } ?: "当前会话的工作区"
    val rootPath = state.selectedSession?.raw?.string("cwd") ?: workspace?.string("path")
    val clipboard = LocalClipboardManager.current
    var path by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    var truncated by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<JsonObject?>(null) }
    var imagePreview by remember { mutableStateOf<ImageBitmap?>(null) }
    var previewPath by remember { mutableStateOf("") }
    LaunchedEffect(path, state.connected, refresh) {
        if (!state.connected) return@LaunchedEffect
        loading = true; error = null
        try {
            val result = repository.call("files.list", parameters("sessionId" to sessionId, "path" to path)).jsonObject
            entries = result.array("entries").mapNotNull { it as? JsonObject }
            truncated = (result["truncated"] as? JsonPrimitive)?.booleanOrNull == true
        } catch (failure: Throwable) { if (failure is CancellationException) throw failure; error = failure.message ?: "无法读取文件夹" }
        finally { loading = false }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(workspaceName, style = MaterialTheme.typography.titleMedium)
            Text("电脑上的文件 · 点击文件可预览", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            rootPath?.let { fullPath -> Row(verticalAlignment = Alignment.CenterVertically) {
                SelectionContainer(Modifier.weight(1f)) { Text(fullPath, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(onClick = { clipboard.setText(AnnotatedString(fullPath)) }, modifier = Modifier.semantics { contentDescription = "复制电脑路径" }) { HLNavigationIcon("COPY", 16.dp) }
            } }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { path = "" }) { HLNavigationIcon("WORKSPACES", 16.dp); Spacer(Modifier.width(6.dp)); Text("根目录", style = MaterialTheme.typography.bodyMedium) }
                val segments = path.split('/').filter { it.isNotEmpty() }
                segments.forEachIndexed { index, segment ->
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) { HLNavigationIcon("RIGHT", 12.dp) }
                    TextButton(onClick = { path = segments.take(index + 1).joinToString("/") }) { Text(segment, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            IconButton(enabled = state.connected && !loading, onClick = { refresh++ }, modifier = Modifier.semantics { contentDescription = "刷新文件列表" }) { HLNavigationIcon("REFRESH", 18.dp) }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp)) }
        LazyColumn(contentPadding = PaddingValues(12.dp)) {
            if (entries.isEmpty() && !loading && error == null) item { Text("文件夹为空", modifier = Modifier.padding(16.dp)) }
            items(entries.sortedWith(compareByDescending<JsonObject> { it.string("type") == "directory" }.thenBy { it.string("name")?.lowercase() })) { entry ->
                val name = entry.string("name").orEmpty()
                val directory = entry.string("type") == "directory"
                val childPath = if (path.isBlank()) name else "$path/$name"
                ListItem(headlineContent = { Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text(if (directory) "文件夹" else fileSize((entry["size"] as? JsonPrimitive)?.longOrNull), style = MaterialTheme.typography.bodySmall) }, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background), leadingContent = { CompositionLocalProvider(LocalContentColor provides if (directory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) { HLNavigationIcon(if (directory) "WORKSPACES" else "FILE", 22.dp) } }, modifier = Modifier.clickable(enabled = state.connected && !busy) {
                    if (directory) path = childPath
                    else run {
                        previewPath = childPath; preview = null; imagePreview = null
                        if (childPath.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")) {
                            val bytes = downloadBytes(repository, sessionId, childPath, 8 * 1024 * 1024)
                            imagePreview = withContext(Dispatchers.Default) { bytes.decodeToImageBitmap() }
                        } else preview = repository.call("files.read", buildJsonObject { put("sessionId", sessionId); put("path", childPath); put("range", buildJsonObject { put("offset", 1); put("limit", 200) }) }).jsonObject
                    }
                }, trailingContent = { if (directory) HLNavigationIcon("RIGHT", 14.dp) else IconButton(enabled = state.connected && !busy && HLAppGraph.saveFile != null, onClick = { run { download(repository, sessionId, childPath) } }, modifier = Modifier.semantics { contentDescription = "下载 $name" }) { HLNavigationIcon("DOWNLOAD", 18.dp) } })
            }
            if (truncated) item { Text("目录条目较多，当前列表已达到电脑端返回上限。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp)) }
        }
    }
    if (preview != null || imagePreview != null) Dialog(onDismissRequest = { preview = null; imagePreview = null }) {
        Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().fillMaxHeight(.85f)) {
            Column(Modifier.padding(16.dp)) {
                Text(previewPath.substringAfterLast('/'), style = MaterialTheme.typography.titleMedium)
                Text("只读预览 · ${previewPath.substringAfterLast('.', "文件").uppercase()} · $workspaceName", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer { Text(previewPath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                preview?.let { page ->
                    val start = (page["offset"] as? JsonPrimitive)?.longOrNull ?: 1
                    val count = (page["lines"] as? JsonPrimitive)?.longOrNull ?: 0
                    Text(if (count == 0L) "文件为空或没有可预览的文本" else "第 $start–${start + count - 1} 行" + if ((page["eof"] as? JsonPrimitive)?.booleanOrNull == true) " · 已显示全部文本" else " · 还有更多内容", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val bitmap = imagePreview
                if (bitmap != null) Image(bitmap, contentDescription = previewPath.substringAfterLast('/'), modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp), contentScale = ContentScale.Fit)
                else Box(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) { HLCodeBlock(preview?.string("text").orEmpty(), previewPath.substringAfterLast('.', "")) }
                if ((preview?.get("eof") as? JsonPrimitive)?.booleanOrNull == false) Text("当前预览前 200 行，下载可查看完整文件。", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = HLAppGraph.saveFile != null && !busy, onClick = { run { download(repository, sessionId, previewPath) } }) { Text("下载") }
                    TextButton(onClick = { preview = null; imagePreview = null }) { Text("关闭") }
                }
            }
        }
    }
}

private suspend fun download(repository: HLRepository, sessionId: String, path: String) {
    val save = HLAppGraph.saveFile ?: error("此平台尚未提供文件保存入口")
    save(path.substringAfterLast('/'), downloadBytes(repository, sessionId, path, 20 * 1024 * 1024))
}

private suspend fun downloadBytes(repository: HLRepository, sessionId: String, path: String, limit: Int): ByteArray {
    val chunks = mutableListOf<ByteArray>()
    var length = 0
    repository.downloadFile(sessionId, path) { bytes ->
        if (length + bytes.size > limit) error("文件超过手机当前 ${limit / 1024 / 1024} MiB 处理上限，请在电脑查看。")
        chunks += bytes.copyOf(); length += bytes.size
    }
    // downloadFile verifies the complete digest before any bytes are exposed to the platform saver.
    val output = ByteArray(length); var offset = 0
    chunks.forEach { it.copyInto(output, offset); offset += it.size }
    return output
}

private fun fileSize(size: Long?): String = when {
    size == null -> "文件"
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "${size / 1024} KB"
    else -> "${size / (1024 * 1024)} MB"
}
