package io.dsh.mobile.sync

import io.dsh.mobile.model.*
import io.dsh.mobile.protocol.*
import kotlinx.serialization.json.*

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())

fun decodeSessions(result: JsonObject): List<SessionSummary> = result.array("items").mapNotNull { item ->
    val row = item as? JsonObject ?: return@mapNotNull null
    val id = row.string("sessionId") ?: return@mapNotNull null
    SessionSummary(
        id = id,
        title = row.obj("projections")?.obj("values")?.string("title")?.takeIf { it.isNotBlank() } ?: "新会话",
        workspaceId = row.string("workspaceId"),
        updatedAt = row.long("updatedAt") ?: 0,
        archived = row.boolean("archived") ?: false,
        pinned = row.boolean("pinned") ?: false,
        running = row.boolean("running") ?: false,
        raw = row,
    )
}

/** Workspace has an independent replace/update stream and owns pins and archive state. */
class WorkspaceReducer {
    var items: List<JsonObject> = emptyList()
        private set
    var archived: Set<String> = emptySet()
        private set
    var pinned: Set<String> = emptySet()
        private set

    fun reset() { items = emptyList(); archived = emptySet(); pinned = emptySet() }
    fun apply(payload: JsonObject) {
        when (payload.string("type")) {
            "baseline", "replace" -> {
                val value = payload.obj("value") ?: return
                items = value.array("items").mapNotNull { it as? JsonObject }
                archived = strings(value.array("archivedSessionIds")); pinned = strings(value.array("pinnedSessionIds"))
            }
            "upsert" -> payload.obj("workspace")?.let { workspace ->
                val index = items.indexOfFirst { it.string("workspaceId") == workspace.string("workspaceId") }
                items = if (index < 0) items + workspace else items.mapIndexed { i, item -> if (i == index) workspace else item }
            }
            "remove" -> items = items.filterNot { it.string("workspaceId") == payload.string("workspaceId") }
            "order" -> {
                val order = payload.array("workspaceIds").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                items = items.sortedBy { row -> order.indexOf(row.string("workspaceId")).let { if (it < 0) Int.MAX_VALUE else it } }
            }
            "archived" -> archived = strings(payload.array("archivedSessionIds"))
            "pinned" -> pinned = strings(payload.array("pinnedSessionIds"))
        }
    }
    fun decorate(rows: List<SessionSummary>): List<SessionSummary> = rows.map { row ->
        val workspace = items.firstOrNull { row.id in strings(it.array("sessionIds")) }
        row.copy(archived = row.id in archived, pinned = row.id in pinned, workspaceId = workspace?.string("workspaceId"))
    }
    private fun strings(array: JsonArray) = array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
}

fun applyTitleProjections(rows: List<SessionSummary>, frame: JsonObject): List<SessionSummary> {
    val titles = when (frame.string("type")) {
        "baseline" -> frame.obj("value")?.obj("projections")?.mapNotNull { (id, projection) -> (projection as? JsonObject)?.obj("values")?.string("title")?.let { id to it } }?.toMap().orEmpty()
        "projection" -> {
            val id = frame.string("sessionId")
            val value = (frame["value"] as? JsonPrimitive)?.contentOrNull
            if (frame.string("key") == "title" && id != null && value != null) mapOf(id to value) else emptyMap()
        }
        else -> emptyMap()
    }
    return rows.map { row -> titles[row.id]?.let { row.copy(title = it.ifBlank { "新会话" }) } ?: row }
}

/** Follow snapshots and the host-wide control stream may race; compare projection watermarks. */
class ProjectionReducer {
    private data class Entry(val values: MutableMap<String, JsonElement> = mutableMapOf(), val watermarks: MutableMap<String, Long> = mutableMapOf())
    private val sessions = mutableMapOf<String, Entry>()
    fun reset() = sessions.clear()
    fun values(sessionId: String): JsonObject? = sessions[sessionId]?.let { JsonObject(it.values.toMap()) }
    fun baseline(sessionId: String, baseline: JsonObject) {
        val seq = baseline.long("asOfSeq") ?: return
        val values = baseline.obj("values") ?: return
        val entry = sessions.getOrPut(sessionId) { Entry() }
        (entry.values.keys + values.keys).toSet().forEach { key ->
            if (seq >= (entry.watermarks[key] ?: -1)) {
                entry.watermarks[key] = seq
                if (values.containsKey(key)) entry.values[key] = values.getValue(key) else entry.values.remove(key)
            }
        }
    }
    fun control(frame: JsonObject) {
        when (frame.string("type")) {
            "baseline" -> frame.obj("value")?.obj("projections")?.forEach { (id, value) -> (value as? JsonObject)?.let { baseline(id, it) } }
            "projection" -> {
                val id = frame.string("sessionId") ?: return
                val key = frame.string("key") ?: return
                val seq = frame.long("seq") ?: return
                val value = frame["value"] ?: return
                val entry = sessions.getOrPut(id) { Entry() }
                if (seq >= (entry.watermarks[key] ?: -1)) { entry.watermarks[key] = seq; entry.values[key] = value }
            }
        }
    }
}

/** A follow subscription has its own baseline/cursor and assistant revision domain. */
class SessionReducer(private val sessionId: String) {
    private val durable = linkedMapOf<Long, MessageRecord>()
    private var transient: MessageRecord? = null
    private var revision = -1L
    private var attemptId: String? = null
    private var chunkIndex = -1L
    var cursor: JsonObject? = null
        private set
    var hasMore = false
        private set
    var projections: JsonObject? = null
        private set
    val messages: List<MessageRecord> get() = durable.values.sortedBy { it.sequence } + listOfNotNull(transient)

    fun seed(records: List<MessageRecord>) {
        durable.clear()
        records.filter { it.sequence != null }.forEach { durable[it.sequence!!] = it }
        transient = null; revision = -1; attemptId = null; chunkIndex = -1
    }

    fun apply(event: JsonObject) {
        when (event.string("type")) {
            "snapshot" -> baseline(event)
            "event" -> event.obj("event")?.let(::durableEvent)
            "assistant-stream" -> event.obj("frame")?.let(::assistantFrame)
            else -> if (event.containsKey("records") && event.containsKey("cursor")) baseline(event)
        }
    }

    fun prependHistory(page: JsonObject) {
        page.array("records").forEach { (it as? JsonObject)?.obj("event")?.let(::durableEvent) }
        hasMore = page.boolean("hasMore") ?: false
    }

    private fun baseline(value: JsonObject) {
        // Cached older pages remain useful, while the follow baseline replaces its authoritative window.
        val incoming = value.array("records").mapNotNull { (it as? JsonObject)?.obj("event") }
        val cut = value.long("cursor")
        if (cut != null) durable.keys.filter { it > cut }.toList().forEach(durable::remove)
        val firstSeq = incoming.mapNotNull { it.long("seq") }.minOrNull()
        if (firstSeq != null) durable.keys.filter { it >= firstSeq }.toList().forEach(durable::remove)
        incoming.forEach(::durableEvent)
        cursor = value["cursor"]?.let { token -> buildJsonObject { put("cursor", token) } }
        hasMore = value.boolean("hasMore") ?: false
        projections = value.obj("projections")?.obj("values")
        transient = null; attemptId = null; chunkIndex = -1
        val assistant = value.obj("assistantStream")
        revision = assistant?.long("revision") ?: -1
        assistant?.obj("activeAttempt")?.let { active ->
            attemptId = active.string("attemptId")
            chunkIndex = (active.long("nextIndex") ?: 0) - 1
            val text = compactText(active.array("stream"))
            transient = MessageRecord("attempt:${attemptId}", sessionId, "assistant", text, revision = revision, streaming = true, raw = active)
        }
    }

    private fun durableEvent(event: JsonObject) {
        val seq = event.long("seq") ?: return
        val type = event.string("type") ?: return
        val data = event.obj("data") ?: JsonObject(emptyMap())
        val (role, text) = when (type) {
            "user/message" -> (if (data.obj("source")?.string("kind") in setOf(null, "user")) "user" else "context") to contentText(data.array("content"))
            "assistant/message" -> "assistant" to contentText(data.obj("message")?.array("content") ?: JsonArray(emptyList()))
            else -> "event" to ""
        }
        durable[seq] = MessageRecord("event:$seq", sessionId, role, text, sequence = seq, raw = event)
        if (seq > ((cursor?.get("cursor") as? JsonPrimitive)?.longOrNull ?: -1)) cursor = buildJsonObject { put("cursor", seq) }
        if (type == "assistant/message" || type == "assistant/attempt") {
            val live = transient
            if (live != null && (live.raw.long("turn") == data.long("turn") || live.raw.long("turn") == null)) {
                transient = null
            }
        }
    }

    private fun assistantFrame(frame: JsonObject) {
        val incomingRevision = frame.long("revision") ?: return
        if (frame.string("type") == "start" && incomingRevision == 1L && frame.string("attemptId") != attemptId) {
            revision = 0; transient = null; attemptId = null; chunkIndex = -1
        }
        if (incomingRevision <= revision) return
        if (revision >= 0 && incomingRevision != revision + 1) throw ProtocolException("Assistant revision gap; a new baseline is required")
        val incomingAttempt = frame.string("attemptId") ?: return
        when (frame.string("type")) {
            "start" -> {
                revision = incomingRevision; attemptId = incomingAttempt; chunkIndex = -1
                transient = MessageRecord("attempt:$incomingAttempt", sessionId, "assistant", "", revision = revision, streaming = true, raw = frame)
            }
            "chunk" -> {
                if (incomingAttempt != attemptId) return
                val index = frame.long("index") ?: return
                if (index <= chunkIndex) return
                if (index != chunkIndex + 1) throw ProtocolException("Assistant stream gap; a new baseline is required")
                revision = incomingRevision; chunkIndex = index
                val chunk = frame.obj("chunk") ?: return
                val delta = if (chunk.string("type") == "text-delta") chunk.string("text").orEmpty() else ""
                transient = transient?.copy(text = transient!!.text + delta, revision = revision)
            }
            "end" -> {
                if (incomingAttempt != attemptId) return
                if (frame.long("index") != chunkIndex + 1) throw ProtocolException("Assistant terminal index mismatch")
                revision = incomingRevision
                transient = if (frame.obj("outcome")?.string("kind") == "abandoned") null else transient?.copy(streaming = false, revision = revision)
            }
        }
    }

    private fun contentText(content: JsonArray): String = content.mapNotNull { (it as? JsonObject)?.takeIf { block -> block.string("type") == "text" }?.string("text") }.joinToString("")
    private fun compactText(stream: JsonArray): String = stream.mapNotNull { record ->
        val item = record as? JsonObject ?: return@mapNotNull null
        when (item.string("type")) {
            "text-chunks" -> item.array("texts").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString("")
            "chunk" -> item.obj("chunk")?.takeIf { it.string("type") == "text-delta" }?.string("text")
            else -> null
        }
    }.joinToString("")
}

data class SubscriptionTarget(val topic: String, val sessionId: String? = null)
data class RoutedEvent(val target: SubscriptionTarget, val event: SubscriptionEvent)

/** Buffers the real race where events arrive before the subscribe RPC response. */
class SubscriptionRouter(private val maxPendingEvents: Int = 256, private val maxPendingBytes: Int = 32 * 1024 * 1024) {
    private val handles = mutableMapOf<String, Pair<SubscriptionHandle, SubscriptionTarget>>()
    private val pending = ArrayDeque<SubscriptionEvent>()
    private val retired = mutableSetOf<String>()

    fun register(handle: SubscriptionHandle, target: SubscriptionTarget): List<RoutedEvent> {
        handles[handle.subscriptionId] = handle to target
        val matching = pending.filter { it.subscriptionId == handle.subscriptionId }
        pending.removeAll { it.subscriptionId == handle.subscriptionId }
        return matching.filter { it.generation == handle.generation }.map { RoutedEvent(target, it) }
    }

    fun accept(event: SubscriptionEvent): RoutedEvent? {
        if (event.v != PROTOCOL_VERSION) throw ProtocolException("Unsupported event protocol")
        if (event.subscriptionId in retired) return null
        val registration = handles[event.subscriptionId]
        if (registration != null) return if (registration.first.generation == event.generation) RoutedEvent(registration.second, event) else null
        if (pending.size >= maxPendingEvents) throw ProtocolException("Subscription buffer overflow; reconnect required")
        if (pending.sumOf { it.event.toString().encodeToByteArray().size } + event.event.toString().encodeToByteArray().size > maxPendingBytes) throw ProtocolException("Subscription byte budget exceeded; reconnect required")
        pending.addLast(event)
        return null
    }

    fun remove(id: String) { handles.remove(id); pending.removeAll { it.subscriptionId == id }; retired.add(id) }
    fun handles(): List<Pair<SubscriptionHandle, SubscriptionTarget>> = handles.values.toList()
    fun reset() { handles.clear(); pending.clear(); retired.clear() }
}

object OperationRecovery {
    /** Only requests that never crossed the dispatch boundary may be sent automatically. */
    fun canAutomaticallySend(operation: PendingOperation): Boolean = operation.status == OperationStatus.QUEUED
    fun canExplicitlyRetry(operation: PendingOperation): Boolean = operation.method == "sessions.prompt" &&
        operation.requestId != null && operation.status in setOf(OperationStatus.UNKNOWN, OperationStatus.FAILED)
    fun afterConnectionLoss(operation: PendingOperation): PendingOperation =
        if (operation.status == OperationStatus.DISPATCHED) operation.copy(status = OperationStatus.UNKNOWN, error = "连接中断，桌面端是否执行尚未确认，请核对后再操作") else operation
}
