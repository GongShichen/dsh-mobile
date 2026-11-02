package io.dsh.mobile.sync

import io.dsh.mobile.model.*
import io.dsh.mobile.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class HLPersonalRepositoryTest {
    private fun snapshot(revision: Long, profile: String = "a") = WireJson.encodeToJsonElement(
        HLPersonalSnapshot.serializer(), HLPersonalSnapshot(revision = revision, available = true,
            profile = HLPersonalProfile(profile, "我的助理", true, false, 1)))

    @Test fun olderSnapshotsCannotUndoNewerRemoteState() {
        val published = mutableListOf<HLPersonalSnapshot?>()
        val repository = HLPersonalRepository { published += it }
        repository.bind("host-a", 1) { _, _ -> snapshot(0) }
        repository.accept(snapshot(12)); repository.accept(snapshot(11))
        assertEquals(12, repository.snapshot?.revision)
        assertEquals(2, published.size)
        repository.reset(); assertNull(repository.snapshot)
    }

    @Test fun aLateRefreshFromThePreviousComputerIsIgnored() = runTest {
        val pending = CompletableDeferred<JsonElement>()
        val repository = HLPersonalRepository {}
        repository.bind("host-a", 1) { _, _ -> pending.await() }
        val refresh = launch(start = CoroutineStart.UNDISPATCHED) { repository.refresh() }
        repository.bind("host-b", 2) { _, _ -> snapshot(3, "b") }
        repository.refresh(); pending.complete(snapshot(500, "a")); refresh.join()
        assertEquals("b", repository.snapshot?.profile?.id)
        assertEquals(3, repository.snapshot?.revision)
    }

    @Test fun aLateMutationResultCannotNavigateTheNewComputer() = runTest {
        val pending = CompletableDeferred<JsonElement>()
        var oldOperation = ""
        val repository = HLPersonalRepository {}
        repository.bind("host-a", 1) { method, params ->
            assertEquals("personal.createTask", method)
            oldOperation = params.getValue("operationId").jsonPrimitive.content
            pending.await()
        }
        val command = async(start = CoroutineStart.UNDISPATCHED) {
            repository.command("createTask", buildJsonObject { put("operationId", "stable-operation") })
        }
        repository.bind("host-b", 2) { _, _ -> snapshot(1, "b") }
        pending.complete(buildJsonObject { put("id", "old-task") })
        assertFailsWith<CancellationException> { command.await() }
        assertEquals("stable-operation", oldOperation)
        assertNull(repository.snapshot)
    }

    @Test fun viewStateIsIsolatedByComputerModeAndActualSession() {
        val views = HLConversationViewStore()
        val normal = HLConversationViewKey("a", HLConversationMode.NORMAL, "session")
        val personal = normal.copy(mode = HLConversationMode.PERSONAL)
        val other = normal.copy(hostId = "b")
        val saved = HLConversationViewSnapshot("unfinished normal", listOf("receipt" to "report.pdf"),
            8, 16, false, mapOf("turn" to true), mapOf("tool" to true))
        views.save(normal, saved); views.updateDraft(personal, "private draft")
        views.selectNormal("a", "session")
        assertEquals(saved, views.get(normal)); assertEquals("private draft", views.get(personal).draft)
        assertEquals("", views.get(other).draft)
        val created = normal.copy(sessionId = "new-session")
        views.copyIfAbsent(normal, created); views.updateDraft(normal, "changed")
        assertEquals(saved, views.get(created))
        views.removeHost("a"); assertNull(views.normalSession("a")); assertEquals("", views.get(personal).draft)
    }

    @Test fun disconnectKeepsOnlyRamViewAndAReconnectedHostCanReplaceItsRevisionSpace() = runTest {
        val repository = HLPersonalRepository {}
        repository.bind("host-a", 1) { _, _ -> snapshot(500) }; repository.refresh()
        repository.unbind()
        assertEquals(500, repository.snapshot?.revision)
        assertFailsWith<HLProtocolException> { repository.command("createTask") }
        repository.accept(snapshot(600)); assertEquals(500, repository.snapshot?.revision)
        repository.bind("host-a", 2) { _, _ -> snapshot(3, "reset-profile") }
        assertEquals(500, repository.snapshot?.revision)
        repository.refresh(); assertEquals(3, repository.snapshot?.revision)
        assertEquals("reset-profile", repository.snapshot?.profile?.id)
        repository.bind("host-b", 3) { _, _ -> snapshot(1, "b") }
        assertNull(repository.snapshot)
    }
}
