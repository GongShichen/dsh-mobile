package io.dsh.mobile.sync

import io.dsh.mobile.platform.newOperationId
import io.dsh.mobile.protocol.*
import io.dsh.mobile.transport.HLPeerConnection
import io.dsh.mobile.transport.HLPeerStream
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Request correlation and stream ownership are per authenticated transport connection. */
internal class HLRPCSession private constructor(
    val connection: HLPeerConnection,
    private val rpc: HLPeerStream,
    parentScope: CoroutineScope,
    private val onEvent: suspend (HLSubscriptionEvent) -> Unit,
    private val onFailure: suspend (Throwable) -> Unit,
    private val onNotice: suspend (String) -> Unit,
    private val decodingDispatcher: CoroutineDispatcher,
) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val writes = Mutex()
    private val pendingLock = Mutex()
    private val pending = mutableMapOf<String, CompletableDeferred<JsonElement>>()
    private var closed = false

    private fun start() {
        scope.launch { guard { readResponses() } }
        scope.launch { guard { readServerStreams() } }
    }

    suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap()), timeoutMillis: Long = 30_000): JsonElement {
        HLMethods.requireAllowed(method)
        val id = newOperationId()
        val result = CompletableDeferred<JsonElement>()
        pendingLock.withLock {
            check(!closed) { "Connection is closed" }
            pending[id] = result
        }
        try {
            writes.withLock {
                rpc.writeAll(HLFrameCodec.encode(WireJson.encodeToJsonElement(HLRPCRequest.serializer(), HLRPCRequest(id = id, method = method, params = params))))
            }
            return withTimeout(timeoutMillis) { result.await() }
        } finally { pendingLock.withLock { pending.remove(id) } }
    }

    suspend fun close() {
        failPending(CancellationException("Connection closed"))
        job.cancel()
        withContext(NonCancellable) { runCatching { rpc.close() }; runCatching { connection.close() } }
    }

    private suspend fun readResponses() {
        while (currentCoroutineContext().isActive) {
            val response = withContext(decodingDispatcher) {
                WireJson.decodeFromJsonElement(HLRPCResponse.serializer(), HLFrameCodec.read(rpc::readExact))
            }
            if (response.v != PROTOCOL_VERSION) throw HLProtocolException("Unsupported response version")
            val waiter = pendingLock.withLock { pending.remove(response.id) } ?: continue
            val error = response.error
            if (error != null) waiter.completeExceptionally(HLRemoteCallException(error.code, error.message, error.retryable))
            else waiter.complete(response.result ?: JsonNull)
        }
    }

    private suspend fun readServerStreams() {
        while (currentCoroutineContext().isActive) {
            val stream = connection.acceptBi()
            scope.launch {
                guard {
                    try {
                        val header = WireJson.decodeFromJsonElement(HLStreamHeader.serializer(), HLFrameCodec.read(stream::readExact))
                        if (header.v != PROTOCOL_VERSION || header.type != "stream" || header.kind != "events") throw HLProtocolException("Unexpected server stream")
                        while (currentCoroutineContext().isActive) {
                            val value = withContext(decodingDispatcher) { HLFrameCodec.read(stream::readExact).jsonObject }
                            if (value.string("type") == "notice") {
                                if (value.long("v") != PROTOCOL_VERSION.toLong()) throw HLProtocolException("Unsupported notice version")
                                val name = value.string("name") ?: throw HLProtocolException("Missing notice name")
                                scope.launch { onNotice(name) }
                            } else onEvent(WireJson.decodeFromJsonElement(HLSubscriptionEvent.serializer(), value))
                        }
                    } finally { withContext(NonCancellable) { runCatching { stream.close() } } }
                }
            }
        }
    }

    private suspend fun guard(block: suspend () -> Unit) {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) {
            if (failPending(error)) {
                withContext(NonCancellable) { runCatching { connection.close() }; onFailure(error) }
                job.cancel()
            }
        }
    }

    private suspend fun failPending(error: Throwable): Boolean = pendingLock.withLock {
        if (closed) return@withLock false
        closed = true
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
        true
    }

    companion object {
        suspend fun open(connection: HLPeerConnection, scope: CoroutineScope, onEvent: suspend (HLSubscriptionEvent) -> Unit, onFailure: suspend (Throwable) -> Unit, onNotice: suspend (String) -> Unit = {}, decodingDispatcher: CoroutineDispatcher = Dispatchers.Default): HLRPCSession {
            val rpc = connection.openBi()
            try {
                rpc.writeAll(HLFrameCodec.encode(streamHeader("rpc")))
                return HLRPCSession(connection, rpc, scope, onEvent, onFailure, onNotice, decodingDispatcher).also { it.start() }
            } catch (error: Throwable) {
                withContext(NonCancellable) { runCatching { rpc.close() }; runCatching { connection.close() } }
                throw error
            }
        }
    }
}
