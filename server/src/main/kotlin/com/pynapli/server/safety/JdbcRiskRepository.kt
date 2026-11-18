package com.pynapli.server.safety

import com.pynapli.server.db.inTransaction
import com.pynapli.server.db.withConnection
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Risk state and the risk event ledger. Decay (RiskDecayPolicy) is applied lazily: every write first
 * applies whatever decay is due under the account's row lock, and [getState] applies it when due, so
 * the stored score and tier are current whenever this repository hands them out.
 */
class JdbcRiskRepository(
    private val dataSource: DataSource,
    private val clock: Clock = Clock.systemUTC(),
) : RiskRepository {
    override suspend fun recordEvent(
        userId: UUID,
        actorUserId: UUID?,
        source: String,
        reason: String,
        delta: Int,
        metadataJson: String,
        eventIdFactory: () -> UUID,
    ): RiskMutationRecord =
        dataSource.withConnection { connection ->
            connection.inTransaction {
                val now = clock.instant()
                connection.applyDueDecay(userId, now)
                val state = connection.upsertRiskState(userId, delta, RiskDecayPolicy.isDecayable(source), now)
                val event =
                    connection.insertRiskEvent(
                        id = eventIdFactory(),
                        userId = userId,
                        actorUserId = actorUserId,
                        source = source,
                        reason = reason,
                        delta = delta,
                        state = state,
                        metadataJson = metadataJson,
                    )
                connection.commit()
                RiskMutationRecord(event = event, state = state)
            }
        }

    override suspend fun recordEventOnce(
        userId: UUID,
        actorUserId: UUID?,
        source: String,
        reason: String,
        delta: Int,
        metadataJson: String,
        eventIdFactory: () -> UUID,
    ): RiskMutationRecord? =
        dataSource.withConnection { connection ->
            connection.inTransaction {
                // The row lock serializes concurrent callers, so the "already recorded?" check below
                // and the insert can't interleave for the same account.
                connection.lockRiskScore(userId)
                if (connection.hasEventWithReason(userId, reason)) {
                    null
                } else {
                    val now = clock.instant()
                    connection.applyDueDecay(userId, now)
                    val state = connection.upsertRiskState(userId, delta, RiskDecayPolicy.isDecayable(source), now)
                    val event =
                        connection.insertRiskEvent(
                            id = eventIdFactory(),
                            userId = userId,
                            actorUserId = actorUserId,
                            source = source,
                            reason = reason,
                            delta = delta,
                            state = state,
                            metadataJson = metadataJson,
                        )
                    RiskMutationRecord(event = event, state = state)
                }
            }
        }

    private fun Connection.hasEventWithReason(
        userId: UUID,
        reason: String,
    ): Boolean =
        prepareStatement("SELECT EXISTS (SELECT 1 FROM account_risk_events WHERE user_id = ? AND reason = ?)").use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, reason)
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getBoolean(1)
            }
        }

    override suspend fun recordReportEvent(
        userId: UUID,
        reporterUserId: UUID,
        reason: String,
        requestedDelta: Int,
        metadataJson: String,
        policy: ReportScoringPolicy,
        eventIdFactory: () -> UUID,
    ): RiskMutationRecord =
        dataSource.withConnection { connection ->
            connection.inTransaction {
                // Decay first, so the policy judges the report against the current score.
                val now = clock.instant()
                connection.applyDueDecay(userId, now)
                // Lock the account's risk row first so concurrent reports are judged one at a time.
                val currentScore = connection.lockRiskScore(userId)
                val windowSeconds = policy.window.seconds
                val duplicate = connection.hasRecentReportEvent(userId, reporterUserId, reason, windowSeconds)
                val distinctReporters = connection.distinctRecentReporters(userId, reporterUserId, windowSeconds)
                val counted = policy.countedDelta(currentScore, requestedDelta, duplicate, distinctReporters)
                val state =
                    connection.upsertRiskState(userId, counted, RiskDecayPolicy.isDecayable(RiskService.SOURCE_REPORT), now)
                val event =
                    connection.insertRiskEvent(
                        id = eventIdFactory(),
                        userId = userId,
                        actorUserId = reporterUserId,
                        source = RiskService.SOURCE_REPORT,
                        reason = reason,
                        delta = counted,
                        state = state,
                        metadataJson =
                            metadataJson.withJsonFields(
                                "\"requestedDelta\":$requestedDelta,\"countedDelta\":$counted," +
                                    "\"duplicate\":$duplicate,\"distinctReporters\":$distinctReporters",
                            ),
                    )
                connection.commit()
                RiskMutationRecord(event = event, state = state)
            }
        }

    private fun Connection.lockRiskScore(userId: UUID): Int {
        prepareStatement("INSERT INTO account_risk_states (user_id) VALUES (?) ON CONFLICT (user_id) DO NOTHING").use { statement ->
            statement.setObject(1, userId)
            statement.executeUpdate()
        }
        return prepareStatement("SELECT score FROM account_risk_states WHERE user_id = ? FOR UPDATE").use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    private fun Connection.hasRecentReportEvent(
        userId: UUID,
        reporterUserId: UUID,
        reason: String,
        windowSeconds: Long,
    ): Boolean =
        prepareStatement(
            """
            SELECT EXISTS (
                SELECT 1
                FROM account_risk_events
                WHERE user_id = ?
                  AND source = '${RiskService.SOURCE_REPORT}'
                  AND actor_user_id = ?
                  AND reason = ?
                  AND created_at > now() - make_interval(secs => ?::double precision)
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setObject(2, reporterUserId)
            statement.setString(3, reason)
            statement.setLong(4, windowSeconds)
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getBoolean(1)
            }
        }

    /** Distinct accounts that reported [userId] in the window, counting [reporterUserId] (this report). */
    private fun Connection.distinctRecentReporters(
        userId: UUID,
        reporterUserId: UUID,
        windowSeconds: Long,
    ): Int =
        prepareStatement(
            """
            SELECT count(DISTINCT reporter)
            FROM (
                SELECT actor_user_id AS reporter
                FROM account_risk_events
                WHERE user_id = ?
                  AND source = '${RiskService.SOURCE_REPORT}'
                  AND actor_user_id IS NOT NULL
                  AND created_at > now() - make_interval(secs => ?::double precision)
                UNION ALL
                SELECT ?::uuid
            ) reporters
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setLong(2, windowSeconds)
            statement.setObject(3, reporterUserId)
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    override suspend fun getState(userId: UUID): AccountRiskStateRecord? =
        dataSource.withConnection { connection ->
            val now = clock.instant()
            val stored = connection.selectStoredRiskState(userId, forUpdate = false) ?: return@withConnection null
            if (stored.decayStep(now) == null) {
                // Hot path: nothing due, one plain read and no lock.
                stored.record
            } else {
                connection.inTransaction {
                    connection.applyDueDecay(userId, now)
                    connection.selectStoredRiskState(userId, forUpdate = false)?.record
                }
            }
        }

    /** The stored row plus its decay bookkeeping, which stays inside this repository. */
    private class StoredRiskState(
        val record: AccountRiskStateRecord,
        val decayablePoints: Int,
        val decayAnchorAt: Instant?,
    ) {
        // Clamped to the score: some writers (and test fixtures) set the score directly.
        val usableDecayablePoints: Int get() = minOf(decayablePoints, record.score).coerceAtLeast(0)

        fun decayStep(now: Instant): RiskDecayPolicy.Step? = RiskDecayPolicy.step(usableDecayablePoints, decayAnchorAt, now)
    }

    private fun Connection.selectStoredRiskState(
        userId: UUID,
        forUpdate: Boolean,
    ): StoredRiskState? =
        prepareStatement(
            """
            SELECT user_id,
                   score,
                   tier,
                   last_event_at,
                   last_reviewed_at,
                   updated_at,
                   decayable_points,
                   decay_anchor_at
            FROM account_risk_states
            WHERE user_id = ?
            ${if (forUpdate) "FOR UPDATE" else ""}
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    StoredRiskState(
                        record = rs.toRiskStateRecord(),
                        decayablePoints = rs.getInt("decayable_points"),
                        decayAnchorAt = rs.getTimestampOrNull("decay_anchor_at")?.toInstant(),
                    )
                } else {
                    null
                }
            }
        }

    /**
     * Applies the decay due at [now] to the decayable part of the score, recomputes the tier from the
     * new score and writes a `decay.minor` ledger event. Must run inside a transaction: the row is
     * locked first, so concurrent callers apply each interval's decay exactly once (the second one
     * waits, then reads the advanced anchor and finds nothing due). A no-op when nothing is due.
     */
    private fun Connection.applyDueDecay(
        userId: UUID,
        now: Instant,
    ) {
        val stored = selectStoredRiskState(userId, forUpdate = true) ?: return
        val step = stored.decayStep(now) ?: return
        val decayedScore = "(account_risk_states.score - payload.points)"
        val state =
            prepareStatement(
                """
                WITH payload AS (
                    SELECT ?::integer AS points
                )
                UPDATE account_risk_states
                SET score = $decayedScore,
                    decayable_points = LEAST(account_risk_states.decayable_points, account_risk_states.score) - payload.points,
                    tier = ${riskTierCase(decayedScore)},
                    decay_anchor_at = ?::timestamptz,
                    updated_at = now()
                FROM payload
                WHERE account_risk_states.user_id = ?
                RETURNING account_risk_states.user_id,
                          account_risk_states.score,
                          account_risk_states.tier,
                          account_risk_states.last_event_at,
                          account_risk_states.last_reviewed_at,
                          account_risk_states.updated_at
                """.trimIndent(),
            ).use { statement ->
                statement.setInt(1, step.points)
                statement.setTimestamp(2, step.nextAnchor?.let { Timestamp.from(it) })
                statement.setObject(3, userId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.toRiskStateRecord()
                }
            }
        insertRiskEvent(
            id = UUID.randomUUID(),
            userId = userId,
            actorUserId = null,
            source = RiskDecayPolicy.SOURCE_DECAY,
            reason = RiskDecayPolicy.REASON_DECAY,
            delta = -step.points,
            state = state,
            metadataJson =
                "{\"decayedPoints\":${step.points}," +
                    "\"decayablePointsAfter\":${stored.usableDecayablePoints - step.points}," +
                    "\"pointIntervalSeconds\":${RiskDecayPolicy.POINT_INTERVAL.seconds}}",
        )
    }

    /**
     * Applies [delta] to the score (clamped to 0..100) and keeps the decayable part in step:
     * - a positive delta from a decayable source adds the points actually applied after the clamp;
     * - a positive delta from any other source leaves the decayable part alone, except where the 100
     *   cap leaves no room for it (the non-decayable part takes precedence);
     * - a negative delta (verification) comes off the decayable part first, then the rest, so the
     *   total still drops by the full amount;
     * The decay clock starts at [now] when the decayable part goes from zero to positive and is
     * cleared when it returns to zero.
     */
    private fun Connection.upsertRiskState(
        userId: UUID,
        delta: Int,
        decayable: Boolean,
        now: Instant,
    ): AccountRiskStateRecord {
        val insertScore = "GREATEST(0, LEAST(100, payload.delta))"
        val insertDecayable = "(CASE WHEN payload.decayable AND payload.delta > 0 THEN $insertScore ELSE 0 END)"
        val payloadDelta = "(SELECT delta FROM payload)"
        val payloadDecayable = "(SELECT decayable FROM payload)"
        val updateScore = "GREATEST(0, LEAST(100, account_risk_states.score + $payloadDelta))"
        val oldDecayable = "GREATEST(0, LEAST(account_risk_states.decayable_points, account_risk_states.score))"
        val updateDecayable =
            """
            (CASE
                WHEN $payloadDelta < 0 THEN GREATEST(0, $oldDecayable + $payloadDelta)
                WHEN $payloadDelta > 0 AND $payloadDecayable THEN $oldDecayable + ($updateScore - account_risk_states.score)
                WHEN $payloadDelta > 0
                    THEN GREATEST(0, LEAST($oldDecayable, 100 - LEAST(100, account_risk_states.score - $oldDecayable + $payloadDelta)))
                ELSE $oldDecayable
            END)
            """.trimIndent()
        return prepareStatement(
            """
            WITH payload AS (
                SELECT ?::uuid AS user_id,
                       ?::integer AS delta,
                       ?::boolean AS decayable,
                       ?::timestamptz AS clock_now
            )
            INSERT INTO account_risk_states (
                user_id,
                score,
                tier,
                decayable_points,
                decay_anchor_at,
                last_event_at,
                updated_at
            )
            SELECT payload.user_id,
                   $insertScore,
                   ${riskTierCase(insertScore)},
                   $insertDecayable,
                   CASE WHEN $insertDecayable > 0 THEN payload.clock_now ELSE NULL END,
                   now(),
                   now()
            FROM payload
            ON CONFLICT (user_id) DO UPDATE
            SET score = $updateScore,
                tier = ${riskTierCase(updateScore)},
                decayable_points = $updateDecayable,
                decay_anchor_at =
                    CASE
                        WHEN $updateDecayable = 0 THEN NULL
                        WHEN $oldDecayable = 0 OR account_risk_states.decay_anchor_at IS NULL THEN (SELECT clock_now FROM payload)
                        ELSE account_risk_states.decay_anchor_at
                    END,
                last_event_at = now(),
                updated_at = now()
            RETURNING user_id,
                      score,
                      tier,
                      last_event_at,
                      last_reviewed_at,
                      updated_at
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setInt(2, delta)
            statement.setBoolean(3, decayable)
            statement.setTimestamp(4, Timestamp.from(now))
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
        metadataJson: String,
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
            """.trimIndent(),
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
            updatedAt = getTimestamp("updated_at").toInstant(),
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
            createdAt = getTimestamp("created_at").toInstant(),
        )

    private fun ResultSet.getTimestampOrNull(column: String): Timestamp? = getTimestamp(column).takeUnless { wasNull() }

    /** Adds already-encoded `"key":value` pairs to a JSON object string. */
    private fun String.withJsonFields(fields: String): String {
        val body = trim().removePrefix("{").removeSuffix("}").trim()
        return if (body.isEmpty()) "{$fields}" else "{$body,$fields}"
    }

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
