package io.dsh.mobile.database

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.dsh.mobile.database.generated.MobileDatabase
import io.dsh.mobile.model.DesktopHost
import io.dsh.mobile.model.SessionSummary
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
class AndroidStorageTest {
    @Test fun returningPragmaNeedsQueryAndDeviceStoreOpensWithoutSqlFailure() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "storage-regression-${System.nanoTime()}.db"
        val driver = AndroidSqliteDriver(MobileDatabase.Schema, context, name)
        try {
            val oldFailure = assertFails { driver.execute(null, "PRAGMA wal_checkpoint(TRUNCATE)", 0) }
            assertTrue(oldFailure.message.orEmpty().contains("query", ignoreCase = true), oldFailure.toString())
            val store = SqlDelightMobileCache(driver)
            assertTrue(store.hosts().isEmpty())
            store.saveHost(DesktopHost("desktop", "endpoint", "Test computer"))
            assertEquals("desktop", store.hosts().single().id)
            store.replaceSessions("desktop", listOf(SessionSummary("s", "memory only")))
            assertTrue(MobileDatabase(driver).cacheQueries.selectSessions("desktop").executeAsList().isEmpty())
            assertEquals(1, store.sessions("desktop").size)
        } finally { driver.close(); context.deleteDatabase(name) }
    }
}
