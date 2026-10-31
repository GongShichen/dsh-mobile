package io.dsh.mobile.database

import io.dsh.mobile.model.HLDesktopHost
import io.dsh.mobile.model.HLMessageRecord
import io.dsh.mobile.model.HLPendingOperation
import io.dsh.mobile.model.HLSessionSummary
import kotlinx.serialization.json.JsonObject

interface HLCache {
    suspend fun preference(key: String): String?
    suspend fun savePreference(key: String, value: String?)
    suspend fun hosts(): List<HLDesktopHost>
    suspend fun saveHost(host: HLDesktopHost)
    suspend fun removeHost(hostId: String)
    suspend fun sessions(hostId: String): List<HLSessionSummary>
    suspend fun replaceSessions(hostId: String, sessions: List<HLSessionSummary>)
    suspend fun messages(hostId: String, sessionId: String): List<HLMessageRecord>
    suspend fun saveMessages(hostId: String, sessionId: String, messages: List<HLMessageRecord>, replace: Boolean = false)
    suspend fun draft(hostId: String, sessionId: String): String
    suspend fun saveDraft(hostId: String, sessionId: String, text: String)
    suspend fun operations(hostId: String): List<HLPendingOperation>
    suspend fun saveOperation(operation: HLPendingOperation)
    suspend fun cursor(hostId: String, domain: String): JsonObject?
    suspend fun saveCursor(hostId: String, domain: String, value: JsonObject)
    suspend fun pushConfiguration(hostId: String): JsonObject?
    suspend fun savePushConfiguration(hostId: String, value: JsonObject)
}

expect class HLDatabaseDriverFactory {
    fun createCache(): HLCache
}
