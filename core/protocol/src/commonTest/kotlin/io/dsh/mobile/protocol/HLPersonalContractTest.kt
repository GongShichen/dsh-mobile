package io.dsh.mobile.protocol

import io.dsh.mobile.model.*
import kotlinx.serialization.json.*
import kotlin.test.*

class HLPersonalContractTest {
    @Test fun canonicalDesktopSnapshotKeepsGoalAndDelegationIdentities() {
        val json = WireJson.parseToJsonElement("""{
            "version":1,"revision":8,"available":true,
            "profile":{"id":"host-profile","name":"Assistant","enabled":true,"paused":false,"revision":1},
            "goals":[{"id":"a","title":"A","objective":"A","state":"active","revision":1,"milestones":[{"id":"m","title":"Review","state":"open"}]},
                {"id":"b","title":"B","objective":"B","state":"active","revision":1}],
            "tasks":[{"id":"work","title":"Continue","state":"waiting","revision":1,
                "target":{"kind":"session-continue","sessionId":"original"},
                "binding":{"sessionId":"original","ownership":"borrowed"},
                "result":{"summary":"Finished execution","verification":"execution-only","sessionId":"original","throughSeq":42}},
                {"id":"next","title":"Next","state":"queued","revision":1,"dependsOn":["work"]}]
        }""")
        val snapshot = WireJson.decodeFromJsonElement(HLPersonalSnapshot.serializer(), json)
        assertEquals(2, snapshot.goals.size)
        assertEquals("Review", snapshot.goals[0].milestones[0].title)
        assertEquals("original", snapshot.tasks[0].binding?.sessionId)
        assertEquals("execution-only", snapshot.tasks[0].result?.verification)
        assertEquals(listOf("work"), snapshot.tasks[1].dependsOn)
        assertEquals(2, snapshot.profile.maxConcurrentTasks)
    }
    @Test fun allDesktopCapabilitiesAreAdmittedExplicitlyAndTargetsRoundTrip() {
        listOf("snapshot", "createTask", "resumeTask", "reconcileRoutine", "authorizeTask", "setTaskAccounts").forEach { HLMethods.requireAllowed("personal.$it") }
        assertFailsWith<HLProtocolException> { HLMethods.requireAllowed("personal.invokeArbitraryNamespace") }
        val targets = listOf(HLExecutionTarget(), HLExecutionTarget("workspace-new", workspaceId = "project", modelSelection = HLPersonalModelSelection("test", "model", "high")),
            HLExecutionTarget("session-continue", sessionId = "original"))
        targets.forEach { target ->
            assertEquals(target, WireJson.decodeFromJsonElement(HLExecutionTarget.serializer(), target.toJson()))
        }
        assertEquals("high", targets[1].toJson()["modelSelection"]?.jsonObject?.get("reasoningEffort")?.jsonPrimitive?.content)
    }
}
