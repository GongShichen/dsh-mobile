package io.dsh.mobile.features

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.dsh.mobile.design.HLKeyboardDismissalHost
import io.dsh.mobile.design.keyboardInputArea
import io.dsh.mobile.model.*
import io.dsh.mobile.sync.HLIndexedDiffHunk
import io.dsh.mobile.sync.HLRepository
import io.dsh.mobile.sync.changeAnnouncements
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private data class HLChangeLoad(val loading: Boolean = true, val summary: HLChangeSummary? = null, val error: String? = null)

/** A view of live desktop snapshots. Closing, reconnecting or switching conversations discards all reads. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun HLChangesDialog(state: HLState, repository: HLRepository, close: () -> Unit) {
    val hostId = state.activeHostId
    val sessionId = state.selectedSessionId
    key(hostId, sessionId, state.connectionGeneration, state.connected) {
        val announcements = remember(state.messages, sessionId) { changeAnnouncements(state.messages, sessionId.orEmpty()) }
        val summaries = remember { mutableStateMapOf<Long, HLChangeLoad>() }
        var selected by remember { mutableStateOf<HLChangeFileRef?>(null) }
        var search by remember { mutableStateOf("") }
        var revision by remember { mutableIntStateOf(0) }
        var historyLoading by remember { mutableStateOf(false) }
        var historyError by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        val supported = "changes" in state.capabilities
        LaunchedEffect(announcements, revision) {
            if (!state.connected || !supported || hostId == null) return@LaunchedEffect
            // A replacement event invalidates its old summary and selected comparison.
            val coordinates = announcements.map { it.seq }.toSet()
            summaries.keys.toList().filter { it !in coordinates }.forEach { summaries.remove(it) }
            if (selected?.announcement?.seq?.let { it !in coordinates } == true) selected = null
            val limit = Semaphore(3)
            coroutineScope {
                announcements.filter { summaries[it.seq] == null || summaries[it.seq]?.loading == true }.forEach { announcement -> launch {
                    limit.withPermit {
                        summaries[announcement.seq] = HLChangeLoad()
                        try { summaries[announcement.seq] = HLChangeLoad(loading = false, summary = repository.changeSummary(hostId, announcement)) }
                        catch (error: Throwable) {
                            if (error is CancellationException) throw error
                            summaries[announcement.seq] = HLChangeLoad(loading = false, error = error.message ?: "读取失败，请重试")
                        }
                    }
                } }
            }
        }
        Dialog(onDismissRequest = { if (selected != null) selected = null else close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.widthIn(max = 840.dp).fillMaxWidth().fillMaxHeight(.94f).padding(horizontal = 8.dp), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.background) {
                HLKeyboardDismissalHost {
                    Column(Modifier.fillMaxSize().imePadding()) {
                        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (selected != null) IconButton(onClick = { selected = null }, modifier = Modifier.semantics { contentDescription = "返回改动文件列表" }) { HLNavigationIcon("BACK", 20.dp) }
                            Column(Modifier.weight(1f).padding(start = if (selected == null) 12.dp else 0.dp)) {
                                Text(if (selected == null) "文件改动" else selected!!.file.display.substringAfterLast('/'), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (selected == null) state.selectedSession?.title.orEmpty() else "第 ${selected!!.announcement.turn} 轮 · ${selected!!.file.display}", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = close, modifier = Modifier.semantics { contentDescription = "关闭文件改动" }) { HLNavigationIcon("CLOSE", 20.dp) }
                        }
                        when {
                            !state.connected -> HLChangeNotice("连接桌面后查看文件改动", "文件列表和差异直接读取桌面，不保存在手机。")
                            !supported -> HLChangeNotice("请更新桌面插件", "当前插件尚不支持同步文件改动。")
                            selected != null && hostId != null -> key(selected) { HLChangeDiff(repository, hostId, selected!!, Modifier.weight(1f)) }
                            else -> {
                                TextField(search, { search = it }, label = { Text("搜索改动文件") }, singleLine = true,
                                    leadingIcon = { HLNavigationIcon("SEARCH", 18.dp) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).keyboardInputArea(),
                                    shape = RoundedCornerShape(28.dp), colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("本会话 · 按轮次查看", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                    IconButton(onClick = { summaries.clear(); revision++ }, modifier = Modifier.semantics { contentDescription = "刷新文件改动" }) { HLNavigationIcon("REFRESH", 18.dp) }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                                    if (announcements.isEmpty()) item {
                                        HLChangeNotice("暂无文件改动记录", if (state.selectedSession?.running == true) "本轮结束后，桌面会发布文件快照。" else "桌面尚未发布已载入轮次的文件快照。")
                                    }
                                    announcements.forEach { announcement ->
                                        val load = summaries[announcement.seq] ?: HLChangeLoad()
                                        val summary = load.summary
                                        val files = summary?.files?.withIndex()?.filter { (_, file) -> search.isBlank() || file.display.contains(search, true) || file.path.contains(search, true) }.orEmpty()
                                        if (search.isBlank() || files.isNotEmpty() || load.loading || load.error != null) {
                                            item(key = "turn:${announcement.seq}") { FlowRow(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text("第 ${announcement.turn} 轮", style = MaterialTheme.typography.labelLarge)
                                                if (summary != null) {
                                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                                        Text("${summary.total} 个文件", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                        HLChangeCounts(summary.added, summary.deleted)
                                                    }
                                                }
                                            } }
                                            when {
                                                load.loading -> item(key = "loading:${announcement.seq}") { Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)); Text("正在读取桌面快照…", style = MaterialTheme.typography.bodySmall) } }
                                                load.error != null -> item(key = "error:${announcement.seq}") { Column(Modifier.padding(horizontal = 20.dp)) {
                                                    Text(load.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                                    TextButton(onClick = { summaries.remove(announcement.seq); revision++ }) { Text("重试此轮") }
                                                } }
                                                summary == null -> item(key = "missing:${announcement.seq}") { Text("此轮快照已不可用；桌面重启或会话释放后无法恢复。", Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                                summary.total == 0L -> item(key = "empty:${announcement.seq}") { Text("此轮未记录到文件改动", Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                                else -> {
                                                    items(files, key = { "file:${announcement.seq}:${it.index}" }) { (index, file) -> HLChangedFileRow(file) { selected = HLChangeFileRef(announcement, index, file) } }
                                                    if (summary.total > summary.files.size) item(key = "cap:${announcement.seq}") { Text("桌面仅提供前 ${summary.files.size} / ${summary.total} 个文件", Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                                }
                                            }
                                        }
                                    }
                                    if (search.isNotBlank() && summaries.values.none { it.loading } && announcements.isNotEmpty() && summaries.values.none { load -> load.summary?.files?.any { it.display.contains(search, true) || it.path.contains(search, true) } == true }) item {
                                        Text("没有匹配的改动文件", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    item {
                                        Text("各轮独立统计 · 仅展示已载入记录", Modifier.padding(horizontal = 20.dp, vertical = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (historyError != null) Text(historyError!!, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                        if (state.hasMoreHistory) TextButton(enabled = !historyLoading, onClick = {
                                            scope.launch { historyLoading = true; historyError = null
                                                try { repository.loadHistory() } catch (error: Throwable) { if (error is CancellationException) throw error; historyError = error.message ?: "加载失败，请重试" } finally { historyLoading = false }
                                            }
                                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (historyLoading) "正在载入…" else "加载更早的改动") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HLChangeNotice(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HLChangeCounts(added: Long, deleted: Long) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("+$added", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
        Text("−$deleted", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun HLChangedFileRow(file: HLChangedFile, open: () -> Unit) {
    val largeText = LocalDensity.current.fontScale > 1.2f
    Row(Modifier.fillMaxWidth().clickable(onClickLabel = "查看 ${file.display} 的差异", onClick = open).heightIn(min = 64.dp).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        HLNavigationIcon("DIFF", 20.dp); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(file.display.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val parent = file.display.substringBeforeLast('/', "")
            if (parent.isNotBlank()) Text(parent, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            when { file.binary -> Text("二进制文件", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                file.oversized -> Text("文件过大", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (largeText && !file.binary && !file.oversized) HLChangeCounts(file.added, file.deleted)
        }
        Spacer(Modifier.width(8.dp))
        if (!largeText && !file.binary && !file.oversized) HLChangeCounts(file.added, file.deleted)
        Spacer(Modifier.width(8.dp)); HLNavigationIcon("RIGHT", 14.dp)
    }
    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun HLChangeDiff(repository: HLRepository, hostId: String, ref: HLChangeFileRef, modifier: Modifier) {
    var diff by remember { mutableStateOf<HLFileDiff?>(null) }
    var hunks by remember { mutableStateOf<List<HLIndexedDiffHunk>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(ref, revision) {
        loading = true; error = null; diff = null; hunks = emptyList()
        try {
            val value = repository.changeDiff(hostId, ref)
            val indexed = withContext(Dispatchers.Default) { value?.hunks?.map { HLIndexedDiffHunk(it) }.orEmpty() }
            diff = value; hunks = indexed
        } catch (failure: Throwable) { if (failure is CancellationException) throw failure; error = failure.message ?: "读取文件差异失败" }
        finally { if (currentCoroutineContext().isActive) loading = false }
    }
    Column(modifier) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        when {
            loading -> Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)); Text("正在读取文件差异…") }
            error != null -> { HLChangeNotice("文件差异读取失败", error!!); TextButton(onClick = { revision++ }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("重试") } }
            diff == null -> HLChangeNotice("此轮文件快照已不可用", "桌面已释放此快照，无法还原当时的差异。")
            diff!!.kind == "binary" -> HLChangeNotice("二进制文件发生改动", "桌面没有提供逐行文本差异。")
            diff!!.kind == "oversized" -> HLChangeNotice("文件超过桌面比较上限", "此文件已记录在改动列表中，桌面没有提供逐行差异。")
            else -> {
                val value = diff!!
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(when { value.before == false -> "新增文件"; value.after == false -> "已删除文件"; else -> "修改文件" }, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    HLChangeCounts(ref.file.added, ref.file.deleted)
                }
                Text(if (value.coarse) "比较超时，桌面按整文件替换展示。左列为修改前行号，右列为修改后行号。" else "左列：修改前行号 · 右列：修改后行号", Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val maxLine = value.hunks.maxOfOrNull { maxOf(it.oldStart + it.oldLines, it.newStart + it.newLines) } ?: 1
                val gutter = with(LocalDensity.current) { (maxOf(3, maxLine.toString().length) * 8 + 8).sp.toDp() }
                SelectionContainer(Modifier.weight(1f)) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp)) {
                        if (hunks.isEmpty()) item { Text("此文件没有可展示的文本行差异", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium) }
                        hunks.forEachIndexed { hunkIndex, indexed ->
                            val hunk = indexed.hunk
                            item(key = "h:$hunkIndex") { Text("@@ −${hunk.oldStart},${hunk.oldLines} +${hunk.newStart},${hunk.newLines} @@", Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                            itemsIndexed(hunk.lines, key = { index, _ -> "$hunkIndex:$index" }) { index, _ ->
                                val line = indexed.line(index)
                                val background = when (line.prefix) { '+' -> MaterialTheme.colorScheme.tertiaryContainer; '-' -> MaterialTheme.colorScheme.errorContainer; else -> MaterialTheme.colorScheme.background }
                                val foreground = when (line.prefix) { '+' -> MaterialTheme.colorScheme.onTertiaryContainer; '-' -> MaterialTheme.colorScheme.onErrorContainer; else -> MaterialTheme.colorScheme.onBackground }
                                Row(Modifier.fillMaxWidth().background(background).padding(end = 12.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.Top) {
                                    Text(line.oldLine?.toString().orEmpty(), Modifier.width(gutter).padding(horizontal = 4.dp), textAlign = TextAlign.End, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 20.sp, color = foreground)
                                    Text(line.newLine?.toString().orEmpty(), Modifier.width(gutter).padding(horizontal = 4.dp), textAlign = TextAlign.End, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 20.sp, color = foreground)
                                    Text(line.prefix.toString(), Modifier.padding(horizontal = 4.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 20.sp, color = foreground)
                                    Text(line.text.ifEmpty { " " }, Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp, color = foreground, softWrap = true)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
