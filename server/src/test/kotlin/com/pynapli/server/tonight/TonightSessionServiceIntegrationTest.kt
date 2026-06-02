package com.pynapli.server.tonight

import com.pynapli.server.subscriptions.EntitlementPolicy
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.shared.network.dto.ProposeTonightSessionRequestDto
import com.pynapli.shared.network.dto.SubscriptionTier
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.spec.style.StringSpec
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TonightSessionServiceIntegrationTest :
    StringSpec({
        val postgisImage =
            DockerImageName
                .parse("postgis/postgis:16-3.4")
                .asCompatibleSubstituteFor("postgres")
        val postgres =
            PostgreSQLContainer<Nothing>(postgisImage).apply {
                withDatabaseName("pynapli_tonight_test")
                withUsername("pynapli")
                withPassword("pynapli_test_password")
            }
        lateinit var dataSource: HikariDataSource

        beforeSpec { postgres.start() }
        afterSpec { postgres.stop() }

        beforeTest {
            dataSource =
                HikariDataSource(
                    HikariConfig().apply {
                        jdbcUrl = postgres.jdbcUrl
                        username = postgres.username
                        password = postgres.password
                        maximumPoolSize = 8
                        poolName = "pynapli-tonight-test"
                    },
                )
            Flyway
                .configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .also { flyway ->
                    flyway.clean()
                    flyway.migrate()
                }
        }

        afterTest { dataSource.close() }

        fun paidService(clock: Clock = Clock.systemUTC()): TonightSessionService {
            val subscriptions = mockk<SubscriptionService>()
            coEvery { subscriptions.getPolicy(any()) } returns policy(SubscriptionTier.SELECT)
            return TonightSessionService(
                repository = JdbcTonightSessionRepository(dataSource),
                subscriptionService = subscriptions,
                clock = clock,
            )
        }

        "solo propose goes active immediately with an approved owner" {
            runBlocking {
                val user = UUID.randomUUID()
                dataSource.insertUserWithProfile(user, "Solo Sam")
                val service = paidService()

                val session =
                    service.propose(
                        user,
                        ProposeTonightSessionRequestDto(mode = "solo", intent = "solo"),
                    )

                assertEquals("active", session.status)
                assertNotNull(session.startedAt)
                val owner = session.participants.single()
                assertEquals(user.toString(), owner.userId)
                assertEquals("owner", owner.role)
                assertEquals("approved", owner.approvalStatus)
            }
        }

        "together propose is proposed then activates when partner approves" {
            runBlocking {
                val user = UUID.randomUUID()
                val partner = UUID.randomUUID()
                dataSource.insertUserWithProfile(user, "Owner Olivia")
                dataSource.insertUserWithProfile(partner, "Partner Pat")
                dataSource.insertActiveLink(user, partner)
                val service = paidService()

                val proposed =
                    service.propose(
                        user,
                        ProposeTonightSessionRequestDto(mode = "together", intent = "together", durationHours = 3),
                    )
                assertEquals("proposed", proposed.status)
                assertNull(proposed.startedAt)
                val ownerRow = proposed.participants.single { it.userId == user.toString() }
                val partnerRow = proposed.participants.single { it.userId == partner.toString() }
                assertEquals("approved", ownerRow.approvalStatus)
                assertEquals("pending", partnerRow.approvalStatus)

                val sessionId = UUID.fromString(proposed.id)
                val activated = service.approve(sessionId, partner)
                assertEquals("active", activated.status)
                assertNotNull(activated.startedAt)
                assertTrue(activated.participants.all { it.approvalStatus == "approved" })
                // expires_at is reset to now + originalWindow (3h) at activation.
                val started = Instant.parse(activated.startedAt)
                val expires = Instant.parse(activated.expiresAt)
                val window = Duration.between(started, expires)
                assertTrue(window.toMinutes() in 175..185, "window was ${window.toMinutes()} minutes")
            }
        }

        "partner decline marks the session declined" {
            runBlocking {
                val user = UUID.randomUUID()
                val partner = UUID.randomUUID()
                dataSource.insertUserWithProfile(user, "Owner")
                dataSource.insertUserWithProfile(partner, "Partner")
                dataSource.insertActiveLink(user, partner)
                val service = paidService()

                val proposed =
                    service.propose(user, ProposeTonightSessionRequestDto(mode = "together", intent = "together"))
                val declined = service.decline(UUID.fromString(proposed.id), partner)

                assertEquals("declined", declined.status)
                assertEquals(
                    "declined",
                    declined.participants.single { it.userId == partner.toString() }.approvalStatus,
                )
            }
        }

        "either participant can end the session" {
            runBlocking {
                val user = UUID.randomUUID()
                val partner = UUID.randomUUID()
                dataSource.insertUserWithProfile(user, "Owner")
                dataSource.insertUserWithProfile(partner, "Partner")
                dataSource.insertActiveLink(user, partner)
                val service = paidService()

                val proposed =
                    service.propose(user, ProposeTonightSessionRequestDto(mode = "together", intent = "together"))
                // The partner (who has not approved) can still end it.
                val ended = service.end(UUID.fromString(proposed.id), partner)
                assertEquals("ended", ended.status)
            }
        }

        "together propose without an active partner is rejected" {
            runBlocking {
                val user = UUID.randomUUID()
                dataSource.insertUserWithProfile(user, "Lonely")
                val service = paidService()

                val error =
                    assertFailsWith<TonightSessionException> {
                        service.propose(
                            user,
                            ProposeTonightSessionRequestDto(mode = "together", intent = "together"),
                        )
                    }
                assertEquals("TONIGHT_NO_LINKED_PARTNER", error.code)
            }
        }

        "a second propose while one is live is rejected" {
            runBlocking {
                val user = UUID.randomUUID()
                dataSource.insertUserWithProfile(user, "Busy")
                val service = paidService()

                service.propose(user, ProposeTonightSessionRequestDto(mode = "solo", intent = "solo"))
                val error =
                    assertFailsWith<TonightSessionException> {
                        service.propose(user, ProposeTonightSessionRequestDto(mode = "solo", intent = "social"))
                    }
                assertEquals("TONIGHT_ALREADY_ACTIVE", error.code)
            }
        }

        "note over 100 characters is rejected" {
            runBlocking {
                val user = UUID.randomUUID()
                dataSource.insertUserWithProfile(user, "Wordy")
                val service = paidService()

                val error =
                    assertFailsWith<TonightSessionException> {
                        service.propose(
                            user,
                            ProposeTonightSessionRequestDto(mode = "solo", intent = "solo", note = "x".repeat(101)),
                        )
                    }
                assertEquals("TONIGHT_NOTE_TOO_LONG", error.code)
            }
        }

        "intent that does not match the mode is rejected" {
            runBlocking {
                val user = UUID.randomUUID()
                dataSource.insertUserWithProfile(user, "Mismatch")
                val service = paidService()

                val error =
                    assertFailsWith<TonightSessionException> {
                        // solo mode cannot carry the 'together' intent.
                        service.propose(
                            user,
                            ProposeTonightSessionRequestDto(mode = "solo", intent = "together"),
                        )
                    }
                assertEquals("INVALID_TONIGHT_INTENT", error.code)
            }
        }

        "free tier weekly cap blocks the fourth session" {
            runBlocking {
                val user = UUID.randomUUID()
                dataSource.insertUserWithProfile(user, "Free Fred")
                val subscriptions = mockk<SubscriptionService>()
                coEvery { subscriptions.getPolicy(any()) } returns policy(SubscriptionTier.FREE)
                val service =
                    TonightSessionService(
                        repository = JdbcTonightSessionRepository(dataSource),
                        subscriptionService = subscriptions,
                    )

                // Seed three non-declined sessions inside the 7-day window.
                repeat(3) { dataSource.insertEndedSessionCreatedDaysAgo(user, daysAgo = 1) }

                val error =
                    assertFailsWith<TonightSessionException> {
                        service.propose(user, ProposeTonightSessionRequestDto(mode = "solo", intent = "solo"))
                    }
                assertEquals("TONIGHT_WEEKLY_LIMIT_REACHED", error.code)
            }
        }

        "paid tier is not capped after three sessions" {
            runBlocking {
                val user = UUID.randomUUID()
                dataSource.insertUserWithProfile(user, "Select Sue")
                val service = paidService()

                repeat(3) { dataSource.insertEndedSessionCreatedDaysAgo(user, daysAgo = 1) }

                val session = service.propose(user, ProposeTonightSessionRequestDto(mode = "solo", intent = "solo"))
                assertEquals("active", session.status)
            }
        }

        "current returns participants with display names" {
            runBlocking {
                val user = UUID.randomUUID()
                val partner = UUID.randomUUID()
                dataSource.insertUserWithProfile(user, "Owner Olga")
                dataSource.insertUserWithProfile(partner, "Partner Pim")
                dataSource.insertActiveLink(user, partner)
                val service = paidService()

                service.propose(user, ProposeTonightSessionRequestDto(mode = "together", intent = "together"))

                val current = service.current(user)
                assertNotNull(current.session)
                val names =
                    current.session!!
                        .participants
                        .map { it.displayName }
                        .toSet()
                assertTrue("Owner Olga" in names)
                assertTrue("Partner Pim" in names)
            }
        }

        "current does not return an expired session" {
            runBlocking {
                val now = Instant.parse("2026-06-01T20:00:00Z")
                val proposeClock = Clock.fixed(now, ZoneOffset.UTC)
                val user = UUID.randomUUID()
                dataSource.insertUserWithProfile(user, "Expiring Ed")

                // Propose a 1-hour solo session at `now`.
                val service = paidService(proposeClock)
                service.propose(user, ProposeTonightSessionRequestDto(mode = "solo", intent = "solo", durationHours = 1))

                // Read 2 hours later: the session is past expiry, so current() must be empty
                // even though no sweep has run.
                val laterService = paidService(Clock.fixed(now.plus(Duration.ofHours(2)), ZoneOffset.UTC))
                val current = laterService.current(user)
                assertNull(current.session)
            }
        }
    })

private fun policy(tier: String): EntitlementPolicy =
    EntitlementPolicy(
        tier = tier,
        adSupported = tier == SubscriptionTier.FREE,
        activityRevealLimit = if (tier == SubscriptionTier.FREE) 1 else Int.MAX_VALUE,
        advancedFiltersAllowed = tier != SubscriptionTier.FREE,
        introNoteInventoryRequired = true,
        gridPageLimit = 60,
        gridResultDepthLimit = 60,
    )

private fun DataSource.insertUserWithProfile(
    userId: UUID,
    displayName: String,
) {
    connection.use { connection ->
        connection
            .prepareStatement("INSERT INTO users (id, status) VALUES (?, 'active')")
            .use { statement ->
                statement.setObject(1, userId)
                statement.executeUpdate()
            }
        connection
            .prepareStatement(
                """
                INSERT INTO profiles (user_id, display_name, display_age, relationship_context, moderation_status, visibility_status)
                VALUES (?, ?, 30, 'single', 'approved', 'visible')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setString(2, displayName)
                statement.executeUpdate()
            }
    }
}

private fun DataSource.insertActiveLink(
    userId: UUID,
    linkedUserId: UUID,
) {
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO user_profile_links (id, user_id, linked_user_id, status, accepted_at)
                VALUES (?, ?, ?, 'active', now())
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, userId)
                statement.setObject(3, linkedUserId)
                statement.executeUpdate()
            }
    }
}

private fun DataSource.insertEndedSessionCreatedDaysAgo(
    userId: UUID,
    daysAgo: Long,
) {
    val sessionId = UUID.randomUUID()
    val createdAt = Instant.now().minus(Duration.ofDays(daysAgo))
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO tonight_sessions (id, created_by_user_id, mode, intent, host_mode, status, created_at, started_at, expires_at, ended_at)
                VALUES (?, ?, 'solo', 'solo', 'public_meet_first', 'ended', ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, sessionId)
                statement.setObject(2, userId)
                statement.setTimestamp(3, Timestamp.from(createdAt))
                statement.setTimestamp(4, Timestamp.from(createdAt))
                statement.setTimestamp(5, Timestamp.from(createdAt.plus(Duration.ofHours(2))))
                statement.setTimestamp(6, Timestamp.from(createdAt.plus(Duration.ofHours(2))))
                statement.executeUpdate()
            }
        connection
            .prepareStatement(
                """
                INSERT INTO tonight_session_participants (session_id, user_id, role, approval_status, approved_at)
                VALUES (?, ?, 'owner', 'approved', ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, sessionId)
                statement.setObject(2, userId)
                statement.setTimestamp(3, Timestamp.from(createdAt))
                statement.executeUpdate()
            }
    }
}
