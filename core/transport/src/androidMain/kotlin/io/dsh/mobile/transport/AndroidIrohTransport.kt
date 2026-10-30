package io.dsh.mobile.transport

import android.content.Context
import computer.iroh.BiStream
import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.EndpointId
import computer.iroh.EndpointOptions
import computer.iroh.EndpointTicket
import computer.iroh.IrohAndroid
import computer.iroh.PathChangeCallback
import computer.iroh.PathSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Real QUIC transport. The official N0 preset provides discovery and encrypted relay fallback. */
class AndroidIrohTransport(context: Context) : PeerTransport {
    init { IrohAndroid.installAndroidContext(context.applicationContext) }

    override suspend fun connect(peer: PeerDescriptor, seed: ByteArray): PeerConnection {
        require(seed.size == 32) { "An iroh identity requires a 32-byte seed" }
        val endpoint = Endpoint.bind(EndpointOptions(secretKey = seed, alpns = listOf(MOBILE_ALPN.encodeToByteArray())))
        var connection: Connection? = null
        try {
            val address = EndpointId.fromString(peer.endpointId).use { identity ->
                if (peer.ticket != null) {
                    EndpointTicket.fromString(peer.ticket).use { ticket ->
                        val addr = ticket.endpointAddr()
                        try {
                            addr.id().use { require(it == identity) { "Ticket identity does not match the paired desktop" } }
                            addr
                        } catch (error: Throwable) { addr.destroy(); throw error }
                    }
                } else EndpointAddr(identity, null, emptyList())
            }
            connection = address.use { endpoint.connect(it, MOBILE_ALPN.encodeToByteArray()) }
            val remoteId = connection.remoteId().use { it.toString() }
            require(remoteId == peer.endpointId) { "Desktop identity verification failed" }
            currentCoroutineContext().ensureActive()
            return AndroidPeerConnection(endpoint, connection, remoteId)
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                connection?.close(1L, "connection cancelled".encodeToByteArray())
                runCatching { endpoint.shutdown() }
                connection?.destroy()
                endpoint.destroy()
            }
            throw error
        }
    }
}

private class AndroidPeerConnection(
    private val endpoint: Endpoint,
    private val native: Connection,
    override val remoteEndpointId: String,
) : PeerConnection {
    private val disposed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val streams = ConcurrentHashMap.newKeySet<AndroidPeerStream>()
    private val mutablePath = MutableStateFlow(pathOf(native.paths()))
    override val path = mutablePath.asStateFlow()
    private val watcher = native.watchPaths(object : PathChangeCallback {
        override suspend fun onChange(paths: List<PathSnapshot>) {
            mutablePath.update { if (disposed.get()) PeerPath.CLOSED else pathOf(paths) }
        }
    })
    init { scope.launch { try { native.closed() } finally { close() } } }

    override suspend fun openBi(): PeerStream = wrap(native.openBi())
    override suspend fun acceptBi(): PeerStream = wrap(native.acceptBi())
    private suspend fun wrap(stream: BiStream): PeerStream {
        val wrapped = AndroidPeerStream(stream) { streams.remove(it) }
        streams.add(wrapped)
        if (disposed.get()) { wrapped.close(); error("Connection is closed") }
        return wrapped
    }
    override suspend fun close() {
        if (!disposed.compareAndSet(false, true)) return
        withContext(NonCancellable) {
            mutablePath.value = PeerPath.CLOSED
            native.close(0L, "closed".encodeToByteArray())
            runCatching { watcher.stop() }
            streams.toList().forEach { runCatching { it.close() } }
            runCatching { endpoint.shutdown() }
            watcher.destroy()
            native.destroy()
            endpoint.destroy()
            scope.cancel()
        }
    }
}

private fun pathOf(paths: List<PathSnapshot>): PeerPath {
    val selected = paths.firstOrNull { it.isSelected } ?: return PeerPath.CONNECTING
    return if (selected.isRelay) PeerPath.RELAY else if (selected.isIp) PeerPath.DIRECT else PeerPath.CONNECTING
}

private class AndroidPeerStream(
    private val native: BiStream,
    private val onClose: (AndroidPeerStream) -> Unit,
) : PeerStream {
    private val send = native.send()
    private val receive = native.recv()
    private val writer = Mutex()
    private val reader = Mutex()
    private val disposed = AtomicBoolean(false)
    override suspend fun readExact(count: Int): ByteArray {
        require(count in 0..1_048_576) { "Read size exceeds the protocol frame bound" }
        return cancellable { reader.withLock { receive.readExact(count.toUInt()) } }
    }
    override suspend fun writeAll(bytes: ByteArray) = cancellable { writer.withLock { send.writeAll(bytes) } }
    override suspend fun finish() = cancellable { writer.withLock { send.finish() } }
    private suspend fun <T> cancellable(block: suspend () -> T): T {
        check(!disposed.get()) { "Stream is closed" }
        try { return block() } catch (cancelled: CancellationException) { close(); throw cancelled }
    }
    override suspend fun close() {
        if (!disposed.compareAndSet(false, true)) return
        withContext(NonCancellable) {
            runCatching { receive.stop(0UL) }
            runCatching { send.reset(0UL) }
            receive.destroy()
            send.destroy()
            native.destroy()
            onClose(this@AndroidPeerStream)
        }
    }
}
