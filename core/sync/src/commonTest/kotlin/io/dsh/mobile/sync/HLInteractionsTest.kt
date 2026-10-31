package io.dsh.mobile.sync

import io.dsh.mobile.model.*
import io.dsh.mobile.protocol.WireJson
import kotlinx.serialization.json.*
import kotlin.test.*

class HLInteractionsTest {
    private fun obj(s: String) = WireJson.parseToJsonElement(s).jsonObject
    private fun request(id: String = "e", session: String = "s", call: String = "call") = obj("""{"type":"waterfall","event":"user-questions/request","eventId":"$id","agentId":"$session","request":{"questions":[{"id":"q","question":"Choose","options":[{"label":"A"}]}],"wait":{"callId":"$call","timed":true}}}""")

    @Test fun generationsCancelAndLateClaimFramesCannotResurrectRequests() {
        val r = HLInteractionReducer()
        r.accept(obj("""{"type":"ready","clientId":"first"}""")); r.accept(request())
        assertEquals("first", r.snapshot(0).single().clientId)
        r.accept(obj("""{"type":"cancel","eventId":"e"}""")); r.waiting("e", 5000, 0)
        assertTrue(r.snapshot(0).isEmpty())
        r.accept(request()); r.accept(obj("""{"type":"ready","clientId":"second"}"""))
        assertTrue(r.snapshot(0).isEmpty())
        r.accept(request("new")); assertEquals("second", r.snapshot(0).single().clientId)
        r.reset(); assertNull(r.clientId); assertFalse(r.hasClocks())
    }

    @Test fun focusBeforeHostClaimFreezesBudgetAndRepeatedBlurDoesNotExtendIt() {
        val r = HLInteractionReducer()
        r.accept(obj("""{"type":"ready","clientId":"c"}""")); r.accept(request())
        r.focus("e", true, 0); r.waiting("e", 5000, 20)
        assertEquals(5000, r.snapshot(10000).single().timing?.remainingMs)
        assertTrue(r.expired(10000).isEmpty())
        r.focus("e", false, 10000)
        assertEquals(4000, r.snapshot(11000).single().timing?.remainingMs)
        r.focus("e", false, 11000)
        assertEquals(3000, r.snapshot(12000).single().timing?.remainingMs)
        assertEquals(1, r.expired(15000).size)
        assertTrue(r.expired(16000).isEmpty())
    }

    @Test fun editingAndExplicitExtraTimeKeepTheRequestUntilTheHostSettlesIt() {
        val r = HLInteractionReducer()
        r.accept(obj("""{"type":"ready","clientId":"c"}""")); r.accept(request())
        r.waiting("e", 5000, 0); r.engage("e", 1000)
        r.focus("e", false, 2000)
        assertTrue(r.expired(100000).isEmpty())
        assertTrue(r.snapshot(100000).single().timing!!.paused)
        r.takeTime("e", 2000); assertNull(r.snapshot(100000).single().timing!!.remainingMs)
        r.accept(obj("""{"type":"cancel","eventId":"e"}""")); assertFalse(r.hasClocks())
    }

    @Test fun projectionsFromOtherSessionsRemainAnswerableAndStaleBaselinesCannotReopenThem() {
        val r = HLProjectionReducer()
        fun frame(id: String, seq: Int, active: String) = obj("""{"type":"projection","sessionId":"$id","key":"userQuestions","seq":$seq,"value":{"active":$active}}""")
        val question = """[{"callId":"same","state":"continued","questions":[{"id":"q","question":"Reply"}]}]"""
        r.control(frame("one", 10, question)); r.control(frame("two", 20, question))
        assertEquals(setOf("one", "two"), r.pendingQuestions().map { it.sessionId }.toSet())
        r.control(frame("one", 11, "[]"))
        r.baseline("one", obj("""{"asOfSeq":10,"values":{"userQuestions":{"active":$question}}}"""))
        assertEquals(listOf("two"), r.pendingQuestions().map { it.sessionId })
    }

    @Test fun liveAndContinuedCardsDeduplicateBySessionAndCallRatherThanVisibleConversation() {
        val r = HLInteractionReducer(); r.accept(obj("""{"type":"ready","clientId":"c"}""")); r.accept(request())
        val q = obj("""{"id":"q","question":"Choose"}""")
        val state = HLState(activeHostId = "desktop", selectedSessionId = "elsewhere", interactions = r.snapshot(0), pendingQuestions = listOf(
            HLPendingQuestion("s", "call", "open", listOf(q)), HLPendingQuestion("other", "call", "continued", listOf(q))))
        val prompts = interactionPrompts(state)
        assertEquals(2, prompts.size); assertEquals("e", prompts.first().eventId)
        assertTrue(prompts.last().continued)
        assertEquals(1, interactionPrompts(state.copy(resolvingQuestions = setOf(questionKey("s", "call")))).size)
        assertTrue(interactionPrompts(state.copy(activeHostId = null)).isEmpty())
    }

    @Test fun approvalContextMatchesTheOriginalCallAndNotAnotherToolsArguments() {
        val records = listOf(obj("""{"type":"tool/result","data":{"callId":"requested","arguments":{"command":"forged"}}}"""), obj("""{"type":"tool/call","data":{"callId":"other","arguments":{"command":"other"}}}"""), obj("""{"type":"tool/call","data":{"callId":"requested","arguments":{"command":"safe"}}}"""))
        assertEquals("safe", interactionToolContext(records, "requested")!!.obj("arguments")!!.string("command"))
        assertNull(interactionToolContext(records, "missing"))
    }
}
