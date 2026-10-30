package io.dsh.mobile.features

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
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
import io.dsh.mobile.AppGraph
import io.dsh.mobile.model.MobileState
import io.dsh.mobile.sync.MobileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.jetbrains.compose.resources.decodeToImageBitmap

@Composable
fun WorkspacesScreen(state: MobileState, repository: MobileRepository, busy: Boolean, run: RunAction, openChat: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    var path by remember { mutableStateOf("") }
    var rename by remember { mutableStateOf<JsonObject?>(null) }
    var title by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("工作区", "文件", "后台任务").forEachIndexed { index, label -> FilterChip(selected = tab == index, onClick = { tab = index }, label = { Text(label) }) }
        }
        when (tab) {
            0 -> LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item { OutlinedButton(enabled = state.connected && !busy, onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("添加电脑上的工作区") } }
                if (state.workspaces.isEmpty()) item { Text(if (state.connected) "还没有工作区。选择电脑中的文件夹开始。" else "连接电脑后查看工作区。") }
                items(state.workspaces) { workspace ->
                    val id = workspace.string("workspaceId") ?: workspace.string("id").orEmpty()
                    SectionCard(workspace.string("title") ?: workspace.string("path") ?: "工作区") {
                        Text(workspace.string("path").orEmpty(), style = MaterialTheme.typography.bodySmall)
                        val sessions = state.sessions.filter { it.workspaceId == id || workspace.array("sessionIds").any { value -> (value as? JsonPrimitive)?.contentOrNull == it.id } }
                        Text("${sessions.size} 段对话", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        sessions.take(8).forEach { session -> TextButton(onClick = { run { repository.selectSession(session.id); openChat() } }) { Text(session.title.ifBlank { "未命名对话" }, maxLines = 1) } }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(enabled = state.connected && !busy && id.isNotBlank(), onClick = { run {
                                val created = repository.call("sessions.create", parameters("workspaceId" to id)).jsonObject
                                repository.refreshSessions()
                                created.string("sessionId")?.let { repository.selectSession(it); openChat() }
                            } }) { Text("新对话") }
                            TextButton(enabled = state.connected && id.isNotBlank(), onClick = { title = workspace.string("title").orEmpty(); rename = workspace }) { Text("重命名") }
                            TextButton(enabled = sessions.isNotEmpty(), onClick = { run { repository.selectSession(sessions.first().id); tab = 1 } }) { Text("浏览文件") }
                        }
                    }
                }
            }
            1 -> key(state.activeHostId, state.selectedSessionId) { FileBrowser(state, repository, busy, run) }
            else -> LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.jobs.isEmpty()) item { Text("没有后台任务", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(state.jobs) { job -> SectionCard(job.string("label") ?: job.string("title") ?: job.string("name") ?: job.string("jobId") ?: "后台任务") {
                    Text(job.string("status") ?: job.string("state").orEmpty())
                    job.string("command")?.let { CodeBlock(it, "shell") }
                    val id = job.string("jobId") ?: job.string("id")
                    val sessionId = state.selectedSessionId
                    val killable = job.string("status") in setOf("running", "stopping")
                    if (id != null && sessionId != null && killable) OutlinedButton(enabled = state.connected && !busy && job.string("status") == "running", onClick = { run { repository.call("jobs.kill", parameters("sessionId" to sessionId, "jobId" to id)) } }) { Text(if (job.string("status") == "stopping") "正在停止" else "停止任务") }
                } }
            }
        }
    }
    if (adding) AlertDialog(onDismissRequest = { adding = false }, title = { Text("添加工作区") }, text = { Column { Text("输入电脑上的文件夹完整路径。"); OutlinedTextField(path, { path = it }, label = { Text("电脑文件夹路径") }) } }, confirmButton = { TextButton(enabled = path.isNotBlank(), onClick = { adding = false; run { repository.call("workspaces.create", parameters("path" to path)) } }) { Text("添加") } }, dismissButton = { TextButton(onClick = { adding = false }) { Text("取消") } })
    rename?.let { workspace -> AlertDialog(onDismissRequest = { rename = null }, title = { Text("重命名工作区") }, text = { OutlinedTextField(title, { title = it }, singleLine = true) }, confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = { rename = null; run { repository.call("workspaces.rename", parameters("workspaceId" to (workspace.string("workspaceId") ?: workspace.string("id").orEmpty()), "title" to title)) } }) { Text("保存") } }, dismissButton = { TextButton(onClick = { rename = null }) { Text("取消") } }) }
}

@Composable
private fun FileBrowser(state: MobileState, repository: MobileRepository, busy: Boolean, run: RunAction) {
    val sessionId = state.selectedSessionId
    if (sessionId == null) { EmptyPanel("先选择一段对话", "文件浏览使用对话所属工作区。请先打开对话，再查看文件。"); return }
    var path by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var truncated by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<JsonObject?>(null) }
    var imagePreview by remember { mutableStateOf<ImageBitmap?>(null) }
    var previewPath by remember { mutableStateOf("") }
    LaunchedEffect(path, state.connected) {
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = path.isNotEmpty(), onClick = { path = path.substringBeforeLast('/', "") }) { Text("上一级") }
            Text(path.ifBlank { "工作区根目录" }, maxLines = 2, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp)) }
        LazyColumn(contentPadding = PaddingValues(12.dp)) {
            if (entries.isEmpty() && !loading && error == null) item { Text("文件夹为空", modifier = Modifier.padding(16.dp)) }
            items(entries) { entry ->
                val name = entry.string("name").orEmpty()
                val directory = entry.string("type") == "directory"
                val childPath = if (path.isBlank()) name else "$path/$name"
                ListItem(headlineContent = { Text(name) }, supportingContent = { Text(if (directory) "文件夹" else entry["size"]?.let { "$it 字节" }.orEmpty()) }, leadingContent = { Text(if (directory) "▤" else "▧") }, modifier = Modifier.clickable(enabled = state.connected && !busy) {
                    if (directory) path = childPath
                    else run {
                        previewPath = childPath; preview = null; imagePreview = null
                        if (childPath.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")) {
                            val bytes = downloadBytes(repository, sessionId, childPath, 8 * 1024 * 1024)
                            imagePreview = withContext(Dispatchers.Default) { bytes.decodeToImageBitmap() }
                        } else preview = repository.call("files.read", buildJsonObject { put("sessionId", sessionId); put("path", childPath); put("range", buildJsonObject { put("offset", 1); put("limit", 200) }) }).jsonObject
                    }
                }, trailingContent = { if (!directory) TextButton(enabled = state.connected && !busy && AppGraph.saveFile != null, onClick = { run { download(repository, sessionId, childPath) } }) { Text("下载") } })
            }
            if (truncated) item { Text("目录条目较多，当前列表已达到电脑端返回上限。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp)) }
        }
    }
    if (preview != null || imagePreview != null) Dialog(onDismissRequest = { preview = null; imagePreview = null }) {
        Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().fillMaxHeight(.85f)) {
            Column(Modifier.padding(16.dp)) {
                Text(previewPath.substringAfterLast('/'), style = MaterialTheme.typography.titleMedium)
                val bitmap = imagePreview
                if (bitmap != null) Image(bitmap, contentDescription = previewPath.substringAfterLast('/'), modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp), contentScale = ContentScale.Fit)
                else Box(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) { CodeBlock(preview?.string("text").orEmpty(), previewPath.substringAfterLast('.', "")) }
                if ((preview?.get("eof") as? JsonPrimitive)?.booleanOrNull == false) Text("当前预览前 200 行，下载可查看完整文件。", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = AppGraph.saveFile != null && !busy, onClick = { run { download(repository, sessionId, previewPath) } }) { Text("下载") }
                    TextButton(onClick = { preview = null; imagePreview = null }) { Text("关闭") }
                }
            }
        }
    }
}

private suspend fun download(repository: MobileRepository, sessionId: String, path: String) {
    val save = AppGraph.saveFile ?: error("此平台尚未提供文件保存入口")
    save(path.substringAfterLast('/'), downloadBytes(repository, sessionId, path, 20 * 1024 * 1024))
}

private suspend fun downloadBytes(repository: MobileRepository, sessionId: String, path: String, limit: Int): ByteArray {
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
