package io.dsh.mobile.database

import app.cash.sqldelight.db.SqlDriver
import io.dsh.mobile.database.generated.MobileDatabase
import io.dsh.mobile.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class SqlDelightMobileCache(driver: SqlDriver) : MobileCache {
    private val database = MobileDatabase(driver)
    private val queries = database.cacheQueries
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private suspend fun <T> access(block: () -> T): T = withContext(Dispatchers.Default) { mutex.withLock { block() } }

    override suspend fun preference(key: String): String? = access { queries.selectPreference(key).executeAsOneOrNull() }
    override suspend fun savePreference(key: String, value: String?) = access {
        if (value == null) queries.deletePreference(key) else queries.upsertPreference(key, value)
        Unit
    }

    override suspend fun hosts() = access { queries.selectHosts().executeAsList().map { json.decodeFromString<DesktopHost>(it) } }
    override suspend fun saveHost(host: DesktopHost) = access { queries.upsertHost(host.id, json.encodeToString(host)); Unit }
    override suspend fun removeHost(hostId: String) = access {
        database.transaction {
            queries.deleteHost(hostId); queries.deleteSessions(hostId); queries.deleteHostMessages(hostId)
            queries.deleteHostDrafts(hostId); queries.deleteHostOperations(hostId); queries.deleteHostCursors(hostId)
            queries.deletePushConfiguration(hostId)
            queries.deletePreference("selected.session.$hostId")
            if (queries.selectPreference("selected.host").executeAsOneOrNull() == hostId) queries.deletePreference("selected.host")
        }
    }
    override suspend fun sessions(hostId: String) = access { queries.selectSessions(hostId).executeAsList().map { json.decodeFromString<SessionSummary>(it) } }
    override suspend fun replaceSessions(hostId: String, sessions: List<SessionSummary>) = access {
        database.transaction {
            queries.deleteSessions(hostId)
            sessions.forEach { queries.upsertSession(hostId, it.id, it.updatedAt, json.encodeToString(it)) }
        }
    }
    override suspend fun messages(hostId: String, sessionId: String) = access { queries.selectMessages(hostId, sessionId).executeAsList().map { json.decodeFromString<MessageRecord>(it) } }
    override suspend fun saveMessages(hostId: String, sessionId: String, messages: List<MessageRecord>, replace: Boolean) = access {
        database.transaction {
            if (replace) queries.deleteSessionMessages(hostId, sessionId)
            // Transient assistant chunks are reconstructed from follow's baseline, never journalled here.
            messages.filter { it.sequence != null }.forEach { queries.upsertMessage(hostId, sessionId, it.id, it.sequence, json.encodeToString(it)) }
        }
    }
    override suspend fun draft(hostId: String, sessionId: String) = access { queries.selectDraft(hostId, sessionId).executeAsOneOrNull().orEmpty() }
    override suspend fun saveDraft(hostId: String, sessionId: String, text: String) = access { queries.upsertDraft(hostId, sessionId, text); Unit }
    override suspend fun operations(hostId: String) = access { queries.selectOperations(hostId).executeAsList().map { json.decodeFromString<PendingOperation>(it) } }
    override suspend fun saveOperation(operation: PendingOperation) = access { queries.upsertOperation(operation.hostId, operation.id, json.encodeToString(operation)); Unit }
    override suspend fun cursor(hostId: String, domain: String) = access { queries.selectCursor(hostId, domain).executeAsOneOrNull()?.let { json.decodeFromString<JsonObject>(it) } }
    override suspend fun saveCursor(hostId: String, domain: String, value: JsonObject) = access { queries.upsertCursor(hostId, domain, json.encodeToString(value)); Unit }
    override suspend fun pushConfiguration(hostId: String) = access { queries.selectPushConfiguration(hostId).executeAsOneOrNull()?.let { json.decodeFromString<JsonObject>(it) } }
    override suspend fun savePushConfiguration(hostId: String, value: JsonObject) = access { queries.upsertPushConfiguration(hostId, json.encodeToString(value)); Unit }
}
