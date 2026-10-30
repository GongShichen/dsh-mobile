package io.dsh.mobile.protocol

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.Serializable

@Serializable
data class PairingInvitation(
    val v: Int,
    val endpointId: String,
    val ticket: String,
    val pairingId: String,
    val secret: String,
    val expiresAt: Long,
    val name: String,
) {
    // Never include the invitation secret in diagnostics or UI state.
    override fun toString(): String = "PairingInvitation(name=$name, expiresAt=$expiresAt)"

    companion object {
        @OptIn(ExperimentalEncodingApi::class)
        fun parse(qr: String, nowMillis: Long): PairingInvitation {
            if (qr.length > 65536 || !qr.startsWith("dsh-mobile://pair?")) throw ProtocolException("Invalid pairing QR")
            val query = qr.substringAfter('?')
            if ('#' in query) throw ProtocolException("Invalid pairing QR")
            val values = query.split('&').filter { it.substringBefore('=') == "data" }
            if (values.size != 1) throw ProtocolException("Pairing QR must contain one payload")
            val encoded = values.single().substringAfter('=', "")
            val value = try {
                val bytes = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(encoded)
                WireJson.decodeFromString(serializer(), bytes.decodeToString(throwOnInvalidSequence = true))
            } catch (_: Exception) { throw ProtocolException("Invalid pairing payload") }
            if (value.v != PROTOCOL_VERSION) throw ProtocolException("Unsupported pairing version")
            if (value.expiresAt <= nowMillis) throw ProtocolException("配对二维码已过期，请在电脑上刷新二维码后重新扫描")
            if (value.endpointId.isBlank() || value.ticket.isBlank() || value.pairingId.isBlank() || value.name.isBlank()) throw ProtocolException("Incomplete pairing invitation")
            val secretLength = try { Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(value.secret).size } catch (_: Exception) { 0 }
            if (secretLength != 32) throw ProtocolException("Invalid pairing secret")
            return value
        }
    }
}
