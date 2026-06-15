package com.pynapli.server.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/**
 * Durable device -> account ledger backing account-farm detection (TRUST_AND_SAFETY_SPEC).
 * Keyed by a server-side SHA-256 of the client device id, so no raw device identifier is stored.
 */
interface DeviceFarmRepository {
    suspend fun linkDeviceToAccount(
        deviceHash: String,
        userId: UUID,
        occurredAt: Instant,
    )

    /** Distinct accounts this device hash has created at or after [since]. */
    suspend fun countDistinctAccountsForDevice(
        deviceHash: String,
        since: Instant,
    ): Int
}

class JdbcDeviceFarmRepository(
    private val dataSource: DataSource,
) : DeviceFarmRepository {
    override suspend fun linkDeviceToAccount(
        deviceHash: String,
        userId: UUID,
        occurredAt: Instant,
    ): Unit =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        INSERT INTO account_device_links (device_hash, user_id, created_at)
                        VALUES (?, ?, ?)
                        ON CONFLICT (device_hash, user_id) DO NOTHING
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, deviceHash)
                        statement.setObject(2, userId)
                        statement.setObject(3, OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC))
                        statement.executeUpdate()
                    }
            }
        }

    override suspend fun countDistinctAccountsForDevice(
        deviceHash: String,
        since: Instant,
    ): Int =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        SELECT count(DISTINCT user_id)
                        FROM account_device_links
                        WHERE device_hash = ?
                          AND created_at >= ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, deviceHash)
                        statement.setObject(2, OffsetDateTime.ofInstant(since, ZoneOffset.UTC))
                        statement.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
                    }
            }
        }
}
