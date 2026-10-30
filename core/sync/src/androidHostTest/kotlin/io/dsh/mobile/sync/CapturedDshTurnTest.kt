package io.dsh.mobile.sync

import io.dsh.mobile.protocol.WireJson
import kotlin.test.*
import kotlinx.serialization.json.*

/** Captured from the real pinned DSH AgentLoop, tool execution and mobile approval flow. */
class CapturedDshTurnTest {
    @Test fun realOfficialTurnConvergesWithoutDuplicateHumanOrAssistantMessages() {
        val fixture = javaClass.getResourceAsStream("/captured-dsh-turn.json")!!.bufferedReader().use { WireJson.parseToJsonElement(it.readText()).jsonObject }
        assertEquals("ce7073d51254c26cd63b22a60835b8f948deb447", fixture.obj("metadata")?.string("sourceCommit"))
        val reducer = SessionReducer(fixture.string("sessionId")!!)
        val visibleDeltas = mutableListOf<String>()
        val reasoningDeltas = mutableListOf<String>()
        val preparingTools = mutableListOf<String>()
        val liveKeys = mutableSetOf<String>()
        for (frame in fixture.array("frames")) {
            reducer.apply(frame.jsonObject)
            reducer.messages.lastOrNull { it.streaming && it.text.isNotEmpty() }?.let { visibleDeltas += it.text }
            reducer.messages.lastOrNull { it.streaming }?.let { message ->
                message.presentationId?.let(liveKeys::add)
                message.blocks.filter { it.kind == "reasoning" && it.text.isNotEmpty() }.forEach { reasoningDeltas += it.text }
                message.blocks.filter { it.kind == "tool-call" }.mapNotNull { it.name }.forEach(preparingTools::add)
            }
        }
        assertEquals(1, reducer.messages.count { it.role == "user" })
        assertEquals(2, reducer.messages.count { it.role == "context" })
        assertEquals(2, reducer.messages.count { it.role == "assistant" })
        assertEquals("Mobile round trip verified.", reducer.messages.last { it.role == "assistant" }.text)
        assertTrue(visibleDeltas.contains("Mobile "))
        assertTrue(visibleDeltas.contains("Mobile round trip "))
        assertTrue(reducer.messages.none { it.streaming })
        assertTrue(reasoningDeltas.contains("Checking the mobile approval and tool flow."))
        assertTrue(reasoningDeltas.contains("The approved tool returned verified data."))
        assertTrue(preparingTools.contains("mobile_smoke_echo"))
        assertTrue(reducer.messages.filter { it.role == "assistant" }.all { it.presentationId in liveKeys })
        assertEquals(listOf("reasoning", "text"), reducer.messages.last { it.role == "assistant" }.blocks.map { it.kind })
        assertTrue(reducer.messages.any { it.raw.string("type") == "tool/call" })
        assertTrue(reducer.messages.any { it.raw.string("type") == "tool/result" })
        val human = reducer.messages.single { it.role == "user" }
        assertEquals("mobile-smoke-request-1", human.raw.obj("data")?.obj("source")?.string("rpcId"))
        assertTrue(fixture.array("interactions").any { it.jsonObject.string("type") == "waterfall" && it.jsonObject.string("event") == "approval/request" })
    }
}
