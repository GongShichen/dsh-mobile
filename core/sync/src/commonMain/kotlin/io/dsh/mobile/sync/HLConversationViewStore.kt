package io.dsh.mobile.sync

import io.dsh.mobile.model.HLConversationMode

data class HLConversationViewKey(val hostId: String, val mode: HLConversationMode, val sessionId: String?)
data class HLConversationViewSnapshot(val draft: String = "", val attachments: List<Pair<String, String>> = emptyList(),
    val scrollIndex: Int = 0, val scrollOffset: Int = 0, val followTail: Boolean = true,
    val turns: Map<String, Boolean> = emptyMap(), val groups: Map<String, Boolean> = emptyMap(), val leaves: Map<String, Boolean> = emptyMap())
/** RAM-only view choices, scoped to Host, mode and actual session identity. */
class HLConversationViewStore {
    private val views = mutableMapOf<HLConversationViewKey, HLConversationViewSnapshot>()
    private val normalSessions = mutableMapOf<String, String>()
    fun get(key: HLConversationViewKey) = views[key] ?: HLConversationViewSnapshot()
    fun save(key: HLConversationViewKey, value: HLConversationViewSnapshot) { views[key] = value }
    fun updateDraft(key: HLConversationViewKey, text: String) { save(key, get(key).copy(draft = text)) }
    fun copyIfAbsent(from: HLConversationViewKey, to: HLConversationViewKey) { if (to !in views) views[from]?.let { views[to] = it } }
    fun normalSession(hostId: String): String? = normalSessions[hostId]
    fun selectNormal(hostId: String, sessionId: String) { normalSessions[hostId] = sessionId }
    fun removeHost(hostId: String) { views.keys.filter { it.hostId == hostId }.toList().forEach(views::remove); normalSessions.remove(hostId) }
}
