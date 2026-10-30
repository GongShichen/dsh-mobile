package io.dsh.mobile.database

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.dsh.mobile.database.generated.MobileDatabase

actual class DatabaseDriverFactory(private val context: Context) {
    actual fun createCache(): MobileCache = SqlDelightMobileCache(AndroidSqliteDriver(MobileDatabase.Schema, context.applicationContext, "dsh-mobile.db"))
}
