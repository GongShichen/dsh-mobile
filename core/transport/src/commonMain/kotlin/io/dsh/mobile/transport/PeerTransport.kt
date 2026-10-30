package io.dsh.mobile.transport

import kotlinx.coroutines.flow.StateFlow

const val MOBILE_ALPN = "dsh-mobile/1"

data class PeerDescriptor(val endpointId: String, val ticket: String? = null)
enum class PeerPath { CONNECTING, DIRECT, RELAY, CLOSED }

interface PeerTransport {
    suspend fun connect(peer: PeerDescriptor, seed: ByteArray): PeerConnection
}

interface PeerConnection {
    val remoteEndpointId: String
    val path: StateFlow<PeerPath>
    suspend fun openBi(): PeerStream
    suspend fun acceptBi(): PeerStream
    suspend fun close()
}

interface PeerStream {
    suspend fun readExact(count: Int): ByteArray
    suspend fun writeAll(bytes: ByteArray)
    suspend fun finish()
    suspend fun close()
}
