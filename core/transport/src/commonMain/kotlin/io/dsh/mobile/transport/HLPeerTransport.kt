package io.dsh.mobile.transport

import kotlinx.coroutines.flow.StateFlow

const val MOBILE_ALPN = "dsh-mobile/1"

data class HLPeerDescriptor(val endpointId: String, val ticket: String? = null)
enum class HLPeerPath { CONNECTING, DIRECT, RELAY, CLOSED }

interface HLPeerTransport {
    suspend fun connect(peer: HLPeerDescriptor, seed: ByteArray): HLPeerConnection
}

interface HLPeerConnection {
    val remoteEndpointId: String
    val path: StateFlow<HLPeerPath>
    suspend fun openBi(): HLPeerStream
    suspend fun acceptBi(): HLPeerStream
    suspend fun close()
}

interface HLPeerStream {
    suspend fun readExact(count: Int): ByteArray
    suspend fun writeAll(bytes: ByteArray)
    suspend fun finish()
    suspend fun close()
}
