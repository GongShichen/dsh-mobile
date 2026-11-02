package io.dsh.mobile.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

enum class HLConversationMode { NORMAL, PERSONAL }
@Serializable
data class HLPersonalModelSelection(val provider: String, val model: String, val reasoningEffort: String? = null)
@Serializable
data class HLExecutionTarget(val kind: String = "internal", val workspaceId: String? = null, val sessionId: String? = null, val agentPreset: String? = null, val modelSelection: HLPersonalModelSelection? = null) {
    fun toJson(): JsonObject = buildJsonObject {
        put("kind", kind); workspaceId?.let { put("workspaceId", it) }; sessionId?.let { put("sessionId", it) }; agentPreset?.let { put("agentPreset", it) }
        modelSelection?.let { selection -> put("modelSelection", buildJsonObject { put("provider", selection.provider); put("model", selection.model); selection.reasoningEffort?.let { put("reasoningEffort", it) } }) }
    }
}
@Serializable
data class HLPersonalProfile(val id: String, val name: String, val enabled: Boolean, val paused: Boolean, val revision: Long,
    val maxConcurrentTasks: Int = 2, val taskTimeoutMs: Long = 900_000, val mainSessionId: String? = null,
    val mainSessionReady: Boolean = false, val createdAt: Long = 0, val updatedAt: Long = 0)
@Serializable
data class HLPersonalMilestone(val id: String, val title: String, val state: String = "open")
@Serializable
data class HLPersonalGoal(val id: String, val title: String, val objective: String, val state: String,
    val priority: String = "normal", val revision: Long, val createdAt: Long = 0, val updatedAt: Long = 0,
    val milestones: List<HLPersonalMilestone> = emptyList())
@Serializable
data class HLSessionBinding(val sessionId: String, val ownership: String, val workspaceId: String? = null,
    val workspaceTitle: String? = null, val cwd: String? = null, val agentPreset: String? = null)
@Serializable
data class HLPersonalTaskResult(val summary: String, val verification: String, val sessionId: String, val throughSeq: Long,
    val artifacts: List<String> = emptyList(), val actionIds: List<String> = emptyList())
@Serializable
data class HLPersonalTask(val id: String, val title: String, val instruction: String = "", val goalId: String? = null,
    val priority: String = "normal", val target: HLExecutionTarget = HLExecutionTarget(), val binding: HLSessionBinding? = null,
    val state: String, val waitReason: String? = null, val revision: Long, val summary: String = "",
    val source: JsonObject = JsonObject(emptyMap()),
    val accountIds: List<String> = emptyList(),
    val resultKind: String = "text",
    val dependsOn: List<String> = emptyList(),
    val result: HLPersonalTaskResult? = null, val attempt: Int = 0, val createdAt: Long = 0, val updatedAt: Long = 0, val errorCode: String? = null)
@Serializable
data class HLPersonalMemory(val id: String, val kind: String, val text: String, val quote: String? = null,
    val source: JsonObject = JsonObject(emptyMap()), val revision: Long, val createdAt: Long = 0, val updatedAt: Long = 0, val expiresAt: Long? = null)
@Serializable
data class HLPersonalAction(val id: String, val taskId: String, val operation: String, val accountId: String,
    val input: JsonObject = JsonObject(emptyMap()), val preview: String, val state: String,
    val recipients: List<String> = emptyList(), val calendarIds: List<String> = emptyList(), val errorCode: String? = null,
    val result: JsonElement? = null, val createdAt: Long = 0, val updatedAt: Long = 0)
@Serializable
data class HLPersonalGrant(val id: String, val taskId: String, val accountId: String, val state: String, val revision: Long,
    val operations: List<String> = emptyList(), val recipients: List<String> = emptyList(), val calendarIds: List<String> = emptyList(),
    val expiresAt: Long, val maxWrites: Int, val usedWrites: Int, val createdAt: Long = 0,
    val scope: String = "task", val routineId: String? = null, val parentGrantId: String? = null)
@Serializable
data class HLPersonalRoutine(val id: String, val title: String, val instruction: String, val state: String, val revision: Long,
    val accountIds: List<String> = emptyList(),
    val resultKind: String = "text", val errorCode: String? = null,
    val target: HLExecutionTarget = HLExecutionTarget(), val goalId: String? = null, val timing: JsonObject = JsonObject(emptyMap()),
    val scheduleId: String? = null, val ingressSessionId: String? = null, val createdAt: Long = 0, val updatedAt: Long = 0)
@Serializable
data class HLPersonalActivity(val id: String, val kind: String, val summary: String, val taskId: String? = null, val createdAt: Long = 0)
@Serializable
data class HLPersonalConnection(val id: String, val provider: String, val email: String, val state: String,
    val scopes: List<String> = emptyList(), val updatedAt: Long = 0)
@Serializable
data class HLPersonalCounts(val goals: Int = 0, val tasks: Int = 0, val memories: Int = 0, val attention: Int = 0)
@Serializable
data class HLPersonalDeliveryError(val inputId: String, val message: String)
@Serializable
data class HLPersonalServiceRequest(val id: String, val provider: String = "google", val service: String, val createdAt: Long = 0, val taskId: String? = null)
@Serializable
data class HLPersonalSnapshot(val version: Int = 1, val revision: Long, val available: Boolean, val unavailableReason: String? = null,
    val profile: HLPersonalProfile, val goals: List<HLPersonalGoal> = emptyList(), val tasks: List<HLPersonalTask> = emptyList(),
    val memories: List<HLPersonalMemory> = emptyList(), val actions: List<HLPersonalAction> = emptyList(), val grants: List<HLPersonalGrant> = emptyList(),
    val routines: List<HLPersonalRoutine> = emptyList(), val activity: List<HLPersonalActivity> = emptyList(),
    val connections: List<HLPersonalConnection> = emptyList(), val googleConfigured: Boolean = false, val counts: HLPersonalCounts = HLPersonalCounts(),
    val serviceRequests: List<HLPersonalServiceRequest> = emptyList(),
    val deliveryError: HLPersonalDeliveryError? = null)
@Serializable
data class HLPersonalWorkspace(val workspaceId: String, val title: String, val path: String, val sessionIds: List<String> = emptyList())
@Serializable
data class HLPersonalSession(val sessionId: String, val title: String, val running: Boolean = false, val archived: Boolean = false,
    val workspaceId: String? = null, val cwd: String? = null, val agentPreset: String? = null, val formatStatus: String? = null)

fun HLPersonalTask.statusLabel(): String = when {
    state == "waiting" && waitReason == "verification" -> "执行结束，待核验"
    state == "waiting" && waitReason == "session-busy" -> "等待原会话"
    state == "waiting" && waitReason == "workspace-busy" -> "等待工作区空闲"
    state == "waiting" && waitReason == "dependency" -> "等待依赖事务"
    state == "waiting" && waitReason == "human-control" -> "已让出控制"
    state == "waiting" && waitReason == "external" -> "等待外部变化"
    state == "waiting" && waitReason == "scheduled" -> "已安排跟进"
    state == "waiting" && waitReason == "grant" -> "等待任务授权"
    state == "waiting" && waitReason == "goal-paused" -> "目标已暂停"
    else -> personalStatusLabel(state)
}
fun personalStatusLabel(state: String): String = when (state) {
    "queued" -> "排队中"; "starting", "creating" -> "准备执行"; "running" -> "执行中"; "waiting" -> "需要处理"
    "completed", "confirmed" -> "已完成"; "failed" -> "失败"; "cancelled" -> "已停止"; "interrupted" -> "执行中断"
    "active" -> "进行中"; "paused" -> "已暂停"; "blocked" -> "受阻"; "archived" -> "已归档"; "unknown" -> "待核实"
    "prepared" -> "待授权"; "dispatching" -> "正在提交"; else -> state
}
fun personalResultLabel(kind: String): String = when (kind) {
    "files" -> "成果文件"; "email" -> "发送邮件"; "calendar" -> "写入日程"; "session" -> "工作区会话"; else -> "文字答复"
}
