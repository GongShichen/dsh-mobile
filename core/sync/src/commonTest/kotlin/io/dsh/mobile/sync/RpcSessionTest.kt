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
class RpcSessionTest {
    @Test fun interleavedNoticesAndOutOfOrderResponsesKeepRequestIdentity() = runTest {
        val (clientRpc, serverRpc) = bytePipe()
        val peer = TestPeer(clientRpc)
        val notices = mutableListOf<String>()
        val events = mutableListOf<SubscriptionEvent>()
        val failures = mutableListOf<Throwable>()
        val session = RpcSession.open(peer, this, events::add, failures::add, notices::add, StandardTestDispatcher(testScheduler))
        assertEquals("rpc", FrameCodec.read(serverRpc::readExact).jsonObject.string("kind"))

        val first = async { session.call("hello") }
        val second = async { session.call("sessions.list") }
        val requestOne = FrameCodec.read(serverRpc::readExact).jsonObject
        val requestTwo = FrameCodec.read(serverRpc::readExact).jsonObject
        assertNotEquals(requestOne.string("id"), requestTwo.string("id"))
        serverRpc.writeAll(FrameCodec.encode(buildJsonObject {
            put("v", 1); put("id", requestTwo.string("id")); put("result", buildJsonObject { put("method", requestTwo.string("method")) })
        }))
        serverRpc.writeAll(FrameCodec.encode(buildJsonObject {
            put("v", 1); put("id", requestOne.string("id")); put("result", buildJsonObject { put("method", requestOne.string("method")) })
        }))
        assertEquals("hello", first.await().jsonObject.string("method"))
        assertEquals("sessions.list", second.await().jsonObject.string("method"))

        val (clientEvents, serverEvents) = bytePipe()
        peer.incoming.send(clientEvents)
        serverEvents.writeAll(FrameCodec.encode(streamHeader("events")))
        serverEvents.writeAll(FrameCodec.encode(buildJsonObject { put("v", 1); put("type", "notice"); put("name", "push-configuration-changed") }))
        serverEvents.writeAll(FrameCodec.encode(WireJson.encodeToJsonElement(SubscriptionEvent.serializer(), SubscriptionEvent(subscriptionId = "s", generation = "g", event = JsonObject(emptyMap())))))
        runCurrent()
        assertEquals(listOf("push-configuration-changed"), notices)
        assertEquals("s", events.single().subscriptionId)
        assertTrue(failures.isEmpty())
        session.close()
    }

    private class TestPeer(private val control: PeerStream) : PeerConnection {
        override val remoteEndpointId = "test-peer"
        override val path = MutableStateFlow(PeerPath.DIRECT)
        val incoming = Channel<PeerStream>(Channel.UNLIMITED)
        override suspend fun openBi() = control
        override suspend fun acceptBi() = incoming.receive()
        override suspend fun close() { incoming.close(); control.close() }
    }

    private class ByteStream(private val input: Channel<Byte>, private val output: Channel<Byte>) : PeerStream {
        override suspend fun readExact(count: Int): ByteArray = ByteArray(count) { input.receive() }
        override suspend fun writeAll(bytes: ByteArray) { bytes.forEach { output.send(it) } }
        override suspend fun finish() { output.close() }
        override suspend fun close() { input.close(); output.close() }
    }
    private fun bytePipe(): Pair<PeerStream, PeerStream> {
        val forward = Channel<Byte>(Channel.UNLIMITED)
        val backward = Channel<Byte>(Channel.UNLIMITED)
        return ByteStream(backward, forward) to ByteStream(forward, backward)
    }
}
