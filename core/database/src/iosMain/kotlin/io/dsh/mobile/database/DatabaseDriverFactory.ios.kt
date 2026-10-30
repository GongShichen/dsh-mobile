package io.dsh.mobile.database

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import io.dsh.mobile.database.generated.MobileDatabase

actual class DatabaseDriverFactory {
    actual fun createCache(): MobileCache = SqlDelightMobileCache(NativeSqliteDriver(MobileDatabase.Schema, "dsh-mobile.db"))
}
