package io.dsh.mobile.sync

import io.dsh.mobile.model.*
import kotlinx.serialization.json.*

/** Ordered model blocks, shared by live deltas and compact reconnect snapshots. */
class HLAssistantBlockAccumulator {
    private val values = mutableMapOf<Int, HLAssistantBlock>()
    val blocks: List<HLAssistantBlock> get() = values.entries.sortedBy { it.key }.map { it.value }
    val text: String get() = blocks.filter { it.kind == "text" }.joinToString("") { it.text }
    val phase: HLAssistantPhase get() = when (blocks.lastOrNull { it.streaming }?.kind ?: blocks.lastOrNull()?.kind) {
        "reasoning" -> HLAssistantPhase.THINKING
        "text" -> HLAssistantPhase.WRITING
        "tool-call" -> HLAssistantPhase.TOOL_CALL
        else -> HLAssistantPhase.WAITING
    }
    fun clear() = values.clear()
    fun apply(chunk: JsonObject) {
        val type = chunk.string("type") ?: return
        val index = chunk.long("index")?.toInt()
        if (index == null) return
        val previous = values[index]
        when (type) {
            "block-start" -> values[index] = (previous ?: HLAssistantBlock(index, chunk.string("blockType") ?: "unknown")).copy(streaming = true)
            "text-delta", "reasoning-delta" -> values[index] = (previous ?: HLAssistantBlock(index, if (type == "text-delta") "text" else "reasoning"))
                .copy(text = previous?.text.orEmpty() + chunk.string("text").orEmpty(), streaming = true)
            "tool-call-delta" -> values[index] = (previous ?: HLAssistantBlock(index, "tool-call"))
                .copy(toolCallId = chunk.string("id") ?: previous?.toolCallId, name = chunk.string("name") ?: previous?.name,
                    arguments = previous?.arguments.orEmpty() + chunk.string("argumentsDelta").orEmpty(), streaming = true)
            "block-end" -> chunk.obj("block")?.let { values[index] = canonicalBlock(index, it) }
        }
    }
    fun restore(compact: JsonArray) {
        clear()
        compact.forEach { raw ->
            val item = raw as? JsonObject ?: return@forEach
            when (item.string("type")) {
                "chunk" -> item.obj("chunk")?.let(::apply)
                "text-chunks", "reasoning-chunks" -> apply(buildJsonObject {
                    put("type", if (item.string("type") == "text-chunks") "text-delta" else "reasoning-delta")
                    put("index", item.long("index") ?: 0)
                    put("text", item.array("texts").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(""))
                })
                "tool-call-chunks" -> apply(buildJsonObject {
                    put("type", "tool-call-delta"); put("index", item.long("index") ?: 0)
                    item.string("id")?.let { put("id", it) }; item.string("name")?.let { put("name", it) }
                    put("argumentsDelta", item.array("args").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(""))
                })
            }
        }
    }
}

fun canonicalAssistantBlocks(content: JsonArray): List<HLAssistantBlock> = content.mapIndexedNotNull { index, value ->
    (value as? JsonObject)?.let { canonicalBlock(index, it) }
}
private fun canonicalBlock(index: Int, block: JsonObject) = HLAssistantBlock(index = index, kind = block.string("type") ?: "unknown",
    text = block.string("text").orEmpty(), toolCallId = block.string("id"), name = block.string("name"),
    arguments = block.string("arguments").orEmpty())

/** Matches the official Web reasoning presentation: provider signatures are never display text. */
fun reasoningDisplayText(raw: String): String {
    val opening = "<signature>"
    if (raw.startsWith("<") && opening.startsWith(raw)) return ""
    if (!raw.startsWith(opening)) return raw
    val end = raw.indexOf("</signature>", opening.length)
    if (end < 0) return ""
    return raw.substring(end + "</signature>".length).trimStart('\r', '\n')
}

/** Web uses the latest completed paragraph's first line while running, the first line when settled. */
fun reasoningSummary(raw: String, running: Boolean): String {
    val text = reasoningDisplayText(raw)
    if (!running) return text.substringBefore('\n').replace("**", "")
    var summary = ""
    var start = 0
    val separators = Regex("\\r?\\n(?:[\\t ]*\\r?\\n)+").findAll(text).toList()
    for (separator in separators) {
        val end = separator.range.first + separator.value.indexOf('\n')
        val newline = text.indexOf('\n', start)
        if (newline >= 0 && newline <= end) text.substring(start, newline).trim().takeIf { it.isNotEmpty() }?.let { summary = it }
        start = separator.range.last + 1
    }
    val newline = text.indexOf('\n', start)
    if (newline >= 0) text.substring(start, newline).trim().takeIf { it.isNotEmpty() }?.let { summary = it }
    return summary.replace("**", "")
}
