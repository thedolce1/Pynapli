package com.pynapli.server.verification

import java.sql.Connection

internal fun <T> Connection.runAgeVerificationTransaction(block: Connection.() -> T): T {
    val previousAutoCommit = autoCommit
    autoCommit = false
    return try {
        val result = block()
        commit()
        result
    } catch (error: Throwable) {
        rollback()
        throw error
    } finally {
        autoCommit = previousAutoCommit
    }
}
