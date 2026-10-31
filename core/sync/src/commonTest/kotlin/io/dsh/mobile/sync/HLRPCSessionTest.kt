package io.dsh.mobile.sync

import io.dsh.mobile.protocol.*
import io.dsh.mobile.transport.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.serialization.json.*

@OptIn(ExperimentalCoroutinesApi::class)
class HLRPCSessionTest {
    @Test fun interleavedNoticesAndOutOfOrderResponsesKeepRequestIdentity() = runTest {
        val (clientRpc, serverRpc) = bytePipe()
        val peer = HLTestPeer(clientRpc)
        val notices = mutableListOf<String>()
        val events = mutableListOf<HLSubscriptionEvent>()
        val failures = mutableListOf<Throwable>()
        val session = HLRPCSession.open(peer, this, events::add, failures::add, notices::add, StandardTestDispatcher(testScheduler))
        assertEquals("rpc", HLFrameCodec.read(serverRpc::readExact).jsonObject.string("kind"))

        val first = async { session.call("hello") }
        val second = async { session.call("sessions.list") }
        val requestOne = HLFrameCodec.read(serverRpc::readExact).jsonObject
        val requestTwo = HLFrameCodec.read(serverRpc::readExact).jsonObject
        assertNotEquals(requestOne.string("id"), requestTwo.string("id"))
        serverRpc.writeAll(HLFrameCodec.encode(buildJsonObject {
            put("v", 1); put("id", requestTwo.string("id")); put("result", buildJsonObject { put("method", requestTwo.string("method")) })
        }))
        serverRpc.writeAll(HLFrameCodec.encode(buildJsonObject {
            put("v", 1); put("id", requestOne.string("id")); put("result", buildJsonObject { put("method", requestOne.string("method")) })
        }))
        assertEquals("hello", first.await().jsonObject.string("method"))
        assertEquals("sessions.list", second.await().jsonObject.string("method"))

        val (clientEvents, serverEvents) = bytePipe()
        peer.incoming.send(clientEvents)
        serverEvents.writeAll(HLFrameCodec.encode(streamHeader("events")))
        serverEvents.writeAll(HLFrameCodec.encode(buildJsonObject { put("v", 1); put("type", "notice"); put("name", "push-configuration-changed") }))
        serverEvents.writeAll(HLFrameCodec.encode(WireJson.encodeToJsonElement(HLSubscriptionEvent.serializer(), HLSubscriptionEvent(subscriptionId = "s", generation = "g", event = JsonObject(emptyMap())))))
        runCurrent()
        assertEquals(listOf("push-configuration-changed"), notices)
        assertEquals("s", events.single().subscriptionId)
        assertTrue(failures.isEmpty())
        session.close()
    }

    private class HLTestPeer(private val control: HLPeerStream) : HLPeerConnection {
        override val remoteEndpointId = "test-peer"
        override val path = MutableStateFlow(HLPeerPath.DIRECT)
        val incoming = Channel<HLPeerStream>(Channel.UNLIMITED)
        override suspend fun openBi() = control
        override suspend fun acceptBi() = incoming.receive()
        override suspend fun close() { incoming.close(); control.close() }
    }

    private class HLByteStream(private val input: Channel<Byte>, private val output: Channel<Byte>) : HLPeerStream {
        override suspend fun readExact(count: Int): ByteArray = ByteArray(count) { input.receive() }
        override suspend fun writeAll(bytes: ByteArray) { bytes.forEach { output.send(it) } }
        override suspend fun finish() { output.close() }
        override suspend fun close() { input.close(); output.close() }
    }
    private fun bytePipe(): Pair<HLPeerStream, HLPeerStream> {
        val forward = Channel<Byte>(Channel.UNLIMITED)
        val backward = Channel<Byte>(Channel.UNLIMITED)
        return HLByteStream(backward, forward) to HLByteStream(forward, backward)
    }
}
