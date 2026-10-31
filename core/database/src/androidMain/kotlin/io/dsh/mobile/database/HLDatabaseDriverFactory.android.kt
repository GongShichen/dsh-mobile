package io.dsh.mobile.database

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.dsh.mobile.database.generated.HLDatabase

actual class HLDatabaseDriverFactory(private val context: Context) {
    actual fun createCache(): HLCache = HLSqlDelightCache(AndroidSqliteDriver(HLDatabase.Schema, context.applicationContext, "dsh-mobile.db"))
}
