package com.pynapli.server.verification

import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcAgeVerificationRepository(
    private val dataSource: DataSource,
) : AgeVerificationRepository {
    override suspend fun getProfileState(userId: UUID): AgeVerificationProfileState? =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT user_id, birthdate, age_verified_at
                    FROM profiles
                    WHERE user_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.executeQuery().use { rs ->
                        if (!rs.next()) return@use null
                        AgeVerificationProfileState(
                            userId = rs.getObject("user_id", UUID::class.java),
                            birthdate = rs.getDate("birthdate")?.toLocalDate(),
                            ageVerifiedAt = rs.getTimestamp("age_verified_at")?.toInstant(),
                        )
                    }
                }
        }

    override suspend fun latestForUser(userId: UUID): AgeVerificationRecord? =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT *
                    FROM age_verifications
                    WHERE user_id = ?
                    ORDER BY created_at DESC, requested_at DESC
                    LIMIT 1
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.executeQuery().use { rs -> if (rs.next()) rs.toRecord() else null }
                }
        }

    override suspend fun findById(verificationId: UUID): AgeVerificationRecord? =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT * FROM age_verifications WHERE id = ?").use { statement ->
                statement.setObject(1, verificationId)
                statement.executeQuery().use { rs -> if (rs.next()) rs.toRecord() else null }
            }
        }

    override suspend fun findByProviderReference(
        provider: String,
        providerReference: String,
    ): AgeVerificationRecord? =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT *
                    FROM age_verifications
                    WHERE provider = ? AND provider_reference = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, provider)
                    statement.setString(2, providerReference)
                    statement.executeQuery().use { rs -> if (rs.next()) rs.toRecord() else null }
                }
        }

    override suspend fun createPending(
        verificationId: UUID,
        userId: UUID,
        market: String,
        method: String,
        provider: String,
        providerReference: String?,
        requestedAt: Instant,
    ): AgeVerificationRecord =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO age_verifications (
                        id, user_id, market, method, provider, provider_reference, status, requested_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, 'pending', ?)
                    RETURNING *
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, verificationId)
                    statement.setObject(2, userId)
                    statement.setString(3, market)
                    statement.setString(4, method)
                    statement.setString(5, provider)
                    statement.setString(6, providerReference)
                    statement.setTimestamp(7, Timestamp.from(requestedAt))
                    statement.executeQuery().use { rs ->
                        require(rs.next()) { "age verification insert returned no row" }
                        rs.toRecord()
                    }
                }
        }

    override suspend fun recordCallback(
        verificationId: UUID,
        userId: UUID,
        market: String,
        method: String,
        provider: String,
        providerReference: String?,
        status: String,
        verifiedAt: Instant?,
        expiresAt: Instant?,
        reviewerId: String,
        reason: String?,
        reviewedAt: Instant,
    ): AgeVerificationRecord =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val record =
                    connection
                        .prepareStatement(
                            """
                            INSERT INTO age_verifications (
                                id, user_id, market, method, provider, provider_reference, status,
                                requested_at, verified_at, expires_at, reviewed_at, reviewer_id, review_reason, updated_at
                            )
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            ON CONFLICT (id) DO UPDATE SET
                                status = EXCLUDED.status,
                                verified_at = EXCLUDED.verified_at,
                                expires_at = EXCLUDED.expires_at,
                                reviewed_at = EXCLUDED.reviewed_at,
                                reviewer_id = EXCLUDED.reviewer_id,
                                review_reason = EXCLUDED.review_reason,
                                updated_at = EXCLUDED.updated_at
                            RETURNING *
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setObject(1, verificationId)
                            statement.setObject(2, userId)
                            statement.setString(3, market)
                            statement.setString(4, method)
                            statement.setString(5, provider)
                            statement.setString(6, providerReference)
                            statement.setString(7, status)
                            statement.setTimestamp(8, Timestamp.from(reviewedAt))
                            statement.setTimestamp(9, verifiedAt?.let { Timestamp.from(it) })
                            statement.setTimestamp(10, expiresAt?.let { Timestamp.from(it) })
                            statement.setTimestamp(11, Timestamp.from(reviewedAt))
                            statement.setString(12, reviewerId)
                            statement.setString(13, reason)
                            statement.setTimestamp(14, Timestamp.from(reviewedAt))
                            statement.executeQuery().use { rs ->
                                require(rs.next()) { "age verification callback returned no row" }
                                rs.toRecord()
                            }
                        }
                if (status == STATUS_VERIFIED && verifiedAt != null) {
                    updateProfileAgeVerifiedAt(connection, userId, verifiedAt)
                }
                connection.commit()
                record
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }

    override suspend fun review(
        verificationId: UUID,
        status: String,
        verifiedAt: Instant?,
        expiresAt: Instant?,
        reviewerId: String,
        reason: String?,
        reviewedAt: Instant,
    ): AgeVerificationRecord? =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val record =
                    connection
                        .prepareStatement(
                            """
                            UPDATE age_verifications
                            SET status = ?,
                                verified_at = ?,
                                expires_at = ?,
                                reviewed_at = ?,
                                reviewer_id = ?,
                                review_reason = ?,
                                updated_at = ?
                            WHERE id = ?
                            RETURNING *
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setString(1, status)
                            statement.setTimestamp(2, verifiedAt?.let { Timestamp.from(it) })
                            statement.setTimestamp(3, expiresAt?.let { Timestamp.from(it) })
                            statement.setTimestamp(4, Timestamp.from(reviewedAt))
                            statement.setString(5, reviewerId)
                            statement.setString(6, reason)
                            statement.setTimestamp(7, Timestamp.from(reviewedAt))
                            statement.setObject(8, verificationId)
                            statement.executeQuery().use { rs -> if (rs.next()) rs.toRecord() else null }
                        }
                if (record != null && status == STATUS_VERIFIED && verifiedAt != null) {
                    updateProfileAgeVerifiedAt(connection, record.userId, verifiedAt)
                }
                connection.commit()
                record
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }

    private fun updateProfileAgeVerifiedAt(
        connection: java.sql.Connection,
        userId: UUID,
        verifiedAt: Instant,
    ) {
        connection
            .prepareStatement(
                """
                UPDATE profiles
                SET age_verified_at = ?, updated_at = now()
                WHERE user_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setTimestamp(1, Timestamp.from(verifiedAt))
                statement.setObject(2, userId)
                statement.executeUpdate()
            }
    }

    private fun ResultSet.toRecord(): AgeVerificationRecord =
        AgeVerificationRecord(
            id = getObject("id", UUID::class.java),
            userId = getObject("user_id", UUID::class.java),
            market = getString("market"),
            method = getString("method"),
            provider = getString("provider"),
            providerReference = getString("provider_reference"),
            status = getString("status"),
            requestedAt = getTimestamp("requested_at").toInstant(),
            verifiedAt = getTimestamp("verified_at")?.toInstant(),
            expiresAt = getTimestamp("expires_at")?.toInstant(),
            reviewedAt = getTimestamp("reviewed_at")?.toInstant(),
            reviewerId = getString("reviewer_id"),
            reviewReason = getString("review_reason"),
        )

    private companion object {
        const val STATUS_VERIFIED = "verified"
    }
}
