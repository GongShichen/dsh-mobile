package io.dsh.mobile.sync

import io.dsh.mobile.model.AssistantPhase
import io.dsh.mobile.protocol.WireJson
import kotlinx.serialization.json.*
import kotlin.test.*

class AssistantPresentationTest {
    private fun obj(value: String) = WireJson.parseToJsonElement(value).jsonObject
    @Test fun liveReasoningAndTextKeepIndependentBlocksAndCanonicalEndDoesNotDuplicate() {
        val a = AssistantBlockAccumulator()
        a.apply(obj("""{"type":"block-start","index":0,"blockType":"reasoning"}"""))
        a.apply(obj("""{"type":"reasoning-delta","index":0,"text":"先检查"}"""))
        a.apply(obj("""{"type":"reasoning-delta","index":0,"text":"结果。"}"""))
        assertEquals(AssistantPhase.THINKING, a.phase)
        assertEquals("", a.text)
        assertEquals("先检查结果。", a.blocks.single().text)
        a.apply(obj("""{"type":"block-end","index":0,"block":{"type":"reasoning","text":"先检查结果。"}}"""))
        a.apply(obj("""{"type":"block-start","index":1,"blockType":"text"}"""))
        a.apply(obj("""{"type":"text-delta","index":1,"text":"答案"}"""))
        assertEquals(AssistantPhase.WRITING, a.phase)
        assertFalse(a.blocks[0].streaming)
        a.apply(obj("""{"type":"block-end","index":1,"block":{"type":"text","text":"答案"}}"""))
        assertEquals("答案", a.text)
        assertEquals(2, a.blocks.size)
    }
    @Test fun reconnectCompactSnapshotRestoresReasoningToolArgumentsAndNextDelta() {
        val a = AssistantBlockAccumulator()
        a.restore(WireJson.parseToJsonElement("""[
          {"type":"reasoning-chunks","index":0,"texts":["检查","中"]},
          {"type":"chunk","chunk":{"type":"block-end","index":0,"block":{"type":"reasoning","text":"检查中"}}},
          {"type":"tool-call-chunks","index":1,"id":"c1","name":"read","args":["{","\"path\":"]}
        ]""").jsonArray)
        assertEquals("检查中", a.blocks[0].text)
        assertFalse(a.blocks[0].streaming)
        assertEquals(AssistantPhase.TOOL_CALL, a.phase)
        a.apply(obj("""{"type":"tool-call-delta","index":1,"argumentsDelta":"\"a\"}"}"""))
        assertEquals("{\"path\":\"a\"}", a.blocks[1].arguments)
    }
    @Test fun reasoningSignaturesAndCollapsedSummariesMatchWebPolicy() {
        assertEquals("", reasoningDisplayText("<sign"))
        assertEquals("", reasoningDisplayText("<signature>opaque"))
        assertEquals("visible", reasoningDisplayText("<signature>opaque</signature>\r\nvisible"))
        assertEquals("", reasoningSummary("unfinished paragraph", true))
        assertEquals("Second", reasoningSummary("**First**\nbody\n\nSecond\nmore", true))
        assertEquals("First", reasoningSummary("**First**\nbody\n\nSecond\nmore", false))
    }
    @Test fun pagingEarlierStepInSameTurnDoesNotEraseActiveThinking() {
        val r = SessionReducer("s")
        r.apply(obj("""{"type":"assistant-stream","frame":{"type":"start","attemptId":"a","revision":1,"turn":1,"step":2,"startedAfterSeq":20}}"""))
        r.apply(obj("""{"type":"assistant-stream","frame":{"type":"chunk","attemptId":"a","revision":2,"index":0,"chunk":{"type":"reasoning-delta","index":0,"text":"still thinking"}}}"""))
        r.prependHistory(obj("""{"records":[{"type":"event","event":{"type":"assistant/message","seq":10,"data":{"turn":1,"step":1,"message":{"content":[{"type":"text","text":"old"}]}}}}],"hasMore":false}"""))
        assertEquals("still thinking", r.messages.single { it.streaming }.blocks.single().text)
    }
}
