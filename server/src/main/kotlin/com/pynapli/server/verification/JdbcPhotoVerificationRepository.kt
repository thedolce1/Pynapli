package com.pynapli.server.verification

import com.pynapli.server.db.inTransaction
import com.pynapli.server.db.lockForTransaction
import com.pynapli.server.db.withConnection
import com.pynapli.server.moderation.insertAdminAuditLog
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcPhotoVerificationRepository(
    private val dataSource: DataSource,
) : PhotoVerificationRepository {
    override suspend fun facePhotoVerifiedAt(userId: UUID): Instant? =
        dataSource.withConnection { connection -> connection.facePhotoVerifiedAt(userId) }

    override suspend fun find(verificationId: UUID): PhotoVerificationRecord? =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement("SELECT $COLUMNS FROM photo_verifications WHERE id = ?")
                .use { statement ->
                    statement.setObject(1, verificationId)
                    statement.executeQuery().use { rs -> if (rs.next()) rs.toRecord() else null }
                }
        }

    override suspend fun latest(userId: UUID): PhotoVerificationRecord? =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement("SELECT $COLUMNS FROM photo_verifications WHERE user_id = ? ORDER BY created_at DESC, id DESC LIMIT 1")
                .use { statement ->
                    statement.setObject(1, userId)
                    statement.executeQuery().use { rs -> if (rs.next()) rs.toRecord() else null }
                }
        }

    override suspend fun issueChallenge(
        verificationId: UUID,
        userId: UUID,
        poseKey: String,
        challengeExpiresAt: Instant,
    ): PhotoVerificationRecord =
        dataSource.withConnection { connection ->
            connection.inTransaction {
                lockForTransaction(LOCK_NAMESPACE, userId.toString())
                prepareStatement(
                    """
                    UPDATE photo_verifications
                    SET status = 'expired', updated_at = now()
                    WHERE user_id = ? AND status = 'challenged'
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.executeUpdate()
                }
                try {
                    prepareStatement(
                        """
                        INSERT INTO photo_verifications (id, user_id, pose_key, status, challenge_expires_at)
                        VALUES (?, ?, ?, 'challenged', ?)
                        RETURNING $COLUMNS
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, verificationId)
                        statement.setObject(2, userId)
                        statement.setString(3, poseKey)
                        statement.setTimestamp(4, Timestamp.from(challengeExpiresAt))
                        statement.executeQuery().use { rs ->
                            rs.next()
                            rs.toRecord()
                        }
                    }
                } catch (error: SQLException) {
                    // The one-open-attempt index: a submission is already waiting for review.
                    if (error.sqlState == UNIQUE_VIOLATION) throw VerificationException("PHOTO_VERIFICATION_PENDING")
                    throw error
                }
            }
        }

    override suspend fun findOpenChallenge(
        userId: UUID,
        verificationId: UUID,
    ): PhotoVerificationRecord? =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement("SELECT $COLUMNS FROM photo_verifications WHERE id = ? AND user_id = ? AND status = 'challenged'")
                .use { statement ->
                    statement.setObject(1, verificationId)
                    statement.setObject(2, userId)
                    statement.executeQuery().use { rs -> if (rs.next()) rs.toRecord() else null }
                }
        }

    override suspend fun findUnusedSelfie(
        userId: UUID,
        mediaAssetId: UUID,
    ): StoredSelfie? =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT m.id, m.storage_provider, m.storage_bucket, m.storage_object_key, m.content_type
                    FROM media_assets m
                    WHERE m.id = ?
                      AND m.owner_user_id = ?
                      AND m.intended_album_kind = 'verification'
                      AND m.deleted_at IS NULL
                      AND NOT EXISTS (SELECT 1 FROM photo_verifications pv WHERE pv.media_asset_id = m.id)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, mediaAssetId)
                    statement.setObject(2, userId)
                    statement.executeQuery().use { rs -> if (rs.next()) rs.toStoredSelfie() else null }
                }
        }

    override suspend fun submit(
        userId: UUID,
        verificationId: UUID,
        mediaAssetId: UUID,
        submittedAt: Instant,
    ): PhotoVerificationRecord? =
        dataSource.withConnection { connection ->
            connection.inTransaction {
                lockForTransaction(LOCK_NAMESPACE, userId.toString())
                val record =
                    prepareStatement(
                        """
                        UPDATE photo_verifications
                        SET status = 'pending',
                            media_asset_id = ?,
                            submitted_at = ?,
                            updated_at = now()
                        WHERE id = ? AND user_id = ? AND status = 'challenged'
                        RETURNING $COLUMNS
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, mediaAssetId)
                        statement.setTimestamp(2, Timestamp.from(submittedAt))
                        statement.setObject(3, verificationId)
                        statement.setObject(4, userId)
                        statement.executeQuery().use { rs -> if (rs.next()) rs.toRecord() else null }
                    } ?: return@inTransaction null
                // Uploaded but held: never ready/approved, so no discovery or album query shows it.
                prepareStatement(
                    """
                    UPDATE media_assets
                    SET processing_status = 'uploaded', moderation_status = 'held', updated_at = now()
                    WHERE id = ? AND owner_user_id = ? AND intended_album_kind = 'verification'
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, mediaAssetId)
                    statement.setObject(2, userId)
                    statement.executeUpdate()
                }
                record
            }
        }

    override suspend fun recordFaceCount(
        verificationId: UUID,
        faceCount: Int,
    ) {
        dataSource.withConnection { connection ->
            connection
                .prepareStatement("UPDATE photo_verifications SET face_count = ?, updated_at = now() WHERE id = ?")
                .use { statement ->
                    statement.setInt(1, faceCount)
                    statement.setObject(2, verificationId)
                    statement.executeUpdate()
                }
        }
    }

    override suspend fun rejectAutomatically(
        verificationId: UUID,
        faceCount: Int,
        reason: String,
        decidedAt: Instant,
        retryAvailableAt: Instant,
    ): PhotoVerificationRecord? =
        dataSource.withConnection { connection ->
            connection.decide(
                verificationId = verificationId,
                status = PhotoVerificationRowStatus.REJECTED,
                decisionSource = DECISION_SOURCE_FACE_CHECK,
                reviewerId = null,
                reason = reason,
                decidedAt = decidedAt,
                retryAvailableAt = retryAvailableAt,
                faceCount = faceCount,
            )
        }

    override suspend fun listPending(
        limit: Int,
        after: PhotoVerificationQueueCursor?,
    ): List<PendingPhotoVerification> =
        dataSource.withConnection { connection ->
            val cursorClause = if (after == null) "" else "AND (pv.submitted_at, pv.id) > (?, ?)"
            connection
                .prepareStatement(
                    """
                    SELECT pv.id, pv.user_id, pv.pose_key, pv.submitted_at, pv.face_count, pv.media_asset_id,
                           p.display_name,
                           COALESCE(photos.ids, ARRAY[]::uuid[]) AS profile_photo_ids
                    FROM photo_verifications pv
                    LEFT JOIN profiles p ON p.user_id = pv.user_id
                    LEFT JOIN LATERAL (
                        SELECT array_agg(media_asset_id ORDER BY sort_order, added_at DESC) AS ids
                        FROM (
                            SELECT mai.media_asset_id, mai.sort_order, mai.added_at
                            FROM media_albums ma
                            JOIN media_album_items mai ON mai.album_id = ma.id AND mai.removed_at IS NULL
                            JOIN media_assets m ON m.id = mai.media_asset_id AND m.deleted_at IS NULL
                            WHERE ma.owner_user_id = pv.user_id
                              AND ma.kind = 'profile'
                              AND ma.deleted_at IS NULL
                              AND m.processing_status = 'ready'
                              AND m.moderation_status = 'approved'
                            ORDER BY mai.sort_order, mai.added_at DESC
                            LIMIT 6
                        ) ranked
                    ) photos ON true
                    WHERE pv.status = 'pending'
                      $cursorClause
                    ORDER BY pv.submitted_at ASC, pv.id ASC
                    LIMIT ?
                    """.trimIndent(),
                ).use { statement ->
                    var index = 1
                    if (after != null) {
                        statement.setTimestamp(index++, Timestamp.from(after.submittedAt))
                        statement.setObject(index++, after.verificationId)
                    }
                    statement.setInt(index, limit)
                    statement.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    PendingPhotoVerification(
                                        verificationId = rs.getObject("id", UUID::class.java),
                                        userId = rs.getObject("user_id", UUID::class.java),
                                        displayName = rs.getString("display_name"),
                                        poseKey = rs.getString("pose_key"),
                                        submittedAt = rs.getTimestamp("submitted_at").toInstant(),
                                        faceCount = rs.getObject("face_count") as Int?,
                                        selfieMediaAssetId = rs.getObject("media_asset_id", UUID::class.java),
                                        profilePhotoMediaAssetIds =
                                            (rs.getArray("profile_photo_ids").array as Array<*>).map { it as UUID },
                                    ),
                                )
                            }
                        }
                    }
                }
        }

    override suspend fun recordQueueView(
        actorId: String,
        reason: String,
        itemCount: Int,
    ) {
        dataSource.withConnection { connection ->
            connection.insertAdminAuditLog(
                actorId = actorId,
                action = "photo_verification.queue_view",
                targetType = "photo_verification_queue",
                targetId = null,
                reason = reason,
                metadataJson = buildJsonObject { put("itemCount", itemCount) }.toString(),
            )
        }
    }

    override suspend fun approve(
        verificationId: UUID,
        reviewerId: String,
        reason: String?,
        decidedAt: Instant,
    ): PhotoVerificationRecord? =
        dataSource.withConnection { connection ->
            connection.inTransaction {
                val record =
                    decide(
                        verificationId = verificationId,
                        status = PhotoVerificationRowStatus.APPROVED,
                        decisionSource = DECISION_SOURCE_ADMIN,
                        reviewerId = reviewerId,
                        reason = reason,
                        decidedAt = decidedAt,
                        retryAvailableAt = null,
                        faceCount = null,
                    ) ?: return@inTransaction null
                prepareStatement(
                    """
                    UPDATE profiles
                    SET face_photo_verified_at = ?, verified_at = ?, updated_at = now()
                    WHERE user_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(decidedAt))
                    statement.setTimestamp(2, Timestamp.from(decidedAt))
                    statement.setObject(3, record.userId)
                    statement.executeUpdate()
                }
                auditDecision("photo_verification.approve", record, reviewerId, reason)
                record
            }
        }

    override suspend fun reject(
        verificationId: UUID,
        reviewerId: String,
        reason: String,
        decidedAt: Instant,
        retryAvailableAt: Instant,
    ): PhotoVerificationRecord? =
        dataSource.withConnection { connection ->
            connection.inTransaction {
                val record =
                    decide(
                        verificationId = verificationId,
                        status = PhotoVerificationRowStatus.REJECTED,
                        decisionSource = DECISION_SOURCE_ADMIN,
                        reviewerId = reviewerId,
                        reason = reason,
                        decidedAt = decidedAt,
                        retryAvailableAt = retryAvailableAt,
                        faceCount = null,
                    ) ?: return@inTransaction null
                auditDecision("photo_verification.reject", record, reviewerId, reason)
                record
            }
        }

    override suspend fun revoke(
        userId: UUID,
        reviewerId: String,
        reason: String,
        revokedAt: Instant,
        retryAvailableAt: Instant,
    ): PhotoVerificationRevocation? =
        dataSource.withConnection { connection ->
            connection.inTransaction {
                lockForTransaction(LOCK_NAMESPACE, userId.toString())
                val cleared =
                    prepareStatement(
                        """
                        UPDATE profiles
                        SET face_photo_verified_at = NULL, verified_at = NULL, updated_at = now()
                        WHERE user_id = ? AND face_photo_verified_at IS NOT NULL
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, userId)
                        statement.executeUpdate()
                    }
                if (cleared == 0) return@inTransaction null
                val record =
                    prepareStatement(
                        """
                        UPDATE photo_verifications
                        SET status = 'revoked',
                            reviewer_id = ?,
                            decision_reason = ?,
                            retry_available_at = ?,
                            updated_at = now()
                        WHERE id = (
                            SELECT id FROM photo_verifications
                            WHERE user_id = ? AND status = 'approved'
                            ORDER BY decided_at DESC NULLS LAST, created_at DESC
                            LIMIT 1
                        )
                        RETURNING $COLUMNS
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, reviewerId)
                        statement.setString(2, reason)
                        statement.setTimestamp(3, Timestamp.from(retryAvailableAt))
                        statement.setObject(4, userId)
                        statement.executeQuery().use { rs -> if (rs.next()) rs.toRecord() else null }
                    }
                insertAdminAuditLog(
                    actorId = reviewerId,
                    action = "photo_verification.revoke",
                    targetType = "user",
                    targetId = userId,
                    reason = reason,
                    metadataJson =
                        buildJsonObject {
                            put("verificationId", record?.verificationId?.toString())
                            put("revokedAt", revokedAt.toString())
                        }.toString(),
                )
                PhotoVerificationRevocation(userId = userId, verificationId = record?.verificationId)
            }
        }

    override suspend fun selfiesDueForPurge(
        decidedBefore: Instant,
        abandonedBefore: Instant,
        limit: Int,
    ): List<StoredSelfie> =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT m.id, m.storage_provider, m.storage_bucket, m.storage_object_key, m.content_type
                    FROM media_assets m
                    WHERE m.intended_album_kind = 'verification'
                      AND (
                          EXISTS (
                              SELECT 1 FROM photo_verifications pv
                              WHERE pv.media_asset_id = m.id
                                AND pv.decided_at IS NOT NULL
                                AND pv.decided_at < ?
                          )
                          OR (
                              m.created_at < ?
                              AND NOT EXISTS (SELECT 1 FROM photo_verifications pv WHERE pv.media_asset_id = m.id)
                          )
                      )
                    ORDER BY m.created_at ASC
                    LIMIT ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(decidedBefore))
                    statement.setTimestamp(2, Timestamp.from(abandonedBefore))
                    statement.setInt(3, limit)
                    statement.executeQuery().use { rs ->
                        buildList { while (rs.next()) add(rs.toStoredSelfie()) }
                    }
                }
        }

    override suspend fun deleteSelfie(
        mediaAssetId: UUID,
        purgedAt: Instant,
    ) {
        dataSource.withConnection { connection ->
            connection.inTransaction {
                prepareStatement(
                    """
                    UPDATE photo_verifications
                    SET media_asset_id = NULL, selfie_purged_at = ?, updated_at = now()
                    WHERE media_asset_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(purgedAt))
                    statement.setObject(2, mediaAssetId)
                    statement.executeUpdate()
                }
                prepareStatement("DELETE FROM media_assets WHERE id = ? AND intended_album_kind = 'verification'").use { statement ->
                    statement.setObject(1, mediaAssetId)
                    statement.executeUpdate()
                }
            }
        }
    }

    private fun Connection.facePhotoVerifiedAt(userId: UUID): Instant? =
        prepareStatement("SELECT face_photo_verified_at FROM profiles WHERE user_id = ?").use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.getTimestamp(1)?.toInstant() else null }
        }

    private fun Connection.decide(
        verificationId: UUID,
        status: String,
        decisionSource: String,
        reviewerId: String?,
        reason: String?,
        decidedAt: Instant,
        retryAvailableAt: Instant?,
        faceCount: Int?,
    ): PhotoVerificationRecord? =
        prepareStatement(
            """
            UPDATE photo_verifications
            SET status = ?,
                decision_source = ?,
                reviewer_id = ?,
                decision_reason = ?,
                decided_at = ?,
                retry_available_at = ?,
                face_count = COALESCE(?, face_count),
                updated_at = now()
            WHERE id = ? AND status = 'pending'
            RETURNING $COLUMNS
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, status)
            statement.setString(2, decisionSource)
            statement.setString(3, reviewerId)
            statement.setString(4, reason)
            statement.setTimestamp(5, Timestamp.from(decidedAt))
            statement.setTimestamp(6, retryAvailableAt?.let(Timestamp::from))
            if (faceCount == null) statement.setNull(7, java.sql.Types.INTEGER) else statement.setInt(7, faceCount)
            statement.setObject(8, verificationId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.toRecord() else null }
        }

    private fun Connection.auditDecision(
        action: String,
        record: PhotoVerificationRecord,
        reviewerId: String,
        reason: String?,
    ) {
        insertAdminAuditLog(
            actorId = reviewerId,
            action = action,
            targetType = "photo_verification",
            targetId = record.verificationId,
            reason = reason,
            metadataJson =
                buildJsonObject {
                    put("userId", record.userId.toString())
                    put("poseKey", record.poseKey)
                    put("status", record.status)
                    put("decidedAt", record.decidedAt?.toString())
                }.toString(),
        )
    }

    private fun ResultSet.toRecord(): PhotoVerificationRecord =
        PhotoVerificationRecord(
            verificationId = getObject("id", UUID::class.java),
            userId = getObject("user_id", UUID::class.java),
            poseKey = getString("pose_key"),
            status = getString("status"),
            challengeExpiresAt = getTimestamp("challenge_expires_at").toInstant(),
            mediaAssetId = getObject("media_asset_id", UUID::class.java),
            faceCount = getObject("face_count") as Int?,
            submittedAt = getTimestamp("submitted_at")?.toInstant(),
            decidedAt = getTimestamp("decided_at")?.toInstant(),
            decisionSource = getString("decision_source"),
            reviewerId = getString("reviewer_id"),
            decisionReason = getString("decision_reason"),
            retryAvailableAt = getTimestamp("retry_available_at")?.toInstant(),
            createdAt = getTimestamp("created_at").toInstant(),
        )

    private fun ResultSet.toStoredSelfie(): StoredSelfie =
        StoredSelfie(
            mediaAssetId = getObject("id", UUID::class.java),
            storageProvider = getString("storage_provider"),
            storageBucket = getString("storage_bucket"),
            storageObjectKey = getString("storage_object_key"),
            contentType = getString("content_type"),
        )

    private companion object {
        const val LOCK_NAMESPACE = "photo_verification"
        const val UNIQUE_VIOLATION = "23505"
        const val DECISION_SOURCE_ADMIN = "admin"
        const val DECISION_SOURCE_FACE_CHECK = "face_check"
        const val COLUMNS =
            "id, user_id, pose_key, status, challenge_expires_at, media_asset_id, face_count, submitted_at, " +
                "decided_at, decision_source, reviewer_id, decision_reason, retry_available_at, created_at"
    }
}
