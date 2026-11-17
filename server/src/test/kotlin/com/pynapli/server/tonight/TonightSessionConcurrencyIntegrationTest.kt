package com.pynapli.server.tonight

import com.pynapli.server.subscriptions.EntitlementPolicy
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.server.testsupport.raceTogether
import com.pynapli.shared.network.dto.ProposeTonightSessionRequestDto
import com.pynapli.shared.network.dto.SubscriptionTier
import io.kotest.core.spec.style.StringSpec
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals

/**
 * Starting a Playing Tonight session is check-then-insert (one live session per user, the free
 * weekly cap). A double tap, a retry, or both partners tapping at once must not get past those
 * checks twice. Also: the presence reconciler must not rewrite a session that already finished.
 */
class TonightSessionConcurrencyIntegrationTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource
        val iterations = 40

        beforeTest {
            PynapliTestDatabase.reset()
        }

        fun service(tier: String): TonightSessionService {
            val subscriptions = mockk<SubscriptionService>()
            coEvery { subscriptions.getPolicy(any()) } returns concurrencyPolicy(tier)
            return TonightSessionService(
                repository = JdbcTonightSessionRepository(dataSource),
                subscriptionService = subscriptions,
            )
        }

        val solo = ProposeTonightSessionRequestDto(mode = "solo", intent = "solo")
        val together = ProposeTonightSessionRequestDto(mode = "together", intent = "together")

        "two proposals from the same user at once start one session; the other is told one is live" {
            runBlocking {
                val service = service(SubscriptionTier.SELECT)
                var wrong = 0
                repeat(iterations) {
                    val user = UUID.randomUUID()
                    dataSource.insertConcurrencyUser(user)
                    val (a, b) = raceTogether({ service.propose(user, solo) }, { service.propose(user, solo) })
                    val codes = listOf(a, b).mapNotNull { (it.exceptionOrNull() as? TonightSessionException)?.code }
                    val oneWonOneRejected = listOf(a, b).count { it.isSuccess } == 1 && codes == listOf("TONIGHT_ALREADY_ACTIVE")
                    if (dataSource.liveSessionCount(user) != 1 || !oneWonOneRejected) wrong++
                }
                assertEquals(0, wrong, "iterations out of $iterations that did not end with exactly one live session")
            }
        }

        "a free user one below the weekly cap cannot exceed it with two proposals at once" {
            runBlocking {
                val service = service(SubscriptionTier.FREE)
                var wrong = 0
                repeat(iterations) {
                    val user = UUID.randomUUID()
                    dataSource.insertConcurrencyUser(user)
                    repeat(TONIGHT_FREE_WEEKLY_LIMIT - 1) { dataSource.insertEndedSoloSessionYesterday(user) }
                    raceTogether({ service.propose(user, solo) }, { service.propose(user, solo) })
                    if (dataSource.sessionsCreatedThisWeek(user) > TONIGHT_FREE_WEEKLY_LIMIT) wrong++
                }
                assertEquals(0, wrong, "iterations out of $iterations where the free weekly cap was exceeded")
            }
        }

        "both linked partners proposing together at once start one shared session" {
            runBlocking {
                val service = service(SubscriptionTier.SELECT)
                var wrong = 0
                val observed = mutableSetOf<Pair<Int, Int>>()
                repeat(iterations) {
                    val owner = UUID.randomUUID()
                    val partner = UUID.randomUUID()
                    dataSource.insertConcurrencyUser(owner)
                    dataSource.insertConcurrencyUser(partner)
                    dataSource.insertConcurrencyLink(owner, partner)
                    raceTogether({ service.propose(owner, together) }, { service.propose(partner, together) })
                    // Sequentially the second proposer is already a participant of the first session,
                    // so it is rejected: the pair must end with one live session between them.
                    val counts = dataSource.liveSessionCount(owner) to dataSource.liveSessionCount(partner)
                    observed += counts
                    if (counts != (1 to 1)) wrong++
                }
                assertEquals(
                    0,
                    wrong,
                    "iterations out of $iterations where the pair did not end with one live session (owner to partner counts seen: $observed)",
                )
            }
        }

        // The service's fast-path count can pass while another request fills the last slot (it
        // inserts, then the session ends, so "already active" no longer catches it). The insert's
        // own count under the lock is what holds the cap then.
        "the insert re-checks the weekly cap and writes nothing at the limit" {
            runBlocking {
                val repository = JdbcTonightSessionRepository(dataSource)
                val user = UUID.randomUUID()
                dataSource.insertConcurrencyUser(user)
                repeat(TONIGHT_FREE_WEEKLY_LIMIT) { dataSource.insertEndedSoloSessionYesterday(user) }
                val now = Instant.now()

                val outcome =
                    repository.insertSessionIfAllowed(
                        session = soloSession(user, now),
                        now = now,
                        weeklyCap = TonightSessionRepository.WeeklyCap(now.minus(TONIGHT_WEEKLY_WINDOW), TONIGHT_FREE_WEEKLY_LIMIT),
                    )

                assertEquals(TonightSessionRepository.InsertOutcome.WeeklyLimitReached, outcome)
                assertEquals(TONIGHT_FREE_WEEKLY_LIMIT, dataSource.sessionsCreatedThisWeek(user))
                assertEquals(0, dataSource.liveSessionCount(user))
            }
        }

        "pausing a session that already ended leaves it alone" {
            runBlocking {
                val user = UUID.randomUUID()
                dataSource.insertConcurrencyUser(user)
                val ended = dataSource.insertFinishedTogetherSession(user, status = "ended")

                JdbcTonightSessionRepository(dataSource).pauseSession(ended, Instant.now())

                assertEquals(FinishedRow("ended", null, FINISHED_ENDED_AT), dataSource.finishedRow(ended))
            }
        }

        "pausing a session that already expired leaves it alone" {
            runBlocking {
                val user = UUID.randomUUID()
                dataSource.insertConcurrencyUser(user)
                val expired = dataSource.insertFinishedTogetherSession(user, status = "expired")

                JdbcTonightSessionRepository(dataSource).pauseSession(expired, Instant.now())

                assertEquals(FinishedRow("expired", null, null), dataSource.finishedRow(expired))
            }
        }

        "the system ending a session that already expired does not turn it into ended" {
            runBlocking {
                val user = UUID.randomUUID()
                dataSource.insertConcurrencyUser(user)
                val expired = dataSource.insertFinishedTogetherSession(user, status = "expired")

                JdbcTonightSessionRepository(dataSource).systemEndSession(expired, Instant.now())

                assertEquals(FinishedRow("expired", null, null), dataSource.finishedRow(expired))
            }
        }
    })

private val FINISHED_ENDED_AT: Instant = Instant.parse("2026-01-01T02:00:00Z")

private data class FinishedRow(
    val status: String,
    val pausedAt: Instant?,
    val endedAt: Instant?,
)

private fun soloSession(
    owner: UUID,
    now: Instant,
): TonightSession =
    TonightSession(
        id = UUID.randomUUID(),
        createdByUserId = owner,
        mode = TONIGHT_MODE_SOLO,
        intent = "solo",
        hostMode = "public_meet_first",
        note = null,
        photoMediaAssetId = null,
        mapVisible = false,
        status = TONIGHT_STATUS_ACTIVE,
        createdAt = now,
        startedAt = now,
        expiresAt = now.plus(Duration.ofHours(2)),
        endedAt = null,
        participants =
            listOf(
                TonightSessionParticipant(
                    userId = owner,
                    role = TONIGHT_ROLE_OWNER,
                    approvalStatus = TONIGHT_APPROVAL_APPROVED,
                    approvedAt = now,
                ),
            ),
    )

private fun concurrencyPolicy(tier: String): EntitlementPolicy =
    EntitlementPolicy(
        tier = tier,
        activityRevealLimit = if (tier == SubscriptionTier.FREE) 1 else Int.MAX_VALUE,
        advancedFiltersAllowed = tier != SubscriptionTier.FREE,
        gridPageLimit = 60,
        gridResultDepthLimit = 60,
        psyncMaxDeckSize = if (tier == SubscriptionTier.FREE) 10 else 50,
    )

private fun DataSource.insertConcurrencyUser(userId: UUID) {
    connection.use { c ->
        c.prepareStatement("INSERT INTO users (id, status) VALUES (?, 'active')").use { s ->
            s.setObject(1, userId)
            s.executeUpdate()
        }
        c
            .prepareStatement(
                """
                INSERT INTO profiles (user_id, display_name, display_age, relationship_context, moderation_status, visibility_status)
                VALUES (?, 'Racer', 30, 'single', 'approved', 'visible')
                """.trimIndent(),
            ).use { s ->
                s.setObject(1, userId)
                s.executeUpdate()
            }
    }
}

private fun DataSource.insertConcurrencyLink(
    userId: UUID,
    linkedUserId: UUID,
) {
    connection.use { c ->
        c
            .prepareStatement(
                "INSERT INTO user_profile_links (id, user_id, linked_user_id, status, accepted_at) VALUES (?, ?, ?, 'active', now())",
            ).use { s ->
                s.setObject(1, UUID.randomUUID())
                s.setObject(2, userId)
                s.setObject(3, linkedUserId)
                s.executeUpdate()
            }
    }
}

private fun DataSource.insertSessionRow(
    owner: UUID,
    mode: String,
    status: String,
    createdAt: Instant,
    expiresAt: Instant,
    endedAt: Instant?,
): UUID {
    val sessionId = UUID.randomUUID()
    connection.use { c ->
        c
            .prepareStatement(
                """
                INSERT INTO tonight_sessions (id, created_by_user_id, mode, intent, host_mode, status, created_at, started_at, expires_at, ended_at)
                VALUES (?, ?, ?, ?, 'public_meet_first', ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { s ->
                s.setObject(1, sessionId)
                s.setObject(2, owner)
                s.setString(3, mode)
                s.setString(4, mode)
                s.setString(5, status)
                s.setTimestamp(6, Timestamp.from(createdAt))
                s.setTimestamp(7, Timestamp.from(createdAt))
                s.setTimestamp(8, Timestamp.from(expiresAt))
                s.setTimestamp(9, endedAt?.let(Timestamp::from))
                s.executeUpdate()
            }
        c
            .prepareStatement(
                "INSERT INTO tonight_session_participants (session_id, user_id, role, approval_status, approved_at) VALUES (?, ?, 'owner', 'approved', ?)",
            ).use { s ->
                s.setObject(1, sessionId)
                s.setObject(2, owner)
                s.setTimestamp(3, Timestamp.from(createdAt))
                s.executeUpdate()
            }
    }
    return sessionId
}

private fun DataSource.insertEndedSoloSessionYesterday(owner: UUID) {
    val createdAt = Instant.now().minus(Duration.ofDays(1))
    insertSessionRow(owner, "solo", "ended", createdAt, createdAt.plus(Duration.ofHours(2)), createdAt.plus(Duration.ofHours(2)))
}

private fun DataSource.insertFinishedTogetherSession(
    owner: UUID,
    status: String,
): UUID {
    val createdAt = Instant.parse("2026-01-01T00:00:00Z")
    return insertSessionRow(
        owner = owner,
        mode = "together",
        status = status,
        createdAt = createdAt,
        expiresAt = createdAt.plus(Duration.ofHours(3)),
        endedAt = if (status == "ended") FINISHED_ENDED_AT else null,
    )
}

private fun DataSource.queryInt(
    sql: String,
    userId: UUID,
): Int =
    connection.use { c ->
        c.prepareStatement(sql).use { s ->
            s.setObject(1, userId)
            s.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

private fun DataSource.liveSessionCount(userId: UUID): Int =
    queryInt(
        """
        SELECT COUNT(*)::integer
        FROM tonight_session_participants p
        JOIN tonight_sessions s ON s.id = p.session_id
        WHERE p.user_id = ? AND s.status IN ('proposed', 'active') AND s.expires_at > now()
        """.trimIndent(),
        userId,
    )

private fun DataSource.sessionsCreatedThisWeek(userId: UUID): Int =
    queryInt(
        """
        SELECT COUNT(*)::integer FROM tonight_sessions
        WHERE created_by_user_id = ? AND created_at > now() - interval '7 days' AND status <> 'declined'
        """.trimIndent(),
        userId,
    )

private fun DataSource.finishedRow(sessionId: UUID): FinishedRow =
    connection.use { c ->
        c.prepareStatement("SELECT status, paused_at, ended_at FROM tonight_sessions WHERE id = ?").use { s ->
            s.setObject(1, sessionId)
            s.executeQuery().use { rs ->
                rs.next()
                FinishedRow(
                    status = rs.getString("status"),
                    pausedAt = rs.getTimestamp("paused_at")?.toInstant(),
                    endedAt = rs.getTimestamp("ended_at")?.toInstant(),
                )
            }
        }
    }
