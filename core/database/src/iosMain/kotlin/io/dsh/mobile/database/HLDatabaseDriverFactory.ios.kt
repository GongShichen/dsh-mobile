package io.dsh.mobile.database

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import io.dsh.mobile.database.generated.HLDatabase

actual class HLDatabaseDriverFactory {
    actual fun createCache(): HLCache = HLSqlDelightCache(NativeSqliteDriver(HLDatabase.Schema, "dsh-mobile.db"))
}
