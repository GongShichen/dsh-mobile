package io.dsh.mobile.sync

import io.dsh.mobile.model.*
import kotlinx.serialization.json.*
import kotlin.test.*

class HLConversationPresentationTest {
    private fun event(seq: Long, type: String, turn: Long? = 1, step: Long = 1, extra: JsonObjectBuilder.() -> Unit = {}): HLMessageRecord {
        val data = buildJsonObject { turn?.let { put("turn", it) }; put("step", step); extra() }
        val raw = buildJsonObject { put("type", type); put("seq", seq); put("time", seq * 1000); put("data", data) }
        return HLMessageRecord("e$seq", "session", if (type == "user/message") "user" else "event", "", seq, raw = raw)
    }
    private fun assistant(seq: Long, step: Long, thought: String = "", text: String = "", turn: Long = 1, tool: String? = null, live: Boolean = false): HLMessageRecord {
        val blocks = buildList {
            if (thought.isNotEmpty()) add(HLAssistantBlock(0, "reasoning", thought, streaming = live))
            if (text.isNotEmpty()) add(HLAssistantBlock(1, "text", text, streaming = live))
            if (tool != null) add(HLAssistantBlock(2, "tool-call", toolCallId = tool, name = "read", arguments = "{}", streaming = live))
        }
        val raw = if (live) buildJsonObject { put("turn", turn); put("step", step) }
            else event(seq, "assistant/message", turn, step).raw
        return HLMessageRecord(if (live) "attempt" else "e$seq", "session", "assistant", text, if (live) null else seq, streaming = live, raw = raw, blocks = blocks)
    }
    private fun call(seq: Long, id: String, name: String, turn: Long = 1) = event(seq, "tool/call", turn) { put("callId", id); put("name", name); put("arguments", "{}") }
    private fun result(seq: Long, id: String) = event(seq, "tool/result") { put("message", buildJsonObject { put("toolCallId", id); put("isError", false) }) }
    private fun presentation(turn: Long = 1, status: String = "completed") = buildJsonObject { put("turn", turn); put("status", status); put("durationMs", 9000); put("endSeq", 99) }

    @Test fun twoLevelFoldingKeepsAllReplyTextAndFootersOutsideTheProcess() {
        val records = listOf(event(1, "user/message", null), event(2, "turn/start"),
            assistant(3, 1, "first thought", "intermediate reply", tool = "read-1"), call(4, "read-1", "read"), result(5, "read-1"),
            assistant(6, 2, "second thought", "final answer"), event(7, "turn/end"))
        val t = HLConversationProjector().project(records, mapOf(1L to presentation()), false)
        assertEquals(2, t.groups.size)
        assertEquals(t.rows.size, t.rows.map { it.key }.distinct().size, "Reasoning and tools separated by a reply must have distinct groups")
        val collapsed = visibleConversationRows(t, emptySet(), emptySet())
        assertEquals(listOf("", "intermediate reply", "final answer"), collapsed.mapNotNull { it.message?.text })
        assertTrue(collapsed.any { it.kind == HLConversationRowKind.TURN_ACTIONS })
        assertFalse(collapsed.any { it.process || it.kind == HLConversationRowKind.PROCESS_HEADER })
        val outer = visibleConversationRows(t, setOf("turn:1"), emptySet())
        assertEquals(2, outer.count { it.kind == HLConversationRowKind.PROCESS_HEADER })
        assertFalse(outer.any { it.process })
        val toolGroup = t.groups.values.single { it.title == "已读取文件" }
        val opened = visibleConversationRows(t, setOf("turn:1"), setOf(toolGroup.key))
        assertEquals(2, opened.count { it.process })
        assertEquals(1, opened.count { it.message?.raw?.string("type") == "tool/call" })
        assertNotNull(opened.single { it.toolResult != null }.toolResult)
    }

    @Test fun liveToDurableAndTokenUpdatesKeepTheGroupIdentityAndDoNotDuplicateTools() {
        val projector = HLConversationProjector()
        val start = event(1, "turn/start")
        val a = projector.project(listOf(start, assistant(2, 1, "thinking", tool = "c", live = true)), emptyMap(), true)
        val key = a.groups.keys.single()
        val b = projector.project(listOf(start, assistant(2, 1, "thinking more", tool = "c", live = true)), emptyMap(), true)
        assertEquals(key, b.groups.keys.single())
        assertFalse(visibleConversationRows(b, emptySet(), emptySet()).any { it.process })
        val c = projector.project(listOf(start, assistant(2, 1, "thinking more", tool = "c"), call(3, "c", "read"), result(4, "c"),
            assistant(5, 2, "done", "answer"), event(6, "turn/end")), mapOf(1L to presentation()), false)
        assertEquals(key, c.groups.keys.single())
        assertEquals(1, c.rows.count { it.message?.raw?.string("type") == "tool/call" })
        assertEquals("answer", visibleConversationRows(c, emptySet(), emptySet()).mapNotNull { it.message }.single().text)
    }

    @Test fun groupSummaryUsesDistinctCallCategoriesRankedLikeTheWeb() {
        val t = HLConversationProjector().project(listOf(event(1, "turn/start"), assistant(2, 1, "inspect"),
            call(3, "a", "exec_command"), result(4, "a"), call(5, "b", "read"), result(6, "b"),
            call(7, "c", "exec_command"), result(8, "c"), assistant(9, 2, text = "answer"), event(10, "turn/end")), mapOf(1L to presentation()), false)
        assertEquals("执行了命令并已读取文件", t.groups.values.single().title)
    }

    @Test fun pagingEarlierMembersExtendsTheGroupWithoutLosingItsIdentity() {
        val projector = HLConversationProjector()
        val tail = listOf(call(4, "c", "read"), result(5, "c"), assistant(6, 2, text = "answer"), event(7, "turn/end"))
        val first = projector.project(tail, mapOf(1L to presentation()), false)
        val full = projector.project(listOf(event(1, "turn/start"), assistant(3, 1, "earlier")) + tail, mapOf(1L to presentation()), false)
        assertEquals(first.groups.keys, full.groups.keys)
    }

    @Test fun turnsDoNotShareDisclosureAndFailureOrInterleavedInputCannotBeHidden() {
        val t = HLConversationProjector().project(listOf(event(1, "turn/start"), assistant(2, 1, "one"), assistant(3, 2, text = "first answer"), event(4, "turn/end"),
            event(5, "turn/start", 2), assistant(6, 1, "two", turn = 2), event(7, "user/message", 2), event(8, "turn/error", 2), event(9, "turn/end", 2)),
            mapOf(1L to presentation(), 2L to presentation(2, "failed")), false)
        assertTrue(t.turns.getValue("turn:1").foldable)
        assertFalse(t.turns.getValue("turn:2").foldable)
        val visible = visibleConversationRows(t, emptySet(), emptySet())
        assertTrue(visible.any { it.message?.raw?.string("type") == "turn/error" })
        assertTrue(visible.any { it.message?.role == "user" })
        assertEquals(listOf("turn:2"), visible.filter { it.kind == HLConversationRowKind.PROCESS_HEADER }.map { it.turnKey })
    }

    @Test fun partialHistoryDoesNotInventDurationAndPlainTextHasNoFoldToggle() {
        val t = HLConversationProjector().project(listOf(assistant(5, 2, text = "answer"), event(6, "turn/end")), emptyMap(), false)
        assertFalse(t.turns.values.single().foldable)
        assertNull(t.turns.values.single().presentation?.get("durationMs"))
        assertTrue(t.rows.single { it.message?.role == "assistant" }.copyReply)
    }
}
