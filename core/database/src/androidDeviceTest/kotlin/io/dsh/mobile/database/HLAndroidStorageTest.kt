package io.dsh.mobile.database

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.dsh.mobile.database.generated.HLDatabase
import io.dsh.mobile.model.HLDesktopHost
import io.dsh.mobile.model.HLSessionSummary
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
class HLAndroidStorageTest {
    @Test fun returningPragmaNeedsQueryAndDeviceStoreOpensWithoutSqlFailure() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "storage-regression-${System.nanoTime()}.db"
        val driver = AndroidSqliteDriver(HLDatabase.Schema, context, name)
        try {
            val oldFailure = assertFails { driver.execute(null, "PRAGMA wal_checkpoint(TRUNCATE)", 0) }
            assertTrue(oldFailure.message.orEmpty().contains("query", ignoreCase = true), oldFailure.toString())
            val store = HLSqlDelightCache(driver)
            assertTrue(store.hosts().isEmpty())
            store.saveHost(HLDesktopHost("desktop", "endpoint", "Test computer"))
            assertEquals("desktop", store.hosts().single().id)
            store.replaceSessions("desktop", listOf(HLSessionSummary("s", "memory only")))
            assertTrue(HLDatabase(driver).hLCacheQueries.selectSessions("desktop").executeAsList().isEmpty())
            assertEquals(1, store.sessions("desktop").size)
        } finally { driver.close(); context.deleteDatabase(name) }
    }
}
