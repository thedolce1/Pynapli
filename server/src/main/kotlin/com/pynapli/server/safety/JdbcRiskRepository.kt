package com.pynapli.server.safety

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID
import javax.sql.DataSource

class JdbcRiskRepository(
    private val dataSource: DataSource
) : RiskRepository {
    override suspend fun recordEvent(
        userId: UUID,
        actorUserId: UUID?,
        source: String,
        reason: String,
        delta: Int,
        metadataJson: String,
        eventIdFactory: () -> UUID
    ): RiskMutationRecord = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                val state = connection.upsertRiskState(userId, delta)
                val event = connection.insertRiskEvent(
                    id = eventIdFactory(),
                    userId = userId,
                    actorUserId = actorUserId,
                    source = source,
                    reason = reason,
                    delta = delta,
                    state = state,
                    metadataJson = metadataJson
                )
                connection.commit()
                RiskMutationRecord(event = event, state = state)
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = previousAutoCommit
            }
        }
    }

    override suspend fun getState(userId: UUID): AccountRiskStateRecord? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT user_id,
                       score,
                       tier,
                       last_event_at,
                       last_reviewed_at,
                       updated_at
                FROM account_risk_states
                WHERE user_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) rs.toRiskStateRecord() else null
                }
            }
        }
    }

    private fun Connection.upsertRiskState(userId: UUID, delta: Int): AccountRiskStateRecord {
        val insertScore = "GREATEST(0, LEAST(100, payload.delta))"
        val updateScore = "GREATEST(0, LEAST(100, account_risk_states.score + (SELECT delta FROM payload)))"
        return prepareStatement(
            """
            WITH payload AS (
                SELECT ?::uuid AS user_id,
                       ?::integer AS delta
            )
            INSERT INTO account_risk_states (
                user_id,
                score,
                tier,
                last_event_at,
                updated_at
            )
            SELECT payload.user_id,
                   $insertScore,
                   ${riskTierCase(insertScore)},
                   now(),
                   now()
            FROM payload
            ON CONFLICT (user_id) DO UPDATE
            SET score = $updateScore,
                tier = ${riskTierCase(updateScore)},
                last_event_at = now(),
                updated_at = now()
            RETURNING user_id,
                      score,
                      tier,
                      last_event_at,
                      last_reviewed_at,
                      updated_at
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setInt(2, delta)
            statement.executeQuery().use { rs ->
                rs.next()
                return rs.toRiskStateRecord()
            }
        }
    }

    private fun Connection.insertRiskEvent(
        id: UUID,
        userId: UUID,
        actorUserId: UUID?,
        source: String,
        reason: String,
        delta: Int,
        state: AccountRiskStateRecord,
        metadataJson: String
    ): RiskEventRecord =
        prepareStatement(
            """
            INSERT INTO account_risk_events (
                id,
                user_id,
                actor_user_id,
                source,
                reason,
                delta,
                score_after,
                tier_after,
                metadata
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
            RETURNING id,
                      user_id,
                      actor_user_id,
                      source,
                      reason,
                      delta,
                      score_after,
                      tier_after,
                      metadata::text AS metadata_json,
                      created_at
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, id)
            statement.setObject(2, userId)
            statement.setObject(3, actorUserId)
            statement.setString(4, source)
            statement.setString(5, reason)
            statement.setInt(6, delta)
            statement.setInt(7, state.score)
            statement.setString(8, state.tier)
            statement.setString(9, metadataJson)
            statement.executeQuery().use { rs ->
                rs.next()
                rs.toRiskEventRecord()
            }
        }

    private fun ResultSet.toRiskStateRecord(): AccountRiskStateRecord =
        AccountRiskStateRecord(
            userId = getObject("user_id", UUID::class.java),
            score = getInt("score"),
            tier = getString("tier"),
            lastEventAt = getTimestampOrNull("last_event_at")?.toInstant(),
            lastReviewedAt = getTimestampOrNull("last_reviewed_at")?.toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant()
        )

    private fun ResultSet.toRiskEventRecord(): RiskEventRecord =
        RiskEventRecord(
            id = getObject("id", UUID::class.java),
            userId = getObject("user_id", UUID::class.java),
            actorUserId = getObject("actor_user_id", UUID::class.java),
            source = getString("source"),
            reason = getString("reason"),
            delta = getInt("delta"),
            scoreAfter = getInt("score_after"),
            tierAfter = getString("tier_after"),
            metadataJson = getString("metadata_json"),
            createdAt = getTimestamp("created_at").toInstant()
        )

    private fun ResultSet.getTimestampOrNull(column: String): Timestamp? =
        getTimestamp(column).takeUnless { wasNull() }

    private fun riskTierCase(scoreExpression: String): String =
        """
        CASE
            WHEN $scoreExpression >= 90 THEN 'terminate'
            WHEN $scoreExpression >= 70 THEN 'quarantine'
            WHEN $scoreExpression >= 50 THEN 'friction'
            WHEN $scoreExpression >= 30 THEN 'watch'
            ELSE 'clean'
        END
        """.trimIndent()
}
