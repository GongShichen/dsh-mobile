package io.dsh.mobile.transport

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Callback-only boundary exported to Swift. Each operation completes at most once. */
interface NativeCancellation { fun cancel() }
interface NativeConnectCallback {
    fun success(connection: NativePeerConnection)
    fun failure(message: String)
}
interface NativeStreamCallback {
    fun success(stream: NativePeerStream)
    fun failure(message: String)
}
interface NativeBytesCallback {
    fun success(bytes: ByteArray)
    fun failure(message: String)
}
interface NativeUnitCallback {
    fun success()
    fun failure(message: String)
}
interface NativePathCallback { fun changed(path: String) }

interface NativeIrohBridge {
    fun connect(endpointId: String, ticket: String?, seed: ByteArray, callback: NativeConnectCallback): NativeCancellation
}
interface NativePeerConnection {
    fun remoteEndpointId(): String
    fun openBi(callback: NativeStreamCallback): NativeCancellation
    fun acceptBi(callback: NativeStreamCallback): NativeCancellation
    fun watchPath(callback: NativePathCallback): NativeCancellation
    fun close(callback: NativeUnitCallback): NativeCancellation
    /** Starts unconditional cleanup, including any endpoint owned by this connection. */
    fun dispose()
}
interface NativePeerStream {
    fun readExact(count: Int, callback: NativeBytesCallback): NativeCancellation
    fun writeAll(bytes: ByteArray, callback: NativeUnitCallback): NativeCancellation
    fun finish(callback: NativeUnitCallback): NativeCancellation
    fun close(callback: NativeUnitCallback): NativeCancellation
    fun dispose()
}

class BridgePeerTransport(private val bridge: NativeIrohBridge) : PeerTransport {
    override suspend fun connect(peer: PeerDescriptor, seed: ByteArray): PeerConnection {
        require(seed.size == 32) { "An iroh identity requires a 32-byte seed" }
        val native = suspendCancellableCoroutine { continuation ->
            val operation = bridge.connect(peer.endpointId, peer.ticket, seed, object : NativeConnectCallback {
                override fun success(connection: NativePeerConnection) {
                    continuation.resume(connection, onCancellation = { _, value, _ -> value.dispose() })
                }
                override fun failure(message: String) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message))
                }
            })
            continuation.invokeOnCancellation { operation.cancel() }
        }
        if (native.remoteEndpointId() != peer.endpointId) {
            native.dispose()
            error("The connected desktop identity does not match the paired device")
        }
        return BridgeConnection(native)
    }
}

private class BridgeConnection(private val native: NativePeerConnection) : PeerConnection {
    override val remoteEndpointId = native.remoteEndpointId()
    private val mutablePath = MutableStateFlow(PeerPath.CONNECTING)
    override val path = mutablePath.asStateFlow()
    private val pathWatch = native.watchPath(object : NativePathCallback {
        override fun changed(path: String) {
            mutablePath.update { current ->
                if (current == PeerPath.CLOSED) current else PeerPath.entries.firstOrNull { it.name == path } ?: PeerPath.CONNECTING
            }
        }
    })
    override suspend fun openBi(): PeerStream = open(native::openBi)
    override suspend fun acceptBi(): PeerStream = open(native::acceptBi)
    private suspend fun open(start: (NativeStreamCallback) -> NativeCancellation): PeerStream {
        val stream = suspendCancellableCoroutine { continuation ->
            val operation = start(object : NativeStreamCallback {
                override fun success(stream: NativePeerStream) {
                    continuation.resume(stream, onCancellation = { _, value, _ -> value.dispose() })
                }
                override fun failure(message: String) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message))
                }
            })
            continuation.invokeOnCancellation { operation.cancel() }
        }
        return BridgeStream(stream)
    }
    override suspend fun close() {
        pathWatch.cancel()
        mutablePath.value = PeerPath.CLOSED
        try { awaitUnit(native::close) } finally { native.dispose() }
    }
}

private class BridgeStream(private val native: NativePeerStream) : PeerStream {
    override suspend fun readExact(count: Int): ByteArray {
        require(count in 0..1_048_576) { "Read size exceeds the protocol frame bound" }
        return suspendCancellableCoroutine { continuation ->
            val operation = native.readExact(count, object : NativeBytesCallback {
                override fun success(bytes: ByteArray) { continuation.resume(bytes) }
                override fun failure(message: String) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message))
                }
            })
            continuation.invokeOnCancellation { operation.cancel(); native.dispose() }
        }
    }
    override suspend fun writeAll(bytes: ByteArray) = awaitUnit { native.writeAll(bytes, it) }
    override suspend fun finish() = awaitUnit(native::finish)
    override suspend fun close() {
        try { awaitUnit(native::close) } finally { native.dispose() }
    }
}

private suspend fun awaitUnit(start: (NativeUnitCallback) -> NativeCancellation) {
    suspendCancellableCoroutine<Unit> { continuation ->
        val operation = start(object : NativeUnitCallback {
            override fun success() { continuation.resume(Unit) }
            override fun failure(message: String) {
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message))
            }
        })
        continuation.invokeOnCancellation { operation.cancel() }
    }
}
