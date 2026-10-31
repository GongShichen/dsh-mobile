package io.dsh.mobile.sync

import io.dsh.mobile.model.*
import io.dsh.mobile.protocol.HLProtocolException
import kotlinx.serialization.json.*

/** Transient delivery state for one authenticated event generation; no interaction state is persisted. */
class HLInteractionReducer {
    var clientId: String? = null
        private set
    private val pending = linkedMapOf<String, HLPendingInteraction>()
    private val clocks = mutableMapOf<String, HLQuestionClock>()
    fun reset() { clientId = null; pending.clear(); clocks.clear() }
    fun accept(frame: JsonObject) {
        when (frame.string("type")) {
            "ready" -> { reset(); clientId = frame.string("clientId") }
            "waterfall" -> {
                val client = clientId ?: throw HLProtocolException("交互状态缺少连接身份")
                val id = frame.string("eventId") ?: throw HLProtocolException("交互请求缺少身份")
                pending[id] = HLPendingInteraction(id, frame.string("agentId"), frame.string("event").orEmpty(), frame, client)
                if (frame.obj("request")?.obj("wait")?.boolean("timed") == true) clocks.getOrPut(id) { HLQuestionClock() }
            }
            "cancel" -> frame.string("eventId")?.let(::remove)
        }
    }
    fun get(id: String) = pending[id]
    fun remove(id: String) { pending.remove(id); clocks.remove(id) }
    fun context(id: String, value: JsonObject) { pending[id]?.let { pending[id] = it.copy(context = value) } }
    fun waiting(id: String, remainingMs: Long, now: Long) { clocks[id]?.attach(remainingMs.coerceAtLeast(0), now) }
    fun focus(id: String, focused: Boolean, now: Long) { clocks[id]?.focus(focused, now) }
    fun engage(id: String, now: Long) { clocks[id]?.engage(now) }
    fun takeTime(id: String, now: Long) { clocks[id]?.hold(now) }
    fun snapshot(now: Long) = pending.values.map { it.copy(timing = clocks[it.id]?.snapshot(now)) }
    fun expired(now: Long) = clocks.filter { it.value.expire(now) }.keys.mapNotNull(pending::get)
    fun hasClocks() = clocks.isNotEmpty()
}

/** The Host supplies a relative budget; the caller passes monotonic elapsed milliseconds. */
private class HLQuestionClock {
    private var attached = false
    private var focused = false
    private var engaged = false
    private var held = false
    private var expired = false
    private var remaining = 0L
    private var deadline: Long? = null
    private fun pause(now: Long) { deadline?.let { remaining = (it - now).coerceAtLeast(0) }; deadline = null }
    private fun resume(now: Long) { if (attached && !focused && !engaged && !held && !expired) deadline = now + remaining }
    fun attach(value: Long, now: Long) { attached = true; remaining = value; resume(now) }
    fun focus(value: Boolean, now: Long) { if (value == focused) return; if (value) pause(now); focused = value; if (!value) resume(now) }
    fun engage(now: Long) { pause(now); engaged = true }
    fun hold(now: Long) { pause(now); held = true }
    fun expire(now: Long): Boolean {
        if (!expired && deadline?.let { now >= it } == true) { pause(now); expired = true; return true }
        return false
    }
    fun snapshot(now: Long) = HLInteractionTiming(
        if (!attached || held) null else deadline?.let { (it - now).coerceAtLeast(0) } ?: remaining,
        focused || engaged || held, attached, expired,
    )
}

data class HLInteractionPrompt(
    val key: String, val hostId: String, val sessionId: String?, val eventId: String?, val clientId: String?,
    val kind: String, val questions: List<JsonObject>, val request: JsonObject,
    val callId: String? = null, val continued: Boolean = false,
    val timing: HLInteractionTiming? = null, val context: JsonObject? = null,
)

fun questionKey(sessionId: String, callId: String) = "question:$sessionId:$callId"

/** Merge live waterfalls and the Host-wide projection by Session + call, never by the visible tab. */
fun interactionPrompts(state: HLState): List<HLInteractionPrompt> {
    val hostId = state.activeHostId ?: return emptyList()
    val result = linkedMapOf<String, HLInteractionPrompt>()
    for (item in state.interactions) {
        val body = item.raw.obj("request") ?: JsonObject(emptyMap())
        val sessionId = item.sessionId
        val callId = body.obj("wait")?.string("callId")
        val key = if (sessionId != null && callId != null) questionKey(sessionId, callId) else "event:${item.clientId}:${item.id}"
        if (key in state.resolvingQuestions) continue
        result[key] = HLInteractionPrompt(key, hostId, item.sessionId, item.id, item.clientId, item.kind,
            body.array("questions").mapNotNull { it as? JsonObject }, body, callId,
            timing = item.timing, context = item.context)
    }
    for (item in state.pendingQuestions) {
        val key = questionKey(item.sessionId, item.callId)
        if (key !in state.resolvingQuestions && key !in result) result[key] = HLInteractionPrompt(
            key, hostId, item.sessionId, null, null, "user-questions/request", item.questions,
            JsonObject(emptyMap()), item.callId, continued = item.state == "continued",
        )
    }
    return result.values.toList()
}

fun interactionQuestions(frame: JsonObject): List<JsonObject> = frame.obj("request")?.array("questions").orEmpty().mapNotNull { it as? JsonObject }

/** Match call identity before exposing any argument object as approval context. */
fun interactionToolContext(records: List<JsonObject>, callId: String): JsonObject? {
    fun find(value: JsonElement, depth: Int = 0): JsonObject? {
        if (depth > 8) return null
        return when (value) {
            is JsonObject -> if ((value.string("callId") == callId || value.string("id") == callId)
                && (value.containsKey("arguments") || value.containsKey("args"))) value
                else value.values.firstNotNullOfOrNull { find(it, depth + 1) }
            is JsonArray -> value.firstNotNullOfOrNull { find(it, depth + 1) }
            else -> null
        }
    }
    return records.firstNotNullOfOrNull { record ->
        val event = record.obj("event") ?: record
        if (event.string("type") in setOf("tool/call", "tool/asked", "tool/ptc/call")) find(event.obj("data") ?: event) else null
    }
}
