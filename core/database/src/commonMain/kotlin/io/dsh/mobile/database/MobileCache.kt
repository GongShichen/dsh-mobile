package io.dsh.mobile.database

import io.dsh.mobile.model.DesktopHost
import io.dsh.mobile.model.MessageRecord
import io.dsh.mobile.model.PendingOperation
import io.dsh.mobile.model.SessionSummary
import kotlinx.serialization.json.JsonObject

interface MobileCache {
    suspend fun preference(key: String): String?
    suspend fun savePreference(key: String, value: String?)
    suspend fun hosts(): List<DesktopHost>
    suspend fun saveHost(host: DesktopHost)
    suspend fun removeHost(hostId: String)
    suspend fun sessions(hostId: String): List<SessionSummary>
    suspend fun replaceSessions(hostId: String, sessions: List<SessionSummary>)
    suspend fun messages(hostId: String, sessionId: String): List<MessageRecord>
    suspend fun saveMessages(hostId: String, sessionId: String, messages: List<MessageRecord>, replace: Boolean = false)
    suspend fun draft(hostId: String, sessionId: String): String
    suspend fun saveDraft(hostId: String, sessionId: String, text: String)
    suspend fun operations(hostId: String): List<PendingOperation>
    suspend fun saveOperation(operation: PendingOperation)
    suspend fun cursor(hostId: String, domain: String): JsonObject?
    suspend fun saveCursor(hostId: String, domain: String, value: JsonObject)
    suspend fun pushConfiguration(hostId: String): JsonObject?
    suspend fun savePushConfiguration(hostId: String, value: JsonObject)
}

expect class DatabaseDriverFactory {
    fun createCache(): MobileCache
}
