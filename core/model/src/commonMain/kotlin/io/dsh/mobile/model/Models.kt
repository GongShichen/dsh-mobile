package io.dsh.mobile.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class DesktopHost(
    val id: String,
    val endpointId: String,
    val name: String,
    val ticket: String? = null,
    val instanceId: String? = null,
    val dshVersion: String? = null,
    val capabilities: List<String> = emptyList(),
)

@Serializable
data class SessionSummary(
    val id: String,
    val title: String,
    val workspaceId: String? = null,
    val updatedAt: Long = 0,
    val archived: Boolean = false,
    val pinned: Boolean = false,
    val running: Boolean = false,
    val raw: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class AssistantBlock(
    val index: Int,
    val kind: String,
    val text: String = "",
    val toolCallId: String? = null,
    val name: String? = null,
    val arguments: String = "",
    val streaming: Boolean = false,
)

@Serializable
enum class AssistantPhase { WAITING, THINKING, WRITING, TOOL_CALL, COMPLETE, INTERRUPTED }

@Serializable
data class MessageRecord(
    val id: String,
    val sessionId: String,
    val role: String,
    val text: String,
    val sequence: Long? = null,
    val revision: Long = 0,
    val streaming: Boolean = false,
    val raw: JsonObject = JsonObject(emptyMap()),
    val blocks: List<AssistantBlock> = emptyList(),
    val phase: AssistantPhase = AssistantPhase.COMPLETE,
    val presentationId: String? = null,
)

@Serializable
data class PendingInteraction(val id: String, val sessionId: String?, val kind: String, val raw: JsonObject)

@Serializable
enum class OperationStatus { QUEUED, DISPATCHED, CONFIRMED, FAILED, UNKNOWN }

@Serializable
data class PendingOperation(
    val id: String,
    val hostId: String,
    val method: String,
    val params: JsonObject,
    val status: OperationStatus,
    val requestId: String? = null,
    val result: JsonObject? = null,
    val error: String? = null,
)

enum class ConnectionStatus { DISCONNECTED, CONNECTING, PAIRING, AWAITING_APPROVAL, SYNCING, CONNECTED, RECONNECTING, ERROR }
enum class ConnectionPath { UNKNOWN, DIRECT, RELAY }

data class MobileState(
    val hosts: List<DesktopHost> = emptyList(),
    val activeHostId: String? = null,
    val connection: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val path: ConnectionPath = ConnectionPath.UNKNOWN,
    val sessions: List<SessionSummary> = emptyList(),
    val selectedSessionId: String? = null,
    val messages: List<MessageRecord> = emptyList(),
    val draft: String = "",
    val interactions: List<PendingInteraction> = emptyList(),
    val operations: List<PendingOperation> = emptyList(),
    val capabilities: Set<String> = emptySet(),
    val error: String? = null,
    val pairingName: String? = null,
    val sessionState: JsonObject? = null,
    val projections: JsonObject? = null,
    val workspaces: List<JsonObject> = emptyList(),
    val models: List<JsonObject> = emptyList(),
    val defaultModel: JsonObject? = null,
    val feedback: Map<String, JsonObject> = emptyMap(),
    val turnPresentation: Map<Long, JsonObject> = emptyMap(),
    val jobs: List<JsonObject> = emptyList(),
    val hasMoreHistory: Boolean = false,
) {
    val connected: Boolean get() = connection == ConnectionStatus.CONNECTED
    val activeHost: DesktopHost? get() = hosts.firstOrNull { it.id == activeHostId }
    val selectedSession: SessionSummary? get() = sessions.firstOrNull { it.id == selectedSessionId }
}
