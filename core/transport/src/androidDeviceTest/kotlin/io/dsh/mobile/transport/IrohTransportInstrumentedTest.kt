package io.dsh.mobile.transport

import androidx.test.platform.app.InstrumentationRegistry
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.EndpointTicket
import computer.iroh.IrohAndroid
import computer.iroh.presetMinimal
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Runs two actual Rust/QUIC endpoints in Android, including the production mobile adapter. */
class IrohTransportInstrumentedTest {
    @Test fun roundTripStreamsIdentityAndCancellation() = runBlocking {
        withTimeout(30_000) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            IrohAndroid.installAndroidContext(context)
            val server = Endpoint.bind(EndpointOptions(preset = presetMinimal(), bindAddr = "127.0.0.1:0", alpns = listOf(MOBILE_ALPN.encodeToByteArray())))
            try {
                val id = server.id().use { it.toString() }
                val ticket = server.addr().use { EndpointTicket.fromAddr(it).use { value -> value.toString() } }
                val accepting = async { server.acceptNext()!!.use { incoming -> incoming.accept().use { it.connect() } } }
                val phone = AndroidIrohTransport(context).connect(PeerDescriptor(id, ticket), ByteArray(32) { (it + 1).toByte() })
                val host = accepting.await()
                try {
                    assertEquals(id, phone.remoteEndpointId)
                    val expected = ByteArray(65_536) { (it % 251).toByte() }
                    val serverEcho = async {
                        val stream = host.acceptBi()
                        try {
                            val received = stream.recv().use { it.readExact(expected.size.toUInt()) }
                            stream.send().use { it.writeAll(received); it.finish() }
                        } finally { stream.destroy() }
                    }
                    val rpc = phone.openBi()
                    rpc.writeAll(expected)
                    assertContentEquals(expected, rpc.readExact(expected.size))
                    serverEcho.await()
                    rpc.close()

                    // Server-opened streams carry events and must work independently of RPC.
                    val sendingEvent = async {
                        host.openBi().use { stream ->
                            stream.send().use { it.writeAll("event".encodeToByteArray()); it.finish() }
                        }
                    }
                    val events = phone.acceptBi()
                    assertContentEquals("event".encodeToByteArray(), events.readExact(5))
                    events.close()
                    sendingEvent.await()

                    // Cancellation releases a pending native read instead of leaving a Rust future alive.
                    val stalled = phone.openBi()
                    stalled.writeAll(byteArrayOf(1))
                    val reader = launch(start = CoroutineStart.UNDISPATCHED) { stalled.readExact(1) }
                    reader.cancelAndJoin()
                    stalled.close()
                } finally {
                    phone.close()
                    host.close(0L, byteArrayOf())
                    host.destroy()
                }
            } finally {
                server.shutdown()
                server.destroy()
            }
        }
    }

    @Test fun rejectsTicketForDifferentDesktop() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        IrohAndroid.installAndroidContext(context)
        val one = Endpoint.bind(EndpointOptions(preset = presetMinimal()))
        val two = Endpoint.bind(EndpointOptions(preset = presetMinimal()))
        try {
            val id = one.id().use { it.toString() }
            val wrongTicket = two.addr().use { EndpointTicket.fromAddr(it).use { t -> t.toString() } }
            assertFailsWith<IllegalArgumentException> {
                AndroidIrohTransport(context).connect(PeerDescriptor(id, wrongTicket), ByteArray(32) { 7 })
            }
        } finally {
            one.shutdown(); two.shutdown(); one.destroy(); two.destroy()
        }
    }
}
