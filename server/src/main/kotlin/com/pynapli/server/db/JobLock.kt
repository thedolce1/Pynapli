package com.pynapli.server.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.sql.DataSource

/**
 * Makes a periodic job run on one replica at a time. Every replica starts the same workers; for
 * jobs that call external services or do bulk work (Play Store sync, media cleanup, retention
 * sweeps), each tick first takes this lock and the replicas that don't get it skip the tick.
 */
interface JobLock {
    /** Runs [block] if no other replica is running [jobName] right now; returns null when skipped. */
    suspend fun <T> runExclusive(
        jobName: String,
        block: suspend () -> T,
    ): T?
}

/** Single-process default (and tests): always runs. */
object UnlockedJobs : JobLock {
    override suspend fun <T> runExclusive(
        jobName: String,
        block: suspend () -> T,
    ): T? = block()
}

/**
 * Postgres session advisory lock held on one pooled connection for the length of the tick. If the
 * replica dies mid-tick its connection closes and Postgres releases the lock, so a crash never
 * leaves a job stuck.
 */
class PostgresJobLock(
    private val dataSource: DataSource,
) : JobLock {
    override suspend fun <T> runExclusive(
        jobName: String,
        block: suspend () -> T,
    ): T? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                val key = lockKey(jobName)
                val acquired =
                    connection.prepareStatement("SELECT pg_try_advisory_lock(?)").use { statement ->
                        statement.setLong(1, key)
                        statement.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
                    }
                if (!acquired) return@use null
                try {
                    block()
                } finally {
                    runCatching {
                        connection.prepareStatement("SELECT pg_advisory_unlock(?)").use { statement ->
                            statement.setLong(1, key)
                            statement.executeQuery().close()
                        }
                    }
                }
            }
        }

    companion object {
        /** A stable 64-bit key per job name, namespaced so it can't collide with other advisory locks. */
        fun lockKey(jobName: String): Long =
            ByteBuffer
                .wrap(MessageDigest.getInstance("SHA-256").digest("pynapli:job:$jobName".toByteArray()))
                .getLong()
    }
}
