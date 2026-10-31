package io.dsh.mobile.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class HLDesktopHost(
    val id: String,
    val endpointId: String,
    val name: String,
    val ticket: String? = null,
    val instanceId: String? = null,
    val dshVersion: String? = null,
    val capabilities: List<String> = emptyList(),
)

@Serializable
data class HLSessionSummary(
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
data class HLAssistantBlock(
    val index: Int,
    val kind: String,
    val text: String = "",
    val toolCallId: String? = null,
    val name: String? = null,
    val arguments: String = "",
    val streaming: Boolean = false,
)

@Serializable
enum class HLAssistantPhase { WAITING, THINKING, WRITING, TOOL_CALL, COMPLETE, INTERRUPTED }

@Serializable
data class HLMessageRecord(
    val id: String,
    val sessionId: String,
    val role: String,
    val text: String,
    val sequence: Long? = null,
    val revision: Long = 0,
    val streaming: Boolean = false,
    val raw: JsonObject = JsonObject(emptyMap()),
    val blocks: List<HLAssistantBlock> = emptyList(),
    val phase: HLAssistantPhase = HLAssistantPhase.COMPLETE,
    val presentationId: String? = null,
)

@Serializable
data class HLPendingInteraction(
    val id: String, val sessionId: String?, val kind: String, val raw: JsonObject,
    val clientId: String? = null, val timing: HLInteractionTiming? = null, val context: JsonObject? = null,
)

@Serializable
data class HLInteractionTiming(val remainingMs: Long?, val paused: Boolean, val attached: Boolean, val expired: Boolean = false)

@Serializable
data class HLPendingQuestion(val sessionId: String, val callId: String, val state: String, val questions: List<JsonObject>)

@Serializable
enum class HLOperationStatus { QUEUED, DISPATCHED, CONFIRMED, FAILED, UNKNOWN }

@Serializable
data class HLPendingOperation(
    val id: String,
    val hostId: String,
    val method: String,
    val params: JsonObject,
    val status: HLOperationStatus,
    val requestId: String? = null,
    val result: JsonObject? = null,
    val error: String? = null,
)

enum class HLConnectionStatus { DISCONNECTED, CONNECTING, PAIRING, AWAITING_APPROVAL, SYNCING, CONNECTED, RECONNECTING, ERROR }
enum class HLConnectionPath { UNKNOWN, DIRECT, RELAY }

data class HLState(
    val hosts: List<HLDesktopHost> = emptyList(),
    val activeHostId: String? = null,
    val connection: HLConnectionStatus = HLConnectionStatus.DISCONNECTED,
    val path: HLConnectionPath = HLConnectionPath.UNKNOWN,
    val sessions: List<HLSessionSummary> = emptyList(),
    val selectedSessionId: String? = null,
    val messages: List<HLMessageRecord> = emptyList(),
    val draft: String = "",
    val interactions: List<HLPendingInteraction> = emptyList(),
    val interactionsReady: Boolean = false,
    val pendingQuestions: List<HLPendingQuestion> = emptyList(),
    val pendingQuestionsLoading: Boolean = false,
    val resolvingQuestions: Set<String> = emptySet(),
    val operations: List<HLPendingOperation> = emptyList(),
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
    val connectionGeneration: Long = 0,
) {
    val connected: Boolean get() = connection == HLConnectionStatus.CONNECTED
    val activeHost: HLDesktopHost? get() = hosts.firstOrNull { it.id == activeHostId }
    val selectedSession: HLSessionSummary? get() = sessions.firstOrNull { it.id == selectedSessionId }
}
