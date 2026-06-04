package com.pynapli.server.psync

import com.pynapli.shared.network.dto.CreatePsyncSessionRequestDto
import com.pynapli.shared.network.dto.SubmitPsyncActionRequestDto
import com.pynapli.shared.network.dto.SuggestPsyncFiltersRequestDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.spec.style.StringSpec
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PsyncSessionServiceIntegrationTest :
    StringSpec({
        val postgisImage =
            DockerImageName
                .parse("postgis/postgis:16-3.4")
                .asCompatibleSubstituteFor("postgres")
        val postgres =
            PostgreSQLContainer<Nothing>(postgisImage).apply {
                withDatabaseName("pynapli_psync_test")
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
                        poolName = "pynapli-psync-test"
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

        fun service(clock: Clock = Clock.systemUTC()): PsyncSessionService =
            PsyncSessionService(
                repository = JdbcPsyncSessionRepository(dataSource),
                clock = clock,
            )

        // PS-2 service with a fake deck ranker (returns the given candidates in order) + real
        // block/hidden repos so cross-partner exclusion is exercised against the DB.
        fun deckService(
            candidates: List<UUID>,
            clock: Clock = Clock.systemUTC(),
        ): PsyncSessionService =
            PsyncSessionService(
                repository = JdbcPsyncSessionRepository(dataSource),
                deckGenerator =
                    object : com.pynapli.server.psync.PsyncDeckGenerator {
                        override suspend fun rankCandidates(
                            viewerUserId: UUID,
                            filters: com.pynapli.shared.network.dto.SwipeDeckRequestDto,
                            limit: Int,
                        ): List<com.pynapli.shared.network.dto.SwipeCardDto> =
                            candidates.take(limit).map { id ->
                                com.pynapli.shared.network.dto.SwipeCardDto(
                                    userId = id.toString(),
                                    displayName = "Candidate ${id.toString().take(4)}",
                                    rankToken = "rank:$id",
                                )
                            }
                    },
                blockRepository =
                    com.pynapli.server.safety
                        .JdbcBlockRepository(dataSource),
                hiddenProfileRepository =
                    com.pynapli.server.safety
                        .JdbcHiddenProfileRepository(dataSource),
                clock = clock,
            )

        suspend fun PsyncSessionService.readyFor(
            initiator: UUID,
            partner: UUID,
            deckSize: Int = 25,
        ): UUID {
            val created = create(initiator, CreatePsyncSessionRequestDto(deckSize = deckSize))
            accept(UUID.fromString(created.id), partner)
            return UUID.fromString(created.id)
        }

        // Seeds two profiled users + an active link between them. Returns (initiator, partner).
        fun seedLinkedPair(): Pair<UUID, UUID> {
            val initiator = UUID.randomUUID()
            val partner = UUID.randomUUID()
            dataSource.insertUserWithProfile(initiator, "Initiator Ivy")
            dataSource.insertUserWithProfile(partner, "Partner Pat")
            dataSource.insertActiveLink(initiator, partner)
            return initiator to partner
        }

        "create yields an invited session with initiator approved and partner pending" {
            runBlocking {
                val (initiator, partner) = seedLinkedPair()

                val session = service().create(initiator, CreatePsyncSessionRequestDto(deckSize = 30))

                assertEquals("invited", session.status)
                assertEquals(initiator.toString(), session.initiatorUserId)
                assertEquals(30, session.deckSize)
                val ini = session.participants.single { it.userId == initiator.toString() }
                val par = session.participants.single { it.userId == partner.toString() }
                assertEquals("initiator", ini.role)
                assertEquals("accepted", ini.consentStatus)
                assertTrue(ini.approved)
                assertEquals("partner", par.role)
                assertEquals("pending", par.consentStatus)
                assertFalse(par.approved)
            }
        }

        "partner accept flips the session to ready with both approved" {
            runBlocking {
                val (initiator, partner) = seedLinkedPair()
                val svc = service()

                val created = svc.create(initiator, CreatePsyncSessionRequestDto())
                val ready = svc.accept(UUID.fromString(created.id), partner)

                assertEquals("ready", ready.status)
                assertTrue(ready.participants.all { it.approved })
                assertTrue(ready.participants.all { it.consentStatus == "accepted" })
            }
        }

        "partner deny cancels the session" {
            runBlocking {
                val (initiator, partner) = seedLinkedPair()
                val svc = service()

                val created = svc.create(initiator, CreatePsyncSessionRequestDto())
                val denied = svc.deny(UUID.fromString(created.id), partner)

                assertEquals("cancelled", denied.status)
                assertEquals(
                    "denied",
                    denied.participants.single { it.userId == partner.toString() }.consentStatus,
                )
            }
        }

        "suggest-filters sets configuring, resets approvals except the suggester, then accept persists the new filters" {
            runBlocking {
                val (initiator, partner) = seedLinkedPair()
                val svc = service()
                val created = svc.create(initiator, CreatePsyncSessionRequestDto(deckSize = 25))
                val sessionId = UUID.fromString(created.id)

                // Partner suggests a distinct filter set.
                val newFilters =
                    SwipeDeckRequestDto(
                        radiusMeters = 12_345,
                        minimumAge = 27,
                        maximumAge = 41,
                        verifiedOnly = true,
                        intents = listOf("together", "social"),
                        dealbreakers = setOf("age", "distance"),
                    )
                val configuring =
                    svc.suggestFilters(sessionId, partner, SuggestPsyncFiltersRequestDto(filters = newFilters))

                assertEquals("configuring", configuring.status)
                // Suggester (partner) is re-approved; the other (initiator) must re-approve.
                assertTrue(configuring.participants.single { it.userId == partner.toString() }.approved)
                assertFalse(configuring.participants.single { it.userId == initiator.toString() }.approved)
                // Filters round-trip through JSONB.
                assertEquals(12_345, configuring.filters.radiusMeters)
                assertEquals(27, configuring.filters.minimumAge)
                assertEquals(setOf("age", "distance"), configuring.filters.dealbreakers)

                // The other partner re-approves -> ready, new filters intact.
                val ready = svc.accept(sessionId, initiator)
                assertEquals("ready", ready.status)
                assertTrue(ready.participants.all { it.approved })
                assertEquals(12_345, ready.filters.radiusMeters)
                assertEquals(listOf("together", "social"), ready.filters.intents)
                assertTrue(ready.filters.verifiedOnly)
            }
        }

        "leave cancels the session" {
            runBlocking {
                val (initiator, partner) = seedLinkedPair()
                val svc = service()

                val created = svc.create(initiator, CreatePsyncSessionRequestDto())
                val left = svc.leave(UUID.fromString(created.id), partner)

                assertEquals("cancelled", left.status)
            }
        }

        "create without an active link is rejected" {
            runBlocking {
                val lonely = UUID.randomUUID()
                dataSource.insertUserWithProfile(lonely, "Lonely Lou")

                val error =
                    assertFailsWith<PsyncException> {
                        service().create(lonely, CreatePsyncSessionRequestDto())
                    }
                assertEquals("PSYNC_NO_LINKED_PARTNER", error.code)
            }
        }

        "a second create for the same link is rejected" {
            runBlocking {
                val (initiator, partner) = seedLinkedPair()
                val svc = service()

                svc.create(initiator, CreatePsyncSessionRequestDto())
                // Either partner attempting another session on the same link is blocked.
                val error =
                    assertFailsWith<PsyncException> {
                        svc.create(partner, CreatePsyncSessionRequestDto())
                    }
                assertEquals("PSYNC_SESSION_ALREADY_ACTIVE", error.code)
            }
        }

        "deck size 0 is rejected" {
            runBlocking {
                val (initiator, _) = seedLinkedPair()
                val error =
                    assertFailsWith<PsyncException> {
                        service().create(initiator, CreatePsyncSessionRequestDto(deckSize = 0))
                    }
                assertEquals("INVALID_PSYNC_DECK_SIZE", error.code)
            }
        }

        "deck size 51 is rejected" {
            runBlocking {
                val (initiator, _) = seedLinkedPair()
                val error =
                    assertFailsWith<PsyncException> {
                        service().create(initiator, CreatePsyncSessionRequestDto(deckSize = 51))
                    }
                assertEquals("INVALID_PSYNC_DECK_SIZE", error.code)
            }
        }

        "get by a non-participant surfaces not-found" {
            runBlocking {
                val (initiator, _) = seedLinkedPair()
                val outsider = UUID.randomUUID()
                dataSource.insertUserWithProfile(outsider, "Outsider Oz")
                val svc = service()
                val created = svc.create(initiator, CreatePsyncSessionRequestDto())

                val error =
                    assertFailsWith<PsyncException> {
                        svc.get(UUID.fromString(created.id), outsider)
                    }
                assertEquals("PSYNC_SESSION_NOT_FOUND", error.code)
            }
        }

        "current returns the non-terminal session with display names" {
            runBlocking {
                val (initiator, partner) = seedLinkedPair()
                val svc = service()
                svc.create(initiator, CreatePsyncSessionRequestDto())

                val current = svc.current(initiator)
                assertNotNull(current.session)
                val names =
                    current.session!!
                        .participants
                        .mapNotNull { it.displayName }
                        .toSet()
                assertTrue("Initiator Ivy" in names)
                assertTrue("Partner Pat" in names)
                // Visible to the partner too.
                assertNotNull(svc.current(partner).session)
            }
        }

        "current excludes an expired session" {
            runBlocking {
                val now = Instant.parse("2026-06-01T20:00:00Z")
                val (initiator, _) = seedLinkedPair()
                // Create at `now` (24h TTL).
                service(Clock.fixed(now, ZoneOffset.UTC)).create(initiator, CreatePsyncSessionRequestDto())

                // Read 25 hours later: past expiry, so current() is empty.
                val later = service(Clock.fixed(now.plus(Duration.ofHours(25)), ZoneOffset.UTC))
                assertNull(later.current(initiator).session)
            }
        }

        "expireStale flips a past-expiry invited session to expired" {
            runBlocking {
                val now = Instant.parse("2026-06-01T20:00:00Z")
                val (initiator, _) = seedLinkedPair()
                val created =
                    service(Clock.fixed(now, ZoneOffset.UTC)).create(initiator, CreatePsyncSessionRequestDto())
                val sessionId = UUID.fromString(created.id)

                val swept = service(Clock.fixed(now.plus(Duration.ofHours(25)), ZoneOffset.UTC)).expireStale()
                assertEquals(1, swept)
                assertEquals("expired", dataSource.statusOf(sessionId))
            }
        }

        // ---- PS-2: shared deck + private swipes ----------------------------------------

        "both partners must start before the shared deck is generated" {
            runBlocking {
                val (initiator, partner) = seedLinkedPair()
                val candidates = List(3) { UUID.randomUUID() }
                candidates.forEachIndexed { i, c -> dataSource.insertUserWithProfile(c, "Cand $i") }
                val svc = deckService(candidates)
                val sessionId = svc.readyFor(initiator, partner)

                assertEquals("ready", svc.start(sessionId, initiator).status)
                assertEquals("ready", dataSource.statusOf(sessionId))

                val active = svc.start(sessionId, partner)
                assertEquals("active", active.status)
                assertEquals(3, svc.getDeck(sessionId, initiator).cards.size)
            }
        }

        "the shared deck excludes the partner and anyone blocked by either partner" {
            runBlocking {
                val (initiator, partner) = seedLinkedPair()
                val blockedByPartner = UUID.randomUUID()
                val clean = UUID.randomUUID()
                dataSource.insertUserWithProfile(blockedByPartner, "Blocked")
                dataSource.insertUserWithProfile(clean, "Clean")
                dataSource.insertBlock(partner, blockedByPartner)

                // The ranker offers the partner + a partner-blocked user + a clean user.
                val svc = deckService(listOf(partner, blockedByPartner, clean))
                val sessionId = svc.readyFor(initiator, partner)
                svc.start(sessionId, initiator)
                svc.start(sessionId, partner)

                val deck = svc.getDeck(sessionId, initiator)
                assertEquals(listOf(clean.toString()), deck.cards.map { it.card.userId })
            }
        }

        "submitAction records a private decision idempotently and never exposes the partner" {
            runBlocking {
                val (initiator, partner) = seedLinkedPair()
                val candidate = UUID.randomUUID()
                dataSource.insertUserWithProfile(candidate, "Cand")
                val svc = deckService(listOf(candidate))
                val sessionId = svc.readyFor(initiator, partner)
                svc.start(sessionId, initiator)
                svc.start(sessionId, partner)

                val token =
                    svc
                        .getDeck(sessionId, initiator)
                        .cards
                        .single()
                        .cardToken
                val first = svc.submitAction(sessionId, initiator, SubmitPsyncActionRequestDto(token, "like"))
                assertTrue(first.recorded)
                assertEquals(1, first.actedCount)

                val again = svc.submitAction(sessionId, initiator, SubmitPsyncActionRequestDto(token, "pass"))
                assertFalse(again.recorded) // first decision wins
                assertEquals(1, again.actedCount)

                // The initiator sees their own action; the partner never sees it.
                assertEquals(
                    "like",
                    svc
                        .getDeck(sessionId, initiator)
                        .cards
                        .single()
                        .viewerAction,
                )
                assertNull(
                    svc
                        .getDeck(sessionId, partner)
                        .cards
                        .single()
                        .viewerAction,
                )
            }
        }

        "submitAction rejects an unknown card token" {
            runBlocking {
                val (initiator, partner) = seedLinkedPair()
                val candidate = UUID.randomUUID()
                dataSource.insertUserWithProfile(candidate, "Cand")
                val svc = deckService(listOf(candidate))
                val sessionId = svc.readyFor(initiator, partner)
                svc.start(sessionId, initiator)
                svc.start(sessionId, partner)

                val error =
                    assertFailsWith<PsyncException> {
                        svc.submitAction(sessionId, initiator, SubmitPsyncActionRequestDto("not-a-real-token", "like"))
                    }
                assertEquals("PSYNC_INVALID_CARD", error.code)
            }
        }

        "pause then resume round-trips and actions are refused while paused" {
            runBlocking {
                val (initiator, partner) = seedLinkedPair()
                val candidate = UUID.randomUUID()
                dataSource.insertUserWithProfile(candidate, "Cand")
                val svc = deckService(listOf(candidate))
                val sessionId = svc.readyFor(initiator, partner)
                svc.start(sessionId, initiator)
                svc.start(sessionId, partner)
                val token =
                    svc
                        .getDeck(sessionId, initiator)
                        .cards
                        .single()
                        .cardToken

                assertEquals("paused", svc.pause(sessionId, initiator).status)
                val paused =
                    assertFailsWith<PsyncException> {
                        svc.submitAction(sessionId, partner, SubmitPsyncActionRequestDto(token, "like"))
                    }
                assertEquals("PSYNC_INVALID_STATE", paused.code)
                assertEquals("active", svc.resume(sessionId, partner).status)
            }
        }
    })

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

private fun DataSource.insertBlock(
    blockerUserId: UUID,
    blockedUserId: UUID,
) {
    connection.use { connection ->
        connection
            .prepareStatement(
                "INSERT INTO user_blocks (id, blocker_user_id, blocked_user_id) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, blockerUserId)
                statement.setObject(3, blockedUserId)
                statement.executeUpdate()
            }
    }
}

private fun DataSource.statusOf(sessionId: UUID): String? =
    connection.use { connection ->
        connection
            .prepareStatement("SELECT status FROM psync_sessions WHERE id = ?")
            .use { statement ->
                statement.setObject(1, sessionId)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) null else rs.getString("status")
                }
            }
    }
