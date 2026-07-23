package com.pynapli.server.appeals

import com.pynapli.server.moderation.insertAdminAuditLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.postgresql.util.PSQLException
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID
import javax.sql.DataSource

class JdbcAppealRepository(
    private val dataSource: DataSource,
) : AppealRepository,
    AppealEligibilityRepository {
    override suspend fun createAppeal(
        userId: UUID,
        subjectType: String,
        subjectRef: String?,
        reason: String,
    ): AppealRecord? =
        withContext(Dispatchers.IO) {
            val appealId = UUID.randomUUID()
            try {
                dataSource.connection.use { connection ->
                    connection
                        .prepareStatement(
                            """
                            INSERT INTO appeals (id, user_id, subject_type, subject_ref, reason, status)
                            VALUES (?, ?, ?, ?, ?, 'pending')
                            RETURNING id, user_id, subject_type, subject_ref, status,
                                      created_at, resolved_at, resolved_by_admin_id, resolution_note
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setObject(1, appealId)
                            statement.setObject(2, userId)
                            statement.setString(3, subjectType)
                            statement.setString(4, subjectRef)
                            statement.setString(5, reason)
                            statement.executeQuery().use { rs ->
                                check(rs.next()) { "Appeal insert returned no row." }
                                rs.toAppealRecord()
                            }
                        }
                }
            } catch (error: PSQLException) {
                // Partial unique index violation => an open appeal already exists for this subject.
                if (error.sqlState == UNIQUE_VIOLATION_SQL_STATE) return@withContext null
                throw error
            }
        }

    override suspend fun listAppealsForUser(userId: UUID): List<AppealRecord> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        SELECT id, user_id, subject_type, subject_ref, status,
                               created_at, resolved_at, resolved_by_admin_id, resolution_note
                        FROM appeals
                        WHERE user_id = ?
                        ORDER BY created_at DESC, id DESC
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, userId)
                        statement.executeQuery().use { rs ->
                            buildList { while (rs.next()) add(rs.toAppealRecord()) }
                        }
                    }
            }
        }

    override suspend fun listPendingAppeals(
        cursor: AppealCursor?,
        limit: Int,
    ): List<AppealRecord> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        SELECT id, user_id, subject_type, subject_ref, status,
                               created_at, resolved_at, resolved_by_admin_id, resolution_note
                        FROM appeals
                        WHERE status = 'pending'
                          AND (
                            ?::timestamptz IS NULL
                            OR (created_at, id) < (?::timestamptz, ?::uuid)
                          )
                        ORDER BY created_at DESC, id DESC
                        LIMIT ?
                        """.trimIndent(),
                    ).use { statement ->
                        val cursorTs = cursor?.createdAt?.let { Timestamp.from(it) }
                        statement.setObject(1, cursorTs)
                        statement.setObject(2, cursorTs)
                        statement.setObject(3, cursor?.id)
                        statement.setInt(4, limit)
                        statement.executeQuery().use { rs ->
                            buildList { while (rs.next()) add(rs.toAppealRecord()) }
                        }
                    }
            }
        }

    override suspend fun getAppeal(id: UUID): AppealRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        SELECT id, user_id, subject_type, subject_ref, status,
                               created_at, resolved_at, resolved_by_admin_id, resolution_note
                        FROM appeals
                        WHERE id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, id)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) rs.toAppealRecord() else null
                        }
                    }
            }
        }

    override suspend fun resolveAppeal(
        id: UUID,
        status: String,
        adminId: String,
        note: String,
    ): AppealRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                val previousAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    val resolved =
                        connection
                            .prepareStatement(
                                """
                                UPDATE appeals
                                SET status = ?,
                                    resolved_at = now(),
                                    resolved_by_admin_id = ?,
                                    resolution_note = ?
                                WHERE id = ?
                                  AND status = 'pending'
                                RETURNING id, user_id, subject_type, subject_ref, status,
                                          created_at, resolved_at, resolved_by_admin_id, resolution_note
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setString(1, status)
                                statement.setString(2, adminId)
                                statement.setString(3, note)
                                statement.setObject(4, id)
                                statement.executeQuery().use { rs ->
                                    if (rs.next()) rs.toAppealRecord() else null
                                }
                            }
                    if (resolved == null) {
                        connection.rollback()
                        return@use null
                    }
                    // Audit only the decision + subject — never the appeal reason text.
                    connection.insertAdminAuditLog(
                        actorId = adminId,
                        action = "appeal.$status",
                        targetType = "appeal",
                        targetId = id,
                        reason = note,
                        metadataJson =
                            """{"subjectType":"${resolved.subjectType}","status":"$status"}""",
                    )
                    connection.commit()
                    resolved
                } catch (error: Throwable) {
                    connection.rollback()
                    throw error
                } finally {
                    connection.autoCommit = previousAutoCommit
                }
            }
        }

    override suspend fun riskStateForUser(userId: UUID): AppealRiskState? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        SELECT tier, updated_at
                        FROM account_risk_states
                        WHERE user_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, userId)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) {
                                AppealRiskState(
                                    tier = rs.getString("tier"),
                                    actionedAt = rs.getTimestamp("updated_at").toInstant(),
                                )
                            } else {
                                null
                            }
                        }
                    }
            }
        }

    private fun ResultSet.toAppealRecord(): AppealRecord =
        AppealRecord(
            id = getObject("id", UUID::class.java),
            userId = getObject("user_id", UUID::class.java),
            subjectType = getString("subject_type"),
            subjectRef = getString("subject_ref"),
            status = getString("status"),
            createdAt = getTimestamp("created_at").toInstant(),
            resolvedAt = getTimestamp("resolved_at")?.toInstant(),
            resolvedByAdminId = getString("resolved_by_admin_id"),
            resolutionNote = getString("resolution_note"),
        )

    private companion object {
        const val UNIQUE_VIOLATION_SQL_STATE = "23505"
    }
}
