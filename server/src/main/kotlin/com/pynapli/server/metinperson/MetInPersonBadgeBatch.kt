package com.pynapli.server.metinperson

import com.pynapli.server.db.JobLock
import com.pynapli.server.db.UnlockedJobs
import com.pynapli.server.db.inTransaction
import com.pynapli.server.db.withConnection
import com.pynapli.server.metinperson.JdbcMetInPersonSql.keyMatches
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
import java.util.UUID
import javax.sql.DataSource
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * The only writer of earned badges (TRUST_AND_SAFETY_SPEC "Met in person"). Once a week, at a random
 * moment inside that UTC Monday-to-Monday week, it re-evaluates every member in chunks and moves each
 * badge one step through [MetInPersonHysteresis]; a confirmation never changes a badge directly.
 *
 * - The run is claimed in its own short transaction (`vouch_batch_schedule`, row lock): the due check and
 *   the move to next week's random time happen together, so concurrent replicas or ticks run it once,
 *   and a deploy does not re-roll the time. The pass in progress and its cursor live there too (V125): an
 *   interrupted pass resumes at the next tick with its own evaluation time, and each chunk moves the
 *   cursor under the row lock, so nobody is stepped twice in one pass.
 * - [recomputeNow] (an operator's look) writes nothing and never steps the hysteresis.
 * - Each chunk of members is its own short transaction; nothing loads every confirmation.
 * - A couple badge is written only if its link is still active at write time, and is stamped with the
 *   chunk's database time, so a concurrent unlink (which stamps `vouch_link_ends`) always wins and a
 *   relink can never bring back a badge published before it.
 * - It refuses to run when the configured key does not match the stored check value.
 */
class MetInPersonBadgeBatch(
    private val dataSource: DataSource,
    private val tokens: MetInPersonTokens,
    private val clock: Clock = Clock.systemUTC(),
    private val random: Random = SecureRandom().asKotlinRandom(),
    private val chunkSize: Int = 500,
) {
    private val evaluator = MetInPersonEvaluator(tokens)

    /**
     * Runs this week's pass if its random time has passed and it has not run, or resumes a pass a crash
     * or deploy interrupted (with that pass's own evaluation time, from where it stopped). True when this
     * call claimed a new week's pass; a resume helps finish the pass in progress and returns false.
     */
    suspend fun runIfDue(): Boolean {
        val now = clock.instant()
        val claim =
            dataSource.withConnection { connection ->
                connection.inTransaction {
                    if (!keyMatches(tokens, now)) return@inTransaction null
                    ensureSchedule(randomTimeInWeekOf(now))
                    val schedule = lockedSchedule()
                    when {
                        schedule.passId != null -> {
                            PassClaim(schedule.passId, fresh = false)
                        }

                        schedule.nextRunAt.isAfter(now) -> {
                            null
                        }

                        else -> {
                            val passId = UUID.randomUUID()
                            startPass(passId, now, randomTimeInWeekOf(weekStart(now).plus(WEEK)))
                            PassClaim(passId, fresh = true)
                        }
                    }
                }
            } ?: return false
        runScheduledPass(claim.passId)
        return claim.fresh
    }

    /**
     * An operator's look, outside the schedule: evaluates every member as a pass would and returns how
     * many would move toward a different published state. Writes nothing, so it never steps the
     * hysteresis (only the weekly pass may; repeated calls would otherwise shorten the random delay).
     */
    suspend fun recomputeNow(): Int {
        val now = clock.instant()
        val keyOk = dataSource.withConnection { connection -> connection.keyMatches(tokens, now) }
        if (!keyOk) return 0
        var after: UUID? = null
        var differing = 0
        while (true) {
            val chunk = dataSource.withConnection { connection -> connection.userChunk(after, chunkSize) }
            if (chunk.isEmpty()) break
            differing += dataSource.withConnection { connection -> connection.countDiffering(chunk, now) }
            after = chunk.last()
        }
        return differing
    }

    /** One full stepping pass now, outside the schedule. Tests only: it steps the hysteresis like a weekly pass. */
    internal suspend fun runPassNow(): Int {
        val now = clock.instant()
        val keyOk = dataSource.withConnection { connection -> connection.keyMatches(tokens, now) }
        if (!keyOk) return 0
        var after: UUID? = null
        var written = 0
        while (true) {
            val chunk = dataSource.withConnection { connection -> connection.userChunk(after, chunkSize) }
            if (chunk.isEmpty()) break
            written += dataSource.withConnection { connection -> connection.inTransaction { processChunk(this, chunk, now) } }
            after = chunk.last()
        }
        return written
    }

    internal fun randomTimeInWeekOf(instant: Instant): Instant = weekStart(instant).plusSeconds(random.nextLong(WEEK.seconds))

    private class PassClaim(
        val passId: UUID,
        val fresh: Boolean,
    )

    private class Schedule(
        val nextRunAt: Instant,
        val passId: UUID?,
        val passNow: Instant?,
        val passCursor: UUID?,
    )

    /**
     * Works through the pass [passId] chunk by chunk. Each chunk is chosen, stepped, and the cursor moved
     * in one transaction holding the schedule row's lock, so a second runner (a resume racing the first)
     * takes the next chunk instead of the same one, and a crash loses at most the chunk in flight.
     */
    private suspend fun runScheduledPass(passId: UUID) {
        while (true) {
            val more =
                dataSource.withConnection { connection ->
                    connection.inTransaction {
                        val schedule = lockedSchedule()
                        val passNow = schedule.passNow
                        if (schedule.passId != passId || passNow == null) return@inTransaction false
                        val chunk = userChunk(schedule.passCursor, chunkSize)
                        if (chunk.isEmpty()) {
                            finishPass()
                            return@inTransaction false
                        }
                        processChunk(this, chunk, passNow)
                        moveCursor(chunk.last())
                        true
                    }
                }
            if (!more) return
        }
    }

    /** Members of [userIds] whose evaluation now differs from what is published (frozen ones excluded). */
    private fun Connection.countDiffering(
        userIds: List<UUID>,
        now: Instant,
    ): Int {
        val evaluations = evaluator.evaluate(this, userIds, now)
        val published = publishedBadges(userIds)
        return userIds.count { userId ->
            val evaluation = evaluations[userId]
            val badge = published[userId]
            badge?.frozen != true &&
                (
                    (evaluation?.personQualifies == true) != (badge?.person == true) ||
                        (evaluation?.coupleLinkToken != null) != (badge?.couple == true)
                )
        }
    }

    private fun processChunk(
        connection: Connection,
        userIds: List<UUID>,
        now: Instant,
    ): Int {
        val evaluations = evaluator.evaluate(connection, userIds, now)
        val published = connection.publishedBadges(userIds)
        val tokenOf = userIds.associateWith { tokens.subject(it) }
        val states = connection.badgeStates(tokenOf.values)
        var written = 0
        userIds.forEach { userId ->
            val evaluation = evaluations[userId]
            val badge = published[userId]
            val state = states[tokenOf.getValue(userId)]
            if (evaluation == null && badge == null && state == null) return@forEach
            written++
            if (badge?.frozen == true) {
                connection.writeBadge(userId, person = false, couple = false, coupleLinkId = null, frozen = true)
                connection.deleteState(tokenOf.getValue(userId))
                return@forEach
            }
            val person =
                MetInPersonHysteresis.step(
                    track = BadgeTrack(published = badge?.person == true, target = state?.personTarget, remaining = state?.personRemaining, below = state?.personBelow ?: 0),
                    qualifies = evaluation?.personQualifies == true,
                    delay = ::extraBatches,
                )
            val publishedCoupleToken = badge?.coupleLinkId?.takeIf { badge.couple }?.let(tokens::link)
            val couple =
                MetInPersonHysteresis.step(
                    track =
                        BadgeTrack(
                            published = badge?.couple == true,
                            target = state?.coupleTarget,
                            remaining = state?.coupleRemaining,
                            below = state?.coupleBelow ?: 0,
                            linkToken = if (badge?.couple == true) publishedCoupleToken else state?.coupleLinkToken,
                        ),
                    qualifies = evaluation?.coupleLinkToken != null,
                    token = evaluation?.coupleLinkToken,
                    delay = ::extraBatches,
                )
            // The link id to publish: the one already published, or the one this batch qualified with.
            val coupleLinkId =
                when {
                    !couple.published -> null
                    badge?.couple == true -> badge.coupleLinkId
                    else -> evaluation?.coupleLinkId
                }
            connection.writeBadge(userId, person = person.published, couple = couple.published && coupleLinkId != null, coupleLinkId = coupleLinkId, frozen = false)
            if (person.idle && couple.idle) {
                connection.deleteState(tokenOf.getValue(userId))
            } else {
                connection.writeState(tokenOf.getValue(userId), person, couple)
            }
        }
        return written
    }

    private fun extraBatches(): Int = random.nextInt(MetInPersonHysteresis.MIN_EXTRA_BATCHES, MetInPersonHysteresis.MAX_EXTRA_BATCHES + 1)

    private class Published(
        val person: Boolean,
        val couple: Boolean,
        val coupleLinkId: UUID?,
        val frozen: Boolean,
    )

    private class State(
        val personTarget: Boolean?,
        val personRemaining: Int?,
        val personBelow: Int,
        val coupleTarget: Boolean?,
        val coupleRemaining: Int?,
        val coupleBelow: Int,
        val coupleLinkToken: String?,
    )

    private fun Connection.userChunk(
        after: UUID?,
        size: Int,
    ): List<UUID> =
        prepareStatement(
            "SELECT id FROM users WHERE (?::uuid IS NULL OR id > ?::uuid) ORDER BY id LIMIT ?",
        ).use { statement ->
            statement.setObject(1, after)
            statement.setObject(2, after)
            statement.setInt(3, size)
            statement.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getObject(1, UUID::class.java)) } }
        }

    private fun Connection.publishedBadges(userIds: List<UUID>): Map<UUID, Published> =
        prepareStatement(
            "SELECT subject_user_id, person_badge, couple_badge, couple_link_id, frozen_at FROM vouch_badges WHERE subject_user_id = ANY(?)",
        ).use { statement ->
            statement.setArray(1, createArrayOf("uuid", userIds.toTypedArray()))
            statement.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) {
                        put(
                            rs.getObject("subject_user_id", UUID::class.java),
                            Published(
                                person = rs.getBoolean("person_badge"),
                                couple = rs.getBoolean("couple_badge"),
                                coupleLinkId = rs.getObject("couple_link_id", UUID::class.java),
                                frozen = rs.getTimestamp("frozen_at") != null,
                            ),
                        )
                    }
                }
            }
        }

    private fun Connection.badgeStates(subjectTokens: Collection<String>): Map<String, State> =
        prepareStatement(
            """
            SELECT subject_token, person_target, person_remaining, person_below,
                   couple_target, couple_remaining, couple_below, couple_link_token
            FROM vouch_badge_states
            WHERE subject_token = ANY(?)
            """.trimIndent(),
        ).use { statement ->
            statement.setArray(1, createArrayOf("text", subjectTokens.toTypedArray()))
            statement.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) {
                        put(
                            rs.getString("subject_token"),
                            State(
                                personTarget = rs.getObject("person_target") as Boolean?,
                                personRemaining = (rs.getObject("person_remaining") as Number?)?.toInt(),
                                personBelow = rs.getInt("person_below"),
                                coupleTarget = rs.getObject("couple_target") as Boolean?,
                                coupleRemaining = (rs.getObject("couple_remaining") as Number?)?.toInt(),
                                coupleBelow = rs.getInt("couple_below"),
                                coupleLinkToken = rs.getString("couple_link_token"),
                            ),
                        )
                    }
                }
            }
        }

    /**
     * Writes the published badges, or deletes the row when both are off and the member is not frozen
     * (an all-false row would say someone once confirmed them). The couple badge is kept only while its
     * link is active at write time; `couple_since` is this transaction's database time when it is newly
     * published, so a link end stamped after it hides it.
     */
    private fun Connection.writeBadge(
        userId: UUID,
        person: Boolean,
        couple: Boolean,
        coupleLinkId: UUID?,
        frozen: Boolean,
    ) {
        if (!person && !couple && !frozen) {
            prepareStatement("DELETE FROM vouch_badges WHERE subject_user_id = ? AND frozen_at IS NULL").use { statement ->
                statement.setObject(1, userId)
                statement.executeUpdate()
            }
            return
        }
        prepareStatement(
            """
            INSERT INTO vouch_badges (subject_user_id, person_badge, couple_badge, couple_link_id, couple_since, updated_at)
            SELECT ?::uuid, ?::boolean, linked.ok, CASE WHEN linked.ok THEN ?::uuid END, CASE WHEN linked.ok THEN now() END, ?::timestamptz
            FROM (
                SELECT (?::boolean AND EXISTS (
                    SELECT 1 FROM user_profile_links l
                    WHERE l.id = ?::uuid AND l.status = 'active' AND l.unlinked_at IS NULL
                )) AS ok
            ) linked
            ON CONFLICT (subject_user_id) DO UPDATE
            SET person_badge = EXCLUDED.person_badge AND vouch_badges.frozen_at IS NULL,
                couple_badge = EXCLUDED.couple_badge AND vouch_badges.frozen_at IS NULL,
                couple_link_id = EXCLUDED.couple_link_id,
                couple_since =
                    CASE
                        WHEN EXCLUDED.couple_badge
                             AND vouch_badges.couple_badge
                             AND vouch_badges.couple_link_id = EXCLUDED.couple_link_id
                        THEN vouch_badges.couple_since
                        ELSE EXCLUDED.couple_since
                    END,
                updated_at = EXCLUDED.updated_at
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setBoolean(2, person && !frozen)
            statement.setObject(3, coupleLinkId)
            statement.setTimestamp(4, Timestamp.from(clock.instant()))
            statement.setBoolean(5, couple && !frozen)
            statement.setObject(6, coupleLinkId)
            statement.executeUpdate()
        }
    }

    private fun Connection.writeState(
        subjectToken: String,
        person: BadgeTrack,
        couple: BadgeTrack,
    ) {
        prepareStatement(
            """
            INSERT INTO vouch_badge_states (
                subject_token, person_target, person_remaining, person_below,
                couple_target, couple_remaining, couple_below, couple_link_token, key_version
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (subject_token) DO UPDATE
            SET person_target = EXCLUDED.person_target,
                person_remaining = EXCLUDED.person_remaining,
                person_below = EXCLUDED.person_below,
                couple_target = EXCLUDED.couple_target,
                couple_remaining = EXCLUDED.couple_remaining,
                couple_below = EXCLUDED.couple_below,
                couple_link_token = EXCLUDED.couple_link_token
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, subjectToken)
            statement.setObject(2, person.target, java.sql.Types.BOOLEAN)
            statement.setObject(3, person.remaining?.toShort(), java.sql.Types.SMALLINT)
            statement.setShort(4, person.below.toShort())
            statement.setObject(5, couple.target, java.sql.Types.BOOLEAN)
            statement.setObject(6, couple.remaining?.toShort(), java.sql.Types.SMALLINT)
            statement.setShort(7, couple.below.toShort())
            statement.setString(8, couple.linkToken.takeIf { couple.target != null })
            statement.setShort(9, MetInPersonTokens.KEY_VERSION)
            statement.executeUpdate()
        }
    }

    private fun Connection.deleteState(subjectToken: String) {
        prepareStatement("DELETE FROM vouch_badge_states WHERE subject_token = ?").use { statement ->
            statement.setString(1, subjectToken)
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

    private fun Connection.lockedSchedule(): Schedule =
        prepareStatement(
            "SELECT next_run_at, pass_id, pass_now, pass_cursor FROM vouch_batch_schedule WHERE id = 1 FOR UPDATE",
        ).use { statement ->
            statement.executeQuery().use { rs ->
                check(rs.next()) { "vouch_batch_schedule row missing" }
                Schedule(
                    nextRunAt = rs.getTimestamp("next_run_at").toInstant(),
                    passId = rs.getObject("pass_id", UUID::class.java),
                    passNow = rs.getTimestamp("pass_now")?.toInstant(),
                    passCursor = rs.getObject("pass_cursor", UUID::class.java),
                )
            }
        }

    /** Claims this week's pass and moves the schedule to next week's random time, in one transaction. */
    private fun Connection.startPass(
        passId: UUID,
        now: Instant,
        nextRunAt: Instant,
    ) {
        prepareStatement(
            "UPDATE vouch_batch_schedule SET last_run_at = ?, next_run_at = ?, pass_id = ?, pass_now = ?, pass_cursor = NULL WHERE id = 1",
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(now))
            statement.setTimestamp(2, Timestamp.from(nextRunAt))
            statement.setObject(3, passId)
            statement.setTimestamp(4, Timestamp.from(now))
            statement.executeUpdate()
        }
    }

    private fun Connection.moveCursor(cursor: UUID) {
        prepareStatement("UPDATE vouch_batch_schedule SET pass_cursor = ? WHERE id = 1").use { statement ->
            statement.setObject(1, cursor)
            statement.executeUpdate()
        }
    }

    private fun Connection.finishPass() {
        prepareStatement("UPDATE vouch_batch_schedule SET pass_id = NULL, pass_now = NULL, pass_cursor = NULL WHERE id = 1").use { statement ->
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
 * Ticks [MetInPersonBadgeBatch.runIfDue] (cheap when not due) under the shared [JobLock], following the
 * other periodic single-run jobs (`TonightSessionExpiryWorker`, `DataRetentionWorker`).
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
                            if (batch.runIfDue()) logger.info("Met in person weekly badge pass ran")
                        }
                    }.onFailure { error ->
                        logger.warn("Met in person badge pass failed", error)
                    }
                    delay(intervalMillis)
                }
            }
    }

    override fun close() {
        scope.cancel()
    }
}
