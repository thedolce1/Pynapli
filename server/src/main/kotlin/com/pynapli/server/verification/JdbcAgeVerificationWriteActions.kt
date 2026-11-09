package com.pynapli.server.verification

import com.pynapli.server.db.inTransaction
import com.pynapli.server.db.withConnection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

internal class JdbcAgeVerificationWriteActions(
    private val dataSource: DataSource,
) {
    suspend fun createPending(
        verificationId: UUID,
        userId: UUID,
        market: String,
        method: String,
        provider: String,
        providerReference: String?,
        requestedAt: Instant,
    ): AgeVerificationRecord =
        dataSource.withConnection { connection ->
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
                        rs.toAgeVerificationRecord()
                    }
                }
        }

    suspend fun recordCallback(
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
        dataSource.withConnection { connection ->
            connection.inTransaction {
                val record =
                    upsertCallback(
                        verificationId = verificationId,
                        userId = userId,
                        market = market,
                        method = method,
                        provider = provider,
                        providerReference = providerReference,
                        status = status,
                        verifiedAt = verifiedAt,
                        expiresAt = expiresAt,
                        reviewerId = reviewerId,
                        reason = reason,
                        reviewedAt = reviewedAt,
                    )
                if (status == AGE_STATUS_VERIFIED && verifiedAt != null) {
                    updateProfileAgeVerifiedAt(userId, verifiedAt)
                }
                record
            }
        }

    suspend fun review(
        verificationId: UUID,
        status: String,
        verifiedAt: Instant?,
        expiresAt: Instant?,
        reviewerId: String,
        reason: String?,
        reviewedAt: Instant,
    ): AgeVerificationRecord? =
        dataSource.withConnection { connection ->
            connection.inTransaction {
                val record =
                    updateReview(
                        verificationId = verificationId,
                        status = status,
                        verifiedAt = verifiedAt,
                        expiresAt = expiresAt,
                        reviewerId = reviewerId,
                        reason = reason,
                        reviewedAt = reviewedAt,
                    )
                if (record != null && status == AGE_STATUS_VERIFIED && verifiedAt != null) {
                    updateProfileAgeVerifiedAt(record.userId, verifiedAt)
                }
                record
            }
        }

    private fun java.sql.Connection.upsertCallback(
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
        prepareStatement(
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
                rs.toAgeVerificationRecord()
            }
        }

    private fun java.sql.Connection.updateReview(
        verificationId: UUID,
        status: String,
        verifiedAt: Instant?,
        expiresAt: Instant?,
        reviewerId: String,
        reason: String?,
        reviewedAt: Instant,
    ): AgeVerificationRecord? =
        prepareStatement(
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
            statement.executeQuery().use { rs -> if (rs.next()) rs.toAgeVerificationRecord() else null }
        }

    private fun java.sql.Connection.updateProfileAgeVerifiedAt(
        userId: UUID,
        verifiedAt: Instant,
    ) {
        prepareStatement(
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
}
