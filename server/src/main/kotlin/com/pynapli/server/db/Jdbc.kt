package com.pynapli.server.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.util.UUID
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

/**
 * Holds a Postgres advisory lock on `namespace:key` until the current transaction ends. Two
 * transactions that take the same lock run that section one after the other, which closes
 * check-then-insert races that row locks can't (there is no row to lock until one of them inserts
 * it). Must be called inside [inTransaction]: outside one the lock would be released at once.
 */
fun Connection.lockForTransaction(
    namespace: String,
    key: String,
) {
    check(!autoCommit) { "lockForTransaction needs an open transaction" }
    prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))").use { statement ->
        statement.setString(1, transactionLockKey(namespace, key))
        statement.executeQuery().close()
    }
}

/**
 * [lockForTransaction] for an unordered pair of users: both directions (A then B, B then A) take
 * the same lock, so A liking B and B liking A serialise.
 */
fun Connection.lockPairForTransaction(
    namespace: String,
    a: UUID,
    b: UUID,
) = lockForTransaction(namespace, pairLockKey(a, b))

/**
 * Takes several pair locks in one transaction. They are taken in a fixed (sorted) order, so two
 * transactions that need overlapping sets queue behind each other instead of deadlocking.
 */
fun Connection.lockPairsForTransaction(
    namespace: String,
    pairs: Collection<Pair<UUID, UUID>>,
) {
    pairs
        .map { (a, b) -> pairLockKey(a, b) }
        .distinct()
        .sorted()
        .forEach { lockForTransaction(namespace, it) }
}

private fun pairLockKey(
    a: UUID,
    b: UUID,
): String {
    val (first, second) = if (a.toString() <= b.toString()) a to b else b to a
    return "$first:$second"
}

private fun transactionLockKey(
    namespace: String,
    key: String,
): String = "$namespace:$key"
