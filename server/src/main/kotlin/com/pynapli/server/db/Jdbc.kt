package com.pynapli.server.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import javax.sql.DataSource

/**
 * Borrows a pooled connection for [block] and returns it to the pool afterwards. Runs on
 * [Dispatchers.IO] because JDBC blocks: a request coroutine must never hold a Netty event-loop thread
 * while waiting on Postgres.
 */
suspend fun <T> DataSource.withConnection(block: (Connection) -> T): T = withContext(Dispatchers.IO) { connection.use(block) }

/**
 * Runs [block] as one transaction: commit when it returns, roll back when it throws anything
 * (including errors and cancellation), and restore the connection's previous autoCommit either way,
 * so a pooled connection never goes back to the pool mid-transaction.
 */
inline fun <T> Connection.inTransaction(block: Connection.() -> T): T {
    val previousAutoCommit = autoCommit
    autoCommit = false
    try {
        val result = block()
        commit()
        return result
    } catch (error: Throwable) {
        rollback()
        throw error
    } finally {
        autoCommit = previousAutoCommit
    }
}
