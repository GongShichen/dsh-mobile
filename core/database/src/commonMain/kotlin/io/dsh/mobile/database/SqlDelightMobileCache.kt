package io.dsh.mobile.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.QueryResult
import io.dsh.mobile.database.generated.MobileDatabase
import io.dsh.mobile.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Only paired computer/settings data is durable. Desktop business state lives in memory. */
class SqlDelightMobileCache(private val driver: SqlDriver) : MobileCache {
    private val database = MobileDatabase(driver)
    private val queries = database.cacheQueries
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var purged = false
    private val preferences = mutableMapOf<String, String>()
    private val sessionRows = mutableMapOf<String, List<SessionSummary>>()
    private val messageRows = mutableMapOf<Pair<String, String>, List<MessageRecord>>()
    private val drafts = mutableMapOf<Pair<String, String>, String>()
    private val operationRows = mutableMapOf<Pair<String, String>, PendingOperation>()
    private val cursors = mutableMapOf<Pair<String, String>, JsonObject>()

    private fun pragma(sql: String) {
        // Android SQLiteStatement.execute rejects PRAGMAs that return rows.
        driver.executeQuery(null, sql, { cursor ->
            while (cursor.next().value) { /* Drain the result so the PRAGMA actually executes. */ }
            QueryResult.Value(Unit)
        }, 0).value
    }

    private suspend fun <T> access(block: () -> T): T = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (!purged) {
                // Remove older releases' business cache without touching pairing or push setup.
                pragma("PRAGMA secure_delete = ON")
                database.transaction {
                    for (table in listOf("cached_session", "message_record", "draft", "pending_operation", "domain_cursor")) driver.execute(null, "DELETE FROM $table", 0)
                    driver.execute(null, "DELETE FROM preference WHERE preference_key <> 'selected.host'", 0)
                }
                pragma("PRAGMA wal_checkpoint(TRUNCATE)")
                purged = true
            }
            block()
        }
    }
    override suspend fun preference(key: String): String? = access { if (key == "selected.host") queries.selectPreference(key).executeAsOneOrNull() else preferences[key] }
    override suspend fun savePreference(key: String, value: String?) = access {
        if (key == "selected.host") { if (value == null) queries.deletePreference(key) else queries.upsertPreference(key, value) }
        else if (value == null) preferences.remove(key) else preferences[key] = value
        Unit
    }
    override suspend fun hosts() = access { queries.selectHosts().executeAsList().map { json.decodeFromString<DesktopHost>(it) } }
    override suspend fun saveHost(host: DesktopHost) = access { queries.upsertHost(host.id, json.encodeToString(host)); Unit }
    override suspend fun removeHost(hostId: String) = access {
        database.transaction {
            queries.deleteHost(hostId); queries.deletePushConfiguration(hostId)
            if (queries.selectPreference("selected.host").executeAsOneOrNull() == hostId) queries.deletePreference("selected.host")
        }
        sessionRows.remove(hostId); messageRows.keys.removeAll { it.first == hostId }; drafts.keys.removeAll { it.first == hostId }
        operationRows.keys.removeAll { it.first == hostId }; cursors.keys.removeAll { it.first == hostId }; preferences.remove("selected.session.$hostId")
        Unit
    }
    override suspend fun sessions(hostId: String) = access { sessionRows[hostId].orEmpty() }
    override suspend fun replaceSessions(hostId: String, sessions: List<SessionSummary>) = access { sessionRows[hostId] = sessions.toList() }
    override suspend fun messages(hostId: String, sessionId: String) = access { messageRows[hostId to sessionId].orEmpty() }
    override suspend fun saveMessages(hostId: String, sessionId: String, messages: List<MessageRecord>, replace: Boolean) = access {
        val key = hostId to sessionId
        messageRows[key] = if (replace) messages.filter { it.sequence != null } else (messageRows[key].orEmpty() + messages.filter { it.sequence != null }).associateBy { it.id }.values.sortedBy { it.sequence }
    }
    override suspend fun draft(hostId: String, sessionId: String) = access { drafts[hostId to sessionId].orEmpty() }
    override suspend fun saveDraft(hostId: String, sessionId: String, text: String) = access { drafts[hostId to sessionId] = text }
    override suspend fun operations(hostId: String) = access { operationRows.values.filter { it.hostId == hostId } }
    override suspend fun saveOperation(operation: PendingOperation) = access { operationRows[operation.hostId to operation.id] = operation }
    override suspend fun cursor(hostId: String, domain: String) = access { cursors[hostId to domain] }
    override suspend fun saveCursor(hostId: String, domain: String, value: JsonObject) = access { cursors[hostId to domain] = value }
    override suspend fun pushConfiguration(hostId: String) = access { queries.selectPushConfiguration(hostId).executeAsOneOrNull()?.let { json.decodeFromString<JsonObject>(it) } }
    override suspend fun savePushConfiguration(hostId: String, value: JsonObject) = access { queries.upsertPushConfiguration(hostId, json.encodeToString(value)); Unit }
}
