package io.dsh.mobile.transport

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Callback-only boundary exported to Swift. Each operation completes at most once. */
interface HLNativeCancellation { fun cancel() }
interface HLNativeConnectCallback {
    fun success(connection: HLNativePeerConnection)
    fun failure(message: String)
}
interface HLNativeStreamCallback {
    fun success(stream: HLNativePeerStream)
    fun failure(message: String)
}
interface HLNativeBytesCallback {
    fun success(bytes: ByteArray)
    fun failure(message: String)
}
interface HLNativeUnitCallback {
    fun success()
    fun failure(message: String)
}
interface HLNativePathCallback { fun changed(path: String) }

interface HLNativeIrohBridge {
    fun connect(endpointId: String, ticket: String?, seed: ByteArray, callback: HLNativeConnectCallback): HLNativeCancellation
}
interface HLNativePeerConnection {
    fun remoteEndpointId(): String
    fun openBi(callback: HLNativeStreamCallback): HLNativeCancellation
    fun acceptBi(callback: HLNativeStreamCallback): HLNativeCancellation
    fun watchPath(callback: HLNativePathCallback): HLNativeCancellation
    fun close(callback: HLNativeUnitCallback): HLNativeCancellation
    /** Starts unconditional cleanup, including any endpoint owned by this connection. */
    fun dispose()
}
interface HLNativePeerStream {
    fun readExact(count: Int, callback: HLNativeBytesCallback): HLNativeCancellation
    fun writeAll(bytes: ByteArray, callback: HLNativeUnitCallback): HLNativeCancellation
    fun finish(callback: HLNativeUnitCallback): HLNativeCancellation
    fun close(callback: HLNativeUnitCallback): HLNativeCancellation
    fun dispose()
}

class HLBridgePeerTransport(private val bridge: HLNativeIrohBridge) : HLPeerTransport {
    override suspend fun connect(peer: HLPeerDescriptor, seed: ByteArray): HLPeerConnection {
        require(seed.size == 32) { "An iroh identity requires a 32-byte seed" }
        val native = suspendCancellableCoroutine { continuation ->
            val operation = bridge.connect(peer.endpointId, peer.ticket, seed, object : HLNativeConnectCallback {
                override fun success(connection: HLNativePeerConnection) {
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
        return HLBridgeConnection(native)
    }
}

private class HLBridgeConnection(private val native: HLNativePeerConnection) : HLPeerConnection {
    override val remoteEndpointId = native.remoteEndpointId()
    private val mutablePath = MutableStateFlow(HLPeerPath.CONNECTING)
    override val path = mutablePath.asStateFlow()
    private val pathWatch = native.watchPath(object : HLNativePathCallback {
        override fun changed(path: String) {
            mutablePath.update { current ->
                if (current == HLPeerPath.CLOSED) current else HLPeerPath.entries.firstOrNull { it.name == path } ?: HLPeerPath.CONNECTING
            }
        }
    })
    override suspend fun openBi(): HLPeerStream = open(native::openBi)
    override suspend fun acceptBi(): HLPeerStream = open(native::acceptBi)
    private suspend fun open(start: (HLNativeStreamCallback) -> HLNativeCancellation): HLPeerStream {
        val stream = suspendCancellableCoroutine { continuation ->
            val operation = start(object : HLNativeStreamCallback {
                override fun success(stream: HLNativePeerStream) {
                    continuation.resume(stream, onCancellation = { _, value, _ -> value.dispose() })
                }
                override fun failure(message: String) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message))
                }
            })
            continuation.invokeOnCancellation { operation.cancel() }
        }
        return HLBridgeStream(stream)
    }
    override suspend fun close() {
        pathWatch.cancel()
        mutablePath.value = HLPeerPath.CLOSED
        try { awaitUnit(native::close) } finally { native.dispose() }
    }
}

private class HLBridgeStream(private val native: HLNativePeerStream) : HLPeerStream {
    override suspend fun readExact(count: Int): ByteArray {
        require(count in 0..1_048_576) { "Read size exceeds the protocol frame bound" }
        return suspendCancellableCoroutine { continuation ->
            val operation = native.readExact(count, object : HLNativeBytesCallback {
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

private suspend fun awaitUnit(start: (HLNativeUnitCallback) -> HLNativeCancellation) {
    suspendCancellableCoroutine<Unit> { continuation ->
        val operation = start(object : HLNativeUnitCallback {
            override fun success() { continuation.resume(Unit) }
            override fun failure(message: String) {
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message))
            }
        })
        continuation.invokeOnCancellation { operation.cancel() }
    }
}
