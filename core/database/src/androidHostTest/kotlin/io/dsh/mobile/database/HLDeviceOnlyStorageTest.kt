package io.dsh.mobile.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.dsh.mobile.database.generated.HLDatabase
import io.dsh.mobile.model.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

class HLDeviceOnlyStorageTest {
    @Test fun upgradePurgesBusinessRowsAndNewBusinessWritesStayInMemory() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        HLDatabase.Schema.create(driver)
        val queries = HLDatabase(driver).hLCacheQueries
        queries.upsertSession("host", "legacy", 1, "{}")
        queries.upsertMessage("host", "legacy", "old", 1, "{}")
        queries.upsertDraft("host", "legacy", "old draft")
        queries.upsertOperation("host", "legacy", "{}")
        queries.upsertCursor("host", "legacy", "{}")
        queries.upsertPreference("selected.session.host", "legacy")
        val cache = HLSqlDelightCache(driver)
        cache.saveHost(HLDesktopHost("host", "endpoint", "Computer"))
        cache.replaceSessions("host", listOf(HLSessionSummary("session", "title")))
        cache.saveMessages("host", "session", listOf(HLMessageRecord("one", "session", "user", "private", sequence = 1)))
        cache.saveDraft("host", "session", "unsent")
        cache.saveOperation(HLPendingOperation("op", "host", "sessions.prompt", buildJsonObject {}, HLOperationStatus.DISPATCHED))
        cache.saveCursor("host", "session", buildJsonObject { put("cursor", 1) })
        cache.savePreference("selected.session.host", "session")
        assertEquals(1, cache.sessions("host").size)
        assertEquals("unsent", cache.draft("host", "session"))
        assertTrue(queries.selectSessions("host").executeAsList().isEmpty())
        assertTrue(queries.selectMessages("host", "session").executeAsList().isEmpty())
        assertTrue(queries.selectMessages("host", "legacy").executeAsList().isEmpty())
        assertNull(queries.selectDraft("host", "session").executeAsOneOrNull())
        assertNull(queries.selectDraft("host", "legacy").executeAsOneOrNull())
        assertTrue(queries.selectOperations("host").executeAsList().isEmpty())
        assertNull(queries.selectCursor("host", "session").executeAsOneOrNull())
        assertNull(queries.selectPreference("selected.session.host").executeAsOneOrNull())
        val reopened = HLSqlDelightCache(driver)
        assertEquals(1, reopened.hosts().size)
        assertTrue(reopened.sessions("host").isEmpty())
        assertTrue(reopened.messages("host", "session").isEmpty())
        assertTrue(reopened.operations("host").isEmpty())
        assertEquals("", reopened.draft("host", "session"))
        driver.close()
    }
}
