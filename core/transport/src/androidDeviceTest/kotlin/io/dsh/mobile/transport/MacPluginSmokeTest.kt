package io.dsh.mobile.transport

import androidx.test.platform.app.InstrumentationRegistry
import io.dsh.mobile.protocol.FrameCodec
import io.dsh.mobile.protocol.PairingInvitation
import io.dsh.mobile.protocol.WireJson
import io.dsh.mobile.protocol.streamHeader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Opt-in only: scripts/smoke-mac-android.mjs supplies a short-lived invitation privately. */
class MacPluginSmokeTest {
    @Test fun realMacPairCreateFollowAndReconnect() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("dshMacSmoke") == "1")
        runBlocking {
            withTimeout(120_000) {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val configFile = File(instrumentation.context.filesDir, "mac-smoke.json")
                val config = WireJson.parseToJsonElement(configFile.readText()).jsonObject
                configFile.delete()
                val invitation = PairingInvitation.parse(config.getValue("pairingUri").jsonPrimitive.content, System.currentTimeMillis())
                val phoneName = config.getValue("name").jsonPrimitive.content
                val workdir = config.getValue("workdir").jsonPrimitive.content
                val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
                val peer = PeerDescriptor(invitation.endpointId, invitation.ticket)
                val transport = AndroidIrohTransport(instrumentation.targetContext)
                val checks = mutableListOf<String>()
                val paths = mutableListOf<String>()
                var createdSession = ""
                var firstHostInstance = ""
                val first = transport.connect(peer, seed)
                try {
                    assertEquals(invitation.endpointId, first.remoteEndpointId)
                    val rpc = first.openBi()
                    rpc.writeAll(FrameCodec.encode(streamHeader("rpc")))
                    val before = call(rpc, "hello")
                    assertFalse(before.getValue("authorized").jsonPrimitive.boolean)
                    val pending = call(rpc, "pairing.request", buildJsonObject {
                        put("pairingId", invitation.pairingId); put("secret", invitation.secret)
                        put("name", phoneName); put("platform", "android")
                    })
                    val requestId = pending.getValue("requestId").jsonPrimitive.content
                    withTimeout(45_000) {
                        while (true) {
                            val result = call(rpc, "pairing.status", buildJsonObject { put("requestId", requestId) })
                            val status = result.getValue("status").jsonPrimitive.content
                            if (status == "approved") break
                            check(status == "pending") { "Desktop did not approve this test device" }
                            delay(200)
                        }
                    }
                    checks += "desktop-confirmed-pairing"
                    val incomingEvents = async { eventStream(first) }
                    val hello = call(rpc, "hello")
                    assertTrue(hello.getValue("authorized").jsonPrimitive.boolean)
                    firstHostInstance = hello.getValue("hostInstanceId").jsonPrimitive.content
                    checks += "authenticated-hello"
                    paths += withTimeout(10_000) { first.path.first { it == PeerPath.DIRECT || it == PeerPath.RELAY }.name }

                    call(rpc, "sessions.list")
                    checks += "sessions.list"
                    createdSession = call(rpc, "sessions.create", buildJsonObject {
                        put("operationId", id()); put("cwd", workdir)
                    }).getValue("sessionId").jsonPrimitive.content
                    checks += "sessions.create"
                    val renamed = call(rpc, "sessions.rename", buildJsonObject {
                        put("operationId", id()); put("sessionId", createdSession); put("title", "Android native Mac smoke")
                    })
                    assertEquals("Android native Mac smoke", renamed.getValue("title").jsonPrimitive.content)
                    checks += "sessions.rename"
                    val state = call(rpc, "sessions.state", buildJsonObject { put("sessionId", createdSession) })
                    assertFalse(state.getValue("running").jsonPrimitive.boolean)
                    checks += "sessions.state"
                    val sub = subscribeSession(rpc, createdSession)
                    val events = incomingEvents.await()
                    val snapshot = FrameCodec.read(events::readExact).jsonObject
                    assertEquals(sub.getValue("subscriptionId"), snapshot["subscriptionId"])
                    assertEquals(sub.getValue("generation"), snapshot["generation"])
                    assertEquals("snapshot", snapshot.getValue("event").jsonObject.getValue("type").jsonPrimitive.content)
                    checks += "session.follow.snapshot"
                    call(rpc, "unsubscribe", buildJsonObject { put("subscriptionId", sub.getValue("subscriptionId")) })
                } finally { withContext(NonCancellable) { first.close() } }

                // Rebind a fresh native endpoint with the SAME installation identity; no QR or secret is reused.
                val resumed = transport.connect(PeerDescriptor(invitation.endpointId), seed)
                try {
                    val rpc = resumed.openBi()
                    rpc.writeAll(FrameCodec.encode(streamHeader("rpc")))
                    val incomingEvents = async { eventStream(resumed) }
                    val hello = call(rpc, "hello")
                    assertTrue(hello.getValue("authorized").jsonPrimitive.boolean)
                    assertEquals(firstHostInstance, hello.getValue("hostInstanceId").jsonPrimitive.content)
                    checks += "persistent-identity-reconnect-without-invitation"
                    paths += withTimeout(10_000) { resumed.path.first { it == PeerPath.DIRECT || it == PeerPath.RELAY }.name }
                    val list = call(rpc, "sessions.list")
                    assertTrue(list.getValue("items").jsonArray.any { it.jsonObject["sessionId"]?.jsonPrimitive?.content == createdSession })
                    checks += "reconnect-session-visible"
                    val sub = subscribeSession(rpc, createdSession)
                    val events = incomingEvents.await()
                    val snapshot = FrameCodec.read(events::readExact).jsonObject
                    assertEquals(sub["generation"], snapshot["generation"])
                    assertEquals("snapshot", snapshot.getValue("event").jsonObject.getValue("type").jsonPrimitive.content)
                    checks += "reconnect-fresh-follow-baseline"
                    call(rpc, "unsubscribe", buildJsonObject { put("subscriptionId", sub.getValue("subscriptionId")) })
                    call(rpc, "sessions.archive", buildJsonObject { put("operationId", id()); put("sessionId", createdSession) })
                    val output = buildJsonObject {
                        put("ok", true); put("platform", "android-emulator-to-macos-plugin")
                        put("checks", JsonArray(checks.map(::JsonPrimitive)))
                        put("paths", JsonArray(paths.map(::JsonPrimitive)))
                        put("sessionId", createdSession)
                    }
                    File(instrumentation.context.filesDir, "mac-smoke-result.json").writeText(output.toString())
                } finally { withContext(NonCancellable) { resumed.close(); seed.fill(0) } }
            }
        }
    }

    private fun id() = UUID.randomUUID().toString()
    private suspend fun eventStream(connection: PeerConnection): PeerStream {
        val stream = connection.acceptBi()
        val header = FrameCodec.read(stream::readExact).jsonObject
        assertEquals("events", header.getValue("kind").jsonPrimitive.content)
        return stream
    }
    private suspend fun subscribeSession(rpc: PeerStream, sessionId: String) = call(rpc, "subscribe", buildJsonObject {
        put("topic", "session")
        put("request", buildJsonObject { put("sessionId", sessionId); put("activate", false) })
    })
    private suspend fun call(stream: PeerStream, method: String, params: JsonObject = buildJsonObject {}): JsonObject {
        val requestId = id()
        stream.writeAll(FrameCodec.encode(buildJsonObject {
            put("v", 1); put("id", requestId); put("method", method); put("params", params)
        }))
        val response = FrameCodec.read(stream::readExact).jsonObject
        assertEquals(requestId, response.getValue("id").jsonPrimitive.content)
        response["error"]?.let { error("RPC $method failed: ${it.jsonObject["code"]}") }
        val result = response.getValue("result")
        return if (result == JsonNull) buildJsonObject {} else result.jsonObject
    }
}
