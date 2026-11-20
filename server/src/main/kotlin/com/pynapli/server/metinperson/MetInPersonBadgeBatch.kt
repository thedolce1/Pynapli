package com.pynapli.server.metinperson

import com.pynapli.server.db.JobLock
import com.pynapli.server.db.UnlockedJobs
import com.pynapli.server.db.inTransaction
import com.pynapli.server.db.withConnection
import com.pynapli.server.metinperson.MetInPersonResolution.RESOLVED_AND_ELIGIBLE_CTES
import com.pynapli.server.metinperson.MetInPersonResolution.bindResolved
import com.pynapli.server.metinperson.MetInPersonResolution.liveVouches
import com.pynapli.server.metinperson.MetInPersonResolution.resolveVouchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.sql.Connection
import java.sql.Timestamp
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import javax.sql.DataSource
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * The only writer of badges (TRUST_AND_SAFETY_SPEC "Met in person"). Badges are recomputed once a
 * week, at a random moment inside that week (UTC Monday to Monday), never when a confirmation is
 * written, so the moment a badge appears or disappears cannot be tied to one confirmation or meeting.
 *
 * The due time lives in `vouch_batch_schedule` (one row). The check, the badge rewrite and the move to
 * next week's random time happen in one transaction holding that row's lock, so concurrent replicas
 * (or ticks) run it exactly once per week; a deploy does not re-roll the time.
 */
class MetInPersonBadgeBatch(
    private val dataSource: DataSource,
    private val tokens: MetInPersonTokens,
    private val clock: Clock = Clock.systemUTC(),
    private val random: Random = SecureRandom().asKotlinRandom(),
) {
    /** Recomputes if this week's random time has passed and it has not run since. True when it ran. */
    suspend fun runIfDue(): Boolean =
        dataSource.withConnection { connection ->
            connection.inTransaction {
                val now = clock.instant()
                ensureSchedule(randomTimeInWeekOf(now))
                val nextRunAt = lockedNextRunAt()
                if (nextRunAt.isAfter(now)) return@inTransaction false
                recompute(this, now)
                advanceSchedule(now, randomTimeInWeekOf(weekStart(now).plus(WEEK)))
                true
            }
        }

    /** Recomputes now, outside the schedule. Tests, and an operator's one-off after a migration. */
    suspend fun recomputeNow(): Int =
        dataSource.withConnection { connection ->
            connection.inTransaction { recompute(this, clock.instant()) }
        }

    internal fun randomTimeInWeekOf(instant: Instant): Instant = weekStart(instant).plusSeconds(random.nextLong(WEEK.seconds))

    private fun recompute(
        connection: Connection,
        now: Instant,
    ): Int {
        val live = connection.liveVouches(now)
        val resolved = connection.resolveVouchers(live, tokens)
        return connection
            .prepareStatement(
                """
                WITH $RESOLVED_AND_ELIGIBLE_CTES,
                person AS (
                    SELECT subject_user_id, count(DISTINCT voucher_user_id) AS n
                    FROM eligible
                    GROUP BY subject_user_id
                ),
                badge_subjects AS (
                    SELECT subject_user_id FROM person
                    UNION
                    SELECT subject_user_id FROM vouch_badges
                )
                INSERT INTO vouch_badges (subject_user_id, person_badge, couple_badge, couple_link_id, computed_at)
                SELECT s.subject_user_id,
                       COALESCE(p.n, 0) >= ${MetInPersonRules.PERSON_THRESHOLD},
                       c.link_id IS NOT NULL,
                       c.link_id,
                       ?
                FROM badge_subjects s
                LEFT JOIN person p ON p.subject_user_id = s.subject_user_id
                LEFT JOIN LATERAL (
                    SELECT cc.link_id
                    FROM couple cc
                    WHERE cc.subject_user_id = s.subject_user_id
                      AND cc.n >= ${MetInPersonRules.COUPLE_THRESHOLD}
                    ORDER BY cc.n DESC, cc.link_id
                    LIMIT 1
                ) c ON true
                ON CONFLICT (subject_user_id) DO UPDATE
                SET person_badge = EXCLUDED.person_badge AND vouch_badges.frozen_at IS NULL,
                    couple_badge = EXCLUDED.couple_badge AND vouch_badges.frozen_at IS NULL,
                    couple_link_id = CASE WHEN vouch_badges.frozen_at IS NULL THEN EXCLUDED.couple_link_id END,
                    computed_at = EXCLUDED.computed_at
                """.trimIndent(),
            ).use { statement ->
                connection.bindResolved(statement, resolved)
                statement.setTimestamp(3, Timestamp.from(now))
                statement.executeUpdate()
            }
    }

    private fun Connection.ensureSchedule(firstRunAt: Instant) {
        prepareStatement(
            "INSERT INTO vouch_batch_schedule (id, next_run_at) VALUES (1, ?) ON CONFLICT (id) DO NOTHING",
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(firstRunAt))
            statement.executeUpdate()
        }
    }

    private fun Connection.lockedNextRunAt(): Instant =
        prepareStatement("SELECT next_run_at FROM vouch_batch_schedule WHERE id = 1 FOR UPDATE").use { statement ->
            statement.executeQuery().use { rs ->
                check(rs.next()) { "vouch_batch_schedule row missing" }
                rs.getTimestamp("next_run_at").toInstant()
            }
        }

    private fun Connection.advanceSchedule(
        now: Instant,
        nextRunAt: Instant,
    ) {
        prepareStatement("UPDATE vouch_batch_schedule SET last_run_at = ?, next_run_at = ? WHERE id = 1").use { statement ->
            statement.setTimestamp(1, Timestamp.from(now))
            statement.setTimestamp(2, Timestamp.from(nextRunAt))
            statement.executeUpdate()
        }
    }

    internal companion object {
        val WEEK: Duration = Duration.ofDays(7)

        fun weekStart(instant: Instant): Instant =
            instant
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
    }
}

/**
 * Ticks [MetInPersonBadgeBatch.runIfDue] (cheap when not due) under the shared [JobLock], following
 * the other periodic single-run jobs (`TonightSessionExpiryWorker`, `DataRetentionWorker`).
 */
class MetInPersonBadgeWorker(
    private val batch: MetInPersonBadgeBatch,
    private val intervalMillis: Long = 15 * 60 * 1_000L,
    private val jobLock: JobLock = UnlockedJobs,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(MetInPersonBadgeWorker::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: kotlinx.coroutines.Job? = null

    fun start() {
        if (job != null) return
        job =
            scope.launch {
                while (isActive) {
                    runCatching {
                        jobLock.runExclusive("met-in-person-badges") {
                            if (batch.runIfDue()) logger.info("Met in person weekly badge recompute ran")
                        }
                    }.onFailure { error ->
                        logger.warn("Met in person badge recompute failed", error)
                    }
                    delay(intervalMillis)
                }
            }
    }

    override fun close() {
        scope.cancel()
    }
}
