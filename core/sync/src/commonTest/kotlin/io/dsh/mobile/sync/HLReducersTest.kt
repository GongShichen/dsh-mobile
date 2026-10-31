package io.dsh.mobile.sync

import io.dsh.mobile.model.*
import io.dsh.mobile.protocol.*
import kotlin.test.*
import kotlinx.serialization.json.*

class HLReducersTest {
    private fun obj(value: String) = WireJson.parseToJsonElement(value).jsonObject

    @Test fun workspaceBaselineAndIncrementalPinsArchivesStayAuthoritative() {
        val workspace = HLWorkspaceReducer()
        val sessions = listOf(HLSessionSummary("s1", "One"), HLSessionSummary("s2", "Two"))
        workspace.apply(obj("""{"type":"baseline","value":{"items":[{"workspaceId":"w1","sessionIds":["s1"]}],"archivedSessionIds":["s2"],"pinnedSessionIds":[]}}"""))
        assertEquals("w1", workspace.decorate(sessions)[0].workspaceId)
        assertTrue(workspace.decorate(sessions)[1].archived)
        workspace.apply(obj("""{"type":"upsert","workspace":{"workspaceId":"w2","sessionIds":["s2"]}}"""))
        workspace.apply(obj("""{"type":"order","workspaceIds":["w2","w1"]}"""))
        assertEquals("w2", workspace.items.first().string("workspaceId"))
        workspace.apply(obj("""{"type":"pinned","pinnedSessionIds":["s1"]}"""))
        workspace.apply(obj("""{"type":"archived","archivedSessionIds":[]}"""))
        assertTrue(workspace.decorate(sessions)[0].pinned)
        assertFalse(workspace.decorate(sessions)[1].archived)
        workspace.apply(obj("""{"type":"remove","workspaceId":"w1"}"""))
        assertNull(workspace.decorate(sessions)[0].workspaceId)
        workspace.apply(obj("""{"type":"replace","value":{"items":[],"archivedSessionIds":[],"pinnedSessionIds":[]}}"""))
        assertFalse(workspace.decorate(sessions)[0].pinned)
    }

    @Test fun hostWideProjectionUpdatesTargetOnlyTheirSession() {
        val sessions = listOf(HLSessionSummary("s1", "One"), HLSessionSummary("s2", "Two"))
        val baseline = applyTitleProjections(sessions, obj("""{"type":"baseline","value":{"projections":{"s1":{"asOfSeq":4,"values":{"title":"Renamed"}}}}}"""))
        assertEquals(listOf("Renamed", "Two"), baseline.map { it.title })
        val changed = applyTitleProjections(baseline, obj("""{"type":"projection","sessionId":"s2","key":"title","value":"Other","seq":8}"""))
        assertEquals(listOf("Renamed", "Other"), changed.map { it.title })
    }

    @Test fun staleFollowBaselineCannotOverwriteNewerControlProjection() {
        val projections = HLProjectionReducer()
        projections.control(obj("""{"type":"projection","sessionId":"s1","key":"userQuestions","value":{"active":["q1"]},"seq":20}"""))
        projections.baseline("s1", obj("""{"asOfSeq":18,"values":{"userQuestions":{"active":[]},"title":"HLHello"}}"""))
        assertEquals(obj("""{"active":["q1"]}"""), projections.values("s1")?.get("userQuestions"))
        assertEquals("HLHello", projections.values("s1")?.string("title"))
        projections.baseline("s1", obj("""{"asOfSeq":21,"values":{"title":"New"}}"""))
        assertNull(projections.values("s1")?.get("userQuestions"))
        assertNull(projections.values("s2"))
    }

    @Test fun eventsBeforeSubscribeAreBufferedAndOldGenerationIsIgnored() {
        val router = HLSubscriptionRouter()
        val stale = HLSubscriptionEvent(subscriptionId = "s1", generation = "old", event = obj("{}"))
        val fresh = stale.copy(generation = "new", event = obj("{\"type\":\"snapshot\"}"))
        assertNull(router.accept(stale)); assertNull(router.accept(fresh))
        val drained = router.register(HLSubscriptionHandle("s1", "new"), HLSubscriptionTarget("session", "one"))
        assertEquals(listOf(fresh), drained.map { it.event })
        assertNull(router.accept(stale))
        router.remove("s1")
        assertNull(router.accept(fresh))
    }

    @Test fun pendingSubscriptionFloodFailsInsteadOfGrowingForever() {
        val router = HLSubscriptionRouter(maxPendingEvents = 1)
        val event = HLSubscriptionEvent(subscriptionId = "unknown", generation = "one", event = obj("{}"))
        router.accept(event)
        assertFailsWith<HLProtocolException> { router.accept(event) }
    }

    @Test fun aLargeLegalOpeningSnapshotCanArriveBeforeItsSubscribeResponse() {
        val router = HLSubscriptionRouter()
        val payload = buildJsonObject { put("type", "snapshot"); put("context", "x".repeat(9 * 1024 * 1024)) }
        val event = HLSubscriptionEvent(subscriptionId = "large", generation = "first", event = payload)
        assertNull(router.accept(event))
        assertEquals(event, router.register(HLSubscriptionHandle("large", "first"), HLSubscriptionTarget("session", "s")).single().event)
        val limited = HLSubscriptionRouter(maxPendingBytes = 8)
        assertFailsWith<HLProtocolException> { limited.accept(event) }
    }

    @Test fun reconnectBaselineReplacesTransientTextWithoutDuplicateChunks() {
        val reducer = HLSessionReducer("session")
        reducer.apply(obj("""{"type":"snapshot","cursor":{"seq":1},"records":[{"type":"event","event":{"seq":1,"type":"user/message","data":{"content":[{"type":"text","text":"HLHello"}]}}}],"assistantStream":{"revision":4,"activeAttempt":{"attemptId":"attempt","nextIndex":2,"stream":[{"type":"text-chunks","texts":["A","B"]}]}}}"""))
        assertEquals(listOf("HLHello", "AB"), reducer.messages.map { it.text })
        val delta = obj("""{"type":"assistant-stream","frame":{"type":"chunk","attemptId":"attempt","revision":5,"index":2,"chunk":{"type":"text-delta","index":0,"text":"C"}}}""")
        reducer.apply(delta); reducer.apply(delta)
        assertEquals("ABC", reducer.messages.last().text)
        reducer.apply(obj("""{"type":"event","event":{"seq":2,"type":"assistant/message","data":{"message":{"content":[{"type":"text","text":"ABC"}]}}}}"""))
        assertEquals(2, reducer.messages.size)
        assertFalse(reducer.messages.last().streaming)
        assertEquals(2L, reducer.messages.last().sequence)
    }

    @Test fun missingAssistantChunkRequiresResync() {
        val reducer = HLSessionReducer("session")
        reducer.apply(obj("""{"type":"assistant-stream","frame":{"type":"start","attemptId":"a","revision":1}}"""))
        assertFailsWith<HLProtocolException> {
            reducer.apply(obj("""{"type":"assistant-stream","frame":{"type":"chunk","attemptId":"a","revision":3,"index":1,"chunk":{"type":"text-delta","text":"lost"}}}"""))
        }
    }

    @Test fun historicalPagesMergeByDurableSequence() {
        val reducer = HLSessionReducer("session")
        val page = obj("""{"records":[{"type":"event","event":{"seq":3,"type":"user/message","data":{"content":[{"type":"text","text":"Hi"}]}}}],"hasMore":true}""")
        reducer.prependHistory(page); reducer.prependHistory(page)
        assertEquals(1, reducer.messages.size)
        assertTrue(reducer.hasMore)
    }

    @Test fun disconnectedMutationIsUncertainAndNeverAutomaticallyReplayed() {
        val queued = HLPendingOperation("op", "host", "sessions.prompt", obj("{\"requestId\":\"persistent\"}"), HLOperationStatus.QUEUED, "persistent")
        assertTrue(HLOperationRecovery.canAutomaticallySend(queued))
        val uncertain = HLOperationRecovery.afterConnectionLoss(queued.copy(status = HLOperationStatus.DISPATCHED))
        assertEquals(HLOperationStatus.UNKNOWN, uncertain.status)
        assertEquals("persistent", uncertain.requestId)
        assertFalse(HLOperationRecovery.canAutomaticallySend(uncertain))
        assertTrue(HLOperationRecovery.canExplicitlyRetry(uncertain))
        assertFalse(HLOperationRecovery.canExplicitlyRetry(uncertain.copy(method = "jobs.kill")))
        assertFalse(HLOperationRecovery.canExplicitlyRetry(uncertain.copy(requestId = null)))
        assertFalse(HLOperationRecovery.canAutomaticallySend(queued.copy(status = HLOperationStatus.CONFIRMED)))
    }
}
