package com.pynapli.shared.storage

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

class AndroidPynapliDatabaseFactory(
    private val context: Context
) {
    fun createDatabase(): PynapliDatabase {
        val driver = AndroidSqliteDriver(PynapliDatabase.Schema, context, "pynapli.db")
        return PynapliDatabase(driver)
    }
}
