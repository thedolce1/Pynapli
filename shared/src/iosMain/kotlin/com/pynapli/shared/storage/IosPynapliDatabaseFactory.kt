package com.pynapli.shared.storage

import app.cash.sqldelight.driver.native.NativeSqliteDriver

class IosPynapliDatabaseFactory {
    fun createDatabase(): PynapliDatabase {
        val driver = NativeSqliteDriver(PynapliDatabase.Schema, "pynapli.db")
        return PynapliDatabase(driver)
    }
}
