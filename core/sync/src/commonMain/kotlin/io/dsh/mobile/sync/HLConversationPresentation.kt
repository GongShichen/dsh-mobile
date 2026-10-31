package io.dsh.mobile.sync

import io.dsh.mobile.model.*
import kotlinx.serialization.json.*

enum class HLConversationRowKind { MESSAGE, TURN_HEADER, PROCESS_HEADER, TURN_ACTIONS }

data class HLConversationRow(
    val key: String, val kind: HLConversationRowKind, val turnKey: String?,
    val message: HLMessageRecord? = null, val toolResult: HLMessageRecord? = null,
    val groupKey: String? = null, val process: Boolean = false, val copyReply: Boolean = false,
    internal val anchor: String = key,
)

data class HLConversationTurn(
    val key: String, val number: Long?, val presentation: JsonObject?, val running: Boolean,
    val hasProcess: Boolean, val interleavedInput: Boolean,
) {
    val foldable: Boolean get() = hasProcess && !interleavedInput && presentation?.string("status") == "completed"
}

data class HLProcessGroup(
    val key: String, val turnKey: String, val members: List<String>, val title: String,
    val running: Boolean, val failed: Boolean,
)

data class HLConversationTimeline(
    val rows: List<HLConversationRow>, val turns: Map<String, HLConversationTurn>, val groups: Map<String, HLProcessGroup>,
)

/** Read-only presentation over the Host journal. No business state or disclosure preference is persisted. */
class HLConversationProjector {
    private var previousGroups = emptyList<HLProcessGroup>()

    fun project(records: List<HLMessageRecord>, presentations: Map<Long, JsonObject>, sessionRunning: Boolean): HLConversationTimeline {
        data class HLTurnFacts(val key: String, val number: Long?, var start: Long? = null, var end: HLMessageRecord? = null,
            var streaming: Boolean = false, var seenAssistant: Boolean = false, var interleaved: Boolean = false)
        val facts = linkedMapOf<String, HLTurnFacts>()
        val ownership = mutableMapOf<String, String>()
        var active: Long? = null
        var anonymous: String? = null
        for (record in records) {
            val type = record.raw.string("type")
            val data = record.raw.obj("data") ?: record.raw
            val explicit = data.long("turn")
            if (type == "turn/start") active = explicit
            val number = explicit ?: active
            if (record.role == "user" && number == null) anonymous = null
            val visible = record.role == "assistant" || type in setOf("tool/call", "tool/result", "step/error", "turn/error", "turn/end")
            val key = number?.let { "turn:$it" } ?: if (visible) (anonymous ?: "partial:${record.id}".also { anonymous = it }) else null
            if (key != null) {
                ownership[record.id] = key
                val f = facts.getOrPut(key) { HLTurnFacts(key, number) }
                if (record.role == "user" && f.seenAssistant) f.interleaved = true
                if (record.role == "assistant") { f.seenAssistant = true; f.streaming = f.streaming || record.streaming }
                if (type == "turn/start") f.start = record.raw.long("time")
                if (type == "turn/end") f.end = record
            }
            if (type == "turn/end") active = null
        }
        val latest = ownership.values.lastOrNull()
        val turns = facts.mapValues { (key, f) ->
            val supplied = f.number?.let(presentations::get)
            val status = supplied ?: f.end?.let { end ->
                val reason = end.raw.obj("data")?.obj("reason")?.string("kind")
                buildJsonObject {
                    put("status", when (reason) { "error" -> "failed"; "aborted", "interrupted" -> "stopped"; else -> "completed" })
                    val stop = end.raw.long("time")
                    if (f.start != null && stop != null) put("durationMs", (stop - f.start!!).coerceAtLeast(0))
                }
            }
            HLConversationTurn(key, f.number, status, status == null && (f.streaming || (sessionRunning && key == latest)), false, f.interleaved)
        }.toMutableMap()
        val assistants = mutableMapOf<String, String>()
        val ordinals = mutableMapOf<String, Int>()
        for (record in records.filter { it.role == "assistant" }) {
            val data = record.raw.obj("data") ?: record.raw
            val coordinates = "${ownership[record.id]}:step:${data.long("step") ?: record.id}"
            val ordinal = ordinals[coordinates] ?: 0
            ordinals[coordinates] = ordinal + 1
            assistants[record.id] = "assistant:$coordinates:$ordinal"
        }
        fun blocks(record: HLMessageRecord) = record.blocks.ifEmpty {
            canonicalAssistantBlocks(record.raw.obj("data")?.obj("message")?.array("content") ?: JsonArray(emptyList()))
        }
        val actualCalls = records.filter { it.raw.string("type") == "tool/call" }.mapNotNull(::conversationToolId).toSet()
        val toolAnchors = records.filter { it.role == "assistant" }.flatMap { record ->
            blocks(record).mapNotNull { block -> block.toolCallId?.let { it to assistants.getValue(record.id) } }
        }.toMap()
        val knownCalls = actualCalls + toolAnchors.keys
        val results = records.filter { it.raw.string("type") == "tool/result" }.associateBy(::conversationToolId)
        val rows = mutableListOf<HLConversationRow>()
        val groups = linkedMapOf<String, HLProcessGroup>()
        val previousByMember = previousGroups.flatMap { group -> group.members.map { it to group } }.toMap()
        val pending = mutableListOf<HLConversationRow>()
        val reused = mutableSetOf<String>()
        val headers = mutableSetOf<String>()
        val emittedTools = mutableSetOf<String>()
        fun header(key: String?) {
            if (key != null && turns[key]?.presentation != null && headers.add(key)) {
                rows += HLConversationRow("header:$key", HLConversationRowKind.TURN_HEADER, key)
            }
        }
        fun flush(closed: Boolean = true) {
            if (pending.isEmpty()) return
            val turnKey = pending.first().turnKey!!
            val memberKeys = pending.map { it.key }
            val memberSet = memberKeys.toSet()
            val prior = memberKeys.mapNotNull(previousByMember::get).distinctBy { it.key }
                .filter { it.turnKey == turnKey && it.key !in reused && it.members.all(memberSet::contains) }.maxByOrNull { it.members.size }
            val key = prior?.key ?: "process:$turnKey:${pending.first().anchor}:${pending.first().key.substringBefore(':')}"
            reused += key
            val running = !closed && turns[turnKey]?.running == true
            val summary = processSummary(pending, running)
            val group = HLProcessGroup(key, turnKey, memberKeys, summary.first, running, summary.second)
            groups[key] = group
            rows += HLConversationRow("group:$key", HLConversationRowKind.PROCESS_HEADER, turnKey, groupKey = key)
            rows += pending.map { it.copy(groupKey = key) }
            pending.clear()
        }
        fun process(row: HLConversationRow) {
            if (pending.firstOrNull()?.turnKey != row.turnKey) flush()
            header(row.turnKey)
            pending += row
        }
        val closingReplies = mutableMapOf<String, String>()
        for (record in records) {
            val turnKey = ownership[record.id]
            val type = record.raw.string("type")
            if (pending.isNotEmpty() && pending.first().turnKey != turnKey) flush()
            when {
                record.role == "user" -> { flush(); rows += HLConversationRow("user:${record.id}", HLConversationRowKind.MESSAGE, turnKey, record) }
                record.role == "assistant" -> {
                    header(turnKey)
                    val base = assistants.getValue(record.id)
                    val content = blocks(record)
                    val reasoning = content.filter { it.kind == "reasoning" && (reasoningDisplayText(it.text).isNotBlank() || it.streaming) }
                    if (turnKey != null && (reasoning.isNotEmpty() || (record.streaming && content.isEmpty()))) {
                        process(HLConversationRow("reasoning:$base", HLConversationRowKind.MESSAGE, turnKey,
                            record.copy(text = "", blocks = reasoning, phase = if (record.streaming && reasoning.any { it.streaming }) HLAssistantPhase.THINKING else HLAssistantPhase.WAITING), process = true, anchor = base))
                    }
                    val text = content.filter { it.kind == "text" }.ifEmpty {
                        if (record.text.isNotBlank()) listOf(HLAssistantBlock(0, "text", record.text, streaming = record.streaming)) else emptyList()
                    }
                    if (text.any { it.text.isNotEmpty() }) {
                        flush()
                        val rowKey = "reply:$base"
                        rows += HLConversationRow(rowKey, HLConversationRowKind.MESSAGE, turnKey,
                            record.copy(text = text.joinToString("") { it.text }, blocks = text))
                        if (turnKey != null) closingReplies[turnKey] = rowKey
                    }
                    for (block in content.filter { it.kind == "tool-call" }) {
                        val id = block.toolCallId ?: "$base:${block.index}"
                        if (id in actualCalls || !emittedTools.add(id) || turnKey == null) continue
                        val raw = buildJsonObject {
                            put("type", "tool/call"); put("data", buildJsonObject {
                                put("callId", id); put("name", block.name.orEmpty()); put("arguments", block.arguments); put("preparing", true)
                            })
                        }
                        process(HLConversationRow("tool:$id", HLConversationRowKind.MESSAGE, turnKey,
                            record.copy(role = "event", text = "", blocks = emptyList(), raw = raw), results[id], process = true, anchor = base))
                    }
                    if (record.phase == HLAssistantPhase.INTERRUPTED && text.isEmpty()) {
                        flush(); rows += HLConversationRow("stopped:$base", HLConversationRowKind.MESSAGE, turnKey, record.copy(text = "", blocks = emptyList(), raw = JsonObject(emptyMap())))
                    }
                }
                type == "tool/call" || (type == "tool/result" && conversationToolId(record) !in knownCalls) -> {
                    val id = conversationToolId(record) ?: record.id
                    if (turnKey != null && emittedTools.add(id)) process(HLConversationRow("tool:$id", HLConversationRowKind.MESSAGE, turnKey,
                        record, if (type == "tool/call") results[id] else null, process = true, anchor = toolAnchors[id] ?: id))
                }
                type in setOf("step/error", "turn/error") -> { flush(); header(turnKey); rows += HLConversationRow("notice:${record.id}", HLConversationRowKind.MESSAGE, turnKey, record) }
                type == "turn/end" -> {
                    flush(); header(turnKey)
                    if (turnKey != null && facts[turnKey]?.number?.let(presentations::get) != null) rows += HLConversationRow("actions:$turnKey", HLConversationRowKind.TURN_ACTIONS, turnKey)
                }
            }
        }
        flush(closed = pending.firstOrNull()?.turnKey?.let { turns[it]?.running != true } ?: true)
        val processTurns = groups.values.map { it.turnKey }.toSet()
        for ((key, turn) in turns.toMap()) turns[key] = turn.copy(hasProcess = key in processTurns)
        previousGroups = groups.values.toList()
        val footers = rows.filter { it.kind == HLConversationRowKind.TURN_ACTIONS }.map { it.turnKey }.toSet()
        return HLConversationTimeline(rows.map { row -> row.copy(copyReply = row.key == closingReplies[row.turnKey] && row.turnKey !in footers && row.message?.streaming == false) }, turns, groups)
    }
}

fun conversationToolId(message: HLMessageRecord): String? = message.raw.obj("data")?.let { it.obj("message")?.string("toolCallId") ?: it.string("callId") }

/** Whole-Turn folding only affects process, never reply text, errors or action footers. */
fun visibleConversationRows(timeline: HLConversationTimeline, openTurns: Set<String>, openGroups: Set<String>): List<HLConversationRow> = timeline.rows.filter { row ->
    val turn = timeline.turns[row.turnKey]
    val outerOpen = turn?.foldable != true || row.turnKey in openTurns
    when {
        row.kind == HLConversationRowKind.PROCESS_HEADER -> outerOpen
        row.process -> outerOpen && row.groupKey in openGroups
        else -> true
    }
}

private fun processSummary(rows: List<HLConversationRow>, running: Boolean): Pair<String, Boolean> {
    val counts = linkedMapOf<String, Int>()
    var latest: HLMessageRecord? = null
    var failed = false
    for (row in rows) {
        val message = row.message ?: continue
        if (message.raw.string("type") !in setOf("tool/call", "tool/result")) continue
        val category = processActivity(message.raw.obj("data")?.string("name").orEmpty())
        counts[category] = (counts[category] ?: 0) + 1
        val result = (row.toolResult ?: message).raw.obj("data")?.obj("message")
        failed = failed || result?.boolean("isError") == true
        if (row.toolResult == null && message.raw.string("type") == "tool/call") latest = message
    }
    if (running) {
        val name = latest?.raw?.obj("data")?.string("name").orEmpty()
        val category = processActivity(name)
        val preparing = latest?.raw?.obj("data")?.boolean("preparing") == true
        val label = when { latest == null -> "正在分析请求"; preparing && category == "questions" -> "准备提问"; preparing -> liveActivity(category).replaceFirst("正在", "准备"); else -> liveActivity(category) }
        val args = latest?.raw?.obj("data")?.get("arguments")?.let { value ->
            value as? JsonObject ?: (value as? JsonPrimitive)?.contentOrNull?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        }
        val detail = listOf("title", "description", "task", "command", "path", "query").firstNotNullOfOrNull { args?.string(it)?.takeIf(String::isNotBlank) }
            ?: rows.asReversed().firstNotNullOfOrNull { row -> row.message?.blocks?.lastOrNull { it.kind == "reasoning" }?.let { reasoningSummary(it.text, true).takeIf(String::isNotBlank) } }
        return (label + detail?.replace(Regex("\\s+"), " ")?.take(160)?.let { " · $it" }.orEmpty()) to failed
    }
    val labels = counts.entries.sortedByDescending { it.value }.take(3).map { doneActivity(it.key) }
    val title = when (labels.size) {
        0 -> "已完成分析"
        1 -> labels[0]
        2 -> labels[0] + "并" + if (labels.all { it.startsWith("已") }) labels[1].removePrefix("已") else labels[1]
        else -> labels.joinToString("，") + if (counts.size > 3) "等" else ""
    }
    return title to failed
}

/** Category order and Chinese labels follow the pinned official ui-chat process presentation. */
private fun processActivity(name: String): String = when {
    name == "read" -> "read"; name == "read_image" -> "readImage"
    name == "grep" || name == "glob" || name.endsWith("_inspect") -> "search"
    name == "write" -> "write"; name == "edit" || name == "apply_patch" -> "edit"
    name in setOf("bash", "pwsh", "exec_command", "write_stdin") || name.startsWith("terminal_") -> "commands"
    name == "run_code" -> "code"; name == "web_search" -> "webSearch"; name == "web_fetch" -> "webFetch"
    name == "subagent" || name.startsWith("subagent_") -> "subagents"
    name in setOf("todo_write", "create_goal", "update_goal", "get_goal") -> "plan"
    name == "ask_user_question" || name == "request_user_input" -> "questions"
    else -> "tools"
}
private fun doneActivity(kind: String) = when (kind) {
    "read" -> "已读取文件"; "readImage" -> "已读取图片"; "search" -> "已搜索代码"; "write" -> "已写入文件"; "edit" -> "修改了文件"
    "commands" -> "执行了命令"; "code" -> "运行了代码"; "webSearch" -> "已搜索网页"; "webFetch" -> "已访问网页"
    "subagents" -> "已协调子智能体"; "plan" -> "更新了计划"; "questions" -> "向用户提出了问题"; else -> "已调用工具"
}
private fun liveActivity(kind: String) = when (kind) {
    "read" -> "正在读取文件"; "readImage" -> "正在读取图片"; "search" -> "正在搜索代码"; "write" -> "正在写入文件"; "edit" -> "正在编辑文件"
    "commands" -> "正在运行命令"; "code" -> "正在运行代码"; "webSearch" -> "正在搜索网页"; "webFetch" -> "正在访问网页"
    "subagents" -> "正在协调子智能体"; "plan" -> "正在更新计划"; "questions" -> "等待你的操作"; else -> "正在调用工具"
}
