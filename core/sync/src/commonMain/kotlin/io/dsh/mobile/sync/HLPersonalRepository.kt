package io.dsh.mobile.sync

import io.dsh.mobile.model.HLPersonalSnapshot
import io.dsh.mobile.platform.newOperationId
import io.dsh.mobile.protocol.HLProtocolException
import io.dsh.mobile.protocol.WireJson
import kotlinx.serialization.json.*
import kotlinx.coroutines.CancellationException

/** Volatile state on the current authenticated Host; no business database is opened. */
class HLPersonalRepository(private val publish: (HLPersonalSnapshot?) -> Unit) {
    private class HLBinding(val key: String, val call: suspend (String, JsonObject) -> JsonElement)
    private var binding: HLBinding? = null
    private var cachedHostId: String? = null
    private var acceptedRevision = -1L
    var snapshot: HLPersonalSnapshot? = null
        private set
    fun bind(hostId: String, generation: Long, call: suspend (String, JsonObject) -> JsonElement) {
        if (cachedHostId != hostId) snapshot = null
        cachedHostId = hostId; acceptedRevision = -1L
        binding = HLBinding("$hostId:$generation", call); publish(snapshot)
    }
    fun unbind() { binding = null }
    fun reset() { binding = null; cachedHostId = null; acceptedRevision = -1L; snapshot = null; publish(null) }
    fun accept(value: JsonElement) {
        if (binding == null) return
        val next = WireJson.decodeFromJsonElement(HLPersonalSnapshot.serializer(), value)
        if (next.version != 1) throw HLProtocolException("私人助理协议需要更新客户端")
        if (next.revision < acceptedRevision) return
        acceptedRevision = next.revision; snapshot = next; publish(next)
    }
    suspend fun refresh() {
        val current = binding ?: return
        val value = current.call("personal.snapshot", JsonObject(emptyMap()))
        if (binding === current) accept(value)
    }
    suspend fun read(method: String, params: JsonObject = JsonObject(emptyMap())): JsonElement {
        val current = binding ?: throw HLProtocolException("请先连接支持私人助理的电脑")
        val value = current.call("personal.$method", params)
        if (binding !== current) throw CancellationException("电脑连接已切换")
        return value
    }
    suspend fun command(method: String, params: JsonObject = JsonObject(emptyMap())): JsonElement {
        val current = binding ?: throw HLProtocolException("请先连接支持私人助理的电脑")
        val actual = JsonObject(params + ("operationId" to (params["operationId"] ?: JsonPrimitive(newOperationId()))))
        val result = current.call("personal.$method", actual)
        if (binding !== current) throw CancellationException("电脑连接已切换")
        refresh()
        return result
    }
}
