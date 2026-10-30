package io.dsh.mobile.sync

import io.dsh.mobile.model.*
import io.dsh.mobile.protocol.*
import kotlin.test.*
import kotlinx.serialization.json.*

class ReducersTest {
    private fun obj(value: String) = WireJson.parseToJsonElement(value).jsonObject

    @Test fun workspaceBaselineAndIncrementalPinsArchivesStayAuthoritative() {
        val workspace = WorkspaceReducer()
        val sessions = listOf(SessionSummary("s1", "One"), SessionSummary("s2", "Two"))
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
        val sessions = listOf(SessionSummary("s1", "One"), SessionSummary("s2", "Two"))
        val baseline = applyTitleProjections(sessions, obj("""{"type":"baseline","value":{"projections":{"s1":{"asOfSeq":4,"values":{"title":"Renamed"}}}}}"""))
        assertEquals(listOf("Renamed", "Two"), baseline.map { it.title })
        val changed = applyTitleProjections(baseline, obj("""{"type":"projection","sessionId":"s2","key":"title","value":"Other","seq":8}"""))
        assertEquals(listOf("Renamed", "Other"), changed.map { it.title })
    }

    @Test fun staleFollowBaselineCannotOverwriteNewerControlProjection() {
        val projections = ProjectionReducer()
        projections.control(obj("""{"type":"projection","sessionId":"s1","key":"userQuestions","value":{"active":["q1"]},"seq":20}"""))
        projections.baseline("s1", obj("""{"asOfSeq":18,"values":{"userQuestions":{"active":[]},"title":"Hello"}}"""))
        assertEquals(obj("""{"active":["q1"]}"""), projections.values("s1")?.get("userQuestions"))
        assertEquals("Hello", projections.values("s1")?.string("title"))
        projections.baseline("s1", obj("""{"asOfSeq":21,"values":{"title":"New"}}"""))
        assertNull(projections.values("s1")?.get("userQuestions"))
        assertNull(projections.values("s2"))
    }

    @Test fun eventsBeforeSubscribeAreBufferedAndOldGenerationIsIgnored() {
        val router = SubscriptionRouter()
        val stale = SubscriptionEvent(subscriptionId = "s1", generation = "old", event = obj("{}"))
        val fresh = stale.copy(generation = "new", event = obj("{\"type\":\"snapshot\"}"))
        assertNull(router.accept(stale)); assertNull(router.accept(fresh))
        val drained = router.register(SubscriptionHandle("s1", "new"), SubscriptionTarget("session", "one"))
        assertEquals(listOf(fresh), drained.map { it.event })
        assertNull(router.accept(stale))
        router.remove("s1")
        assertNull(router.accept(fresh))
    }

    @Test fun pendingSubscriptionFloodFailsInsteadOfGrowingForever() {
        val router = SubscriptionRouter(maxPendingEvents = 1)
        val event = SubscriptionEvent(subscriptionId = "unknown", generation = "one", event = obj("{}"))
        router.accept(event)
        assertFailsWith<ProtocolException> { router.accept(event) }
    }

    @Test fun aLargeLegalOpeningSnapshotCanArriveBeforeItsSubscribeResponse() {
        val router = SubscriptionRouter()
        val payload = buildJsonObject { put("type", "snapshot"); put("context", "x".repeat(9 * 1024 * 1024)) }
        val event = SubscriptionEvent(subscriptionId = "large", generation = "first", event = payload)
        assertNull(router.accept(event))
        assertEquals(event, router.register(SubscriptionHandle("large", "first"), SubscriptionTarget("session", "s")).single().event)
        val limited = SubscriptionRouter(maxPendingBytes = 8)
        assertFailsWith<ProtocolException> { limited.accept(event) }
    }

    @Test fun reconnectBaselineReplacesTransientTextWithoutDuplicateChunks() {
        val reducer = SessionReducer("session")
        reducer.apply(obj("""{"type":"snapshot","cursor":{"seq":1},"records":[{"type":"event","event":{"seq":1,"type":"user/message","data":{"content":[{"type":"text","text":"Hello"}]}}}],"assistantStream":{"revision":4,"activeAttempt":{"attemptId":"attempt","nextIndex":2,"stream":[{"type":"text-chunks","texts":["A","B"]}]}}}"""))
        assertEquals(listOf("Hello", "AB"), reducer.messages.map { it.text })
        val delta = obj("""{"type":"assistant-stream","frame":{"type":"chunk","attemptId":"attempt","revision":5,"index":2,"chunk":{"type":"text-delta","index":0,"text":"C"}}}""")
        reducer.apply(delta); reducer.apply(delta)
        assertEquals("ABC", reducer.messages.last().text)
        reducer.apply(obj("""{"type":"event","event":{"seq":2,"type":"assistant/message","data":{"message":{"content":[{"type":"text","text":"ABC"}]}}}}"""))
        assertEquals(2, reducer.messages.size)
        assertFalse(reducer.messages.last().streaming)
        assertEquals(2L, reducer.messages.last().sequence)
    }

    @Test fun missingAssistantChunkRequiresResync() {
        val reducer = SessionReducer("session")
        reducer.apply(obj("""{"type":"assistant-stream","frame":{"type":"start","attemptId":"a","revision":1}}"""))
        assertFailsWith<ProtocolException> {
            reducer.apply(obj("""{"type":"assistant-stream","frame":{"type":"chunk","attemptId":"a","revision":3,"index":1,"chunk":{"type":"text-delta","text":"lost"}}}"""))
        }
    }

    @Test fun historicalPagesMergeByDurableSequence() {
        val reducer = SessionReducer("session")
        val page = obj("""{"records":[{"type":"event","event":{"seq":3,"type":"user/message","data":{"content":[{"type":"text","text":"Hi"}]}}}],"hasMore":true}""")
        reducer.prependHistory(page); reducer.prependHistory(page)
        assertEquals(1, reducer.messages.size)
        assertTrue(reducer.hasMore)
    }

    @Test fun disconnectedMutationIsUncertainAndNeverAutomaticallyReplayed() {
        val queued = PendingOperation("op", "host", "sessions.prompt", obj("{\"requestId\":\"persistent\"}"), OperationStatus.QUEUED, "persistent")
        assertTrue(OperationRecovery.canAutomaticallySend(queued))
        val uncertain = OperationRecovery.afterConnectionLoss(queued.copy(status = OperationStatus.DISPATCHED))
        assertEquals(OperationStatus.UNKNOWN, uncertain.status)
        assertEquals("persistent", uncertain.requestId)
        assertFalse(OperationRecovery.canAutomaticallySend(uncertain))
        assertTrue(OperationRecovery.canExplicitlyRetry(uncertain))
        assertFalse(OperationRecovery.canExplicitlyRetry(uncertain.copy(method = "jobs.kill")))
        assertFalse(OperationRecovery.canExplicitlyRetry(uncertain.copy(requestId = null)))
        assertFalse(OperationRecovery.canAutomaticallySend(queued.copy(status = OperationStatus.CONFIRMED)))
    }
}
