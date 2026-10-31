package io.dsh.mobile.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val PROTOCOL_VERSION = 1
const val MOBILE_ALPN = "dsh-mobile/1"
val WireJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

open class HLProtocolException(message: String) : Exception(message)
class HLLogicalMessageTooLargeException : HLProtocolException("桌面状态超过 16 MiB 消息上限，请缩小会话历史窗口后重试")
class HLRemoteCallException(val code: String, override val message: String, val retryable: Boolean = false) : Exception(message)

@Serializable
data class HLRPCRequest(val v: Int = PROTOCOL_VERSION, val id: String, val method: String, val params: JsonObject = JsonObject(emptyMap()))
@Serializable
data class HLRPCError(val code: String, val message: String, val retryable: Boolean = false)
@Serializable
data class HLRPCResponse(val v: Int = PROTOCOL_VERSION, val id: String, val result: JsonElement? = null, val error: HLRPCError? = null)
@Serializable
data class HLStreamHeader(val v: Int = PROTOCOL_VERSION, val type: String = "stream", val kind: String)
@Serializable
data class HLSubscriptionEvent(val v: Int = PROTOCOL_VERSION, val type: String = "event", val subscriptionId: String, val generation: String, val event: JsonObject)
@Serializable
data class HLSubscriptionHandle(val subscriptionId: String, val generation: String)
@Serializable
data class HLHello(
    val protocolVersion: Int,
    val authorized: Boolean,
    val hostId: String? = null,
    val hostInstanceId: String? = null,
    val hostName: String? = null,
    val dshVersion: String? = null,
    val adapterVersion: String? = null,
    val capabilities: List<String> = emptyList(),
)

fun streamHeader(kind: String): JsonObject = buildJsonObject {
    put("v", PROTOCOL_VERSION); put("type", "stream"); put("kind", kind)
}

/** Fixed mobile RPC allowlist. This is deliberately not a generic DSH RPC tunnel. */
object HLMethods {
    val reads = setOf("feedback.list", "hello", "pairing.status", "sessions.list", "sessions.search", "sessions.events", "sessions.state", "sessions.projections", "workspaces.list", "models.list", "files.list", "files.stat", "files.read", "files.download", "jobs.list", "attachments.status", "push.configuration")
    val mutations = setOf("feedback.put", "feedback.delete", "sessions.create", "sessions.rename", "sessions.fork", "sessions.prompt", "sessions.cancel", "sessions.updateQueue", "sessions.archive", "sessions.unarchive", "sessions.pin", "sessions.unpin", "workspaces.create", "workspaces.rename", "models.select", "jobs.kill", "interactions.reply", "questions.answer", "attachments.begin", "attachments.finalize", "attachments.cancel", "push.describe", "push.register", "push.unregister", "push.received")
    val connectionMethods = setOf("pairing.request", "subscribe", "unsubscribe")
    fun requireAllowed(method: String) {
        if (method !in reads && method !in mutations && method !in connectionMethods) throw HLProtocolException("Unsupported mobile method")
    }
}
