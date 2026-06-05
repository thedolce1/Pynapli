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

        // PS-6a: service whose deck size is gated by the initiator's tier. With no subscription
        // rows the initiator is Free (psyncMaxDeckSize = 10).
        fun freeTierService(clock: Clock = Clock.systemUTC()): PsyncSessionService =
            PsyncSessionService(
                repository = JdbcPsyncSessionRepository(dataSource),
                subscriptionService =
                    com.pynapli.server.subscriptions.SubscriptionService(
                        com.pynapli.server.subscriptions
                            .JdbcSubscriptionRepository(dataSource),
                    ),
                clock = clock,
            )

        // PS-2 service with a fake deck ranker (returns the given candidates in order) + real
        // block/hidden repos so cross-partner exclusion is exercised against the DB.
        fun deckService(
            candidates: List<UUID>,
            clock: Clock = Clock.systemUTC(),
            broadcaster: PsyncBroadcaster? = null,
            rateLimitService: com.pynapli.server.safety.RateLimitService? = null,
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
                broadcaster = broadcaster,
                rateLimitService = rateLimitService,
                clock = clock,
            )

        // PS-4: deck service wired to a REAL ActivityService so match resolution surfaces in the
        // Activity feed (other ActivityService deps are null — recordMatch only needs the repo).
        fun deckServiceWithActivity(
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
                activityService =
                    com.pynapli.server.activity.ActivityService(
                        repository =
                            com.pynapli.server.activity
                                .JdbcActivityRepository(dataSource),
                    ),
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

        // Drives a started (active) session to both partners acting on a single-card deck.
        suspend fun PsyncSessionService.actOnSingle(
            sessionId: UUID,
            user: UUID,
            action: String,
        ): com.pynapli.shared.network.dto.PsyncActionResponseDto {
            val token =
                getDeck(sessionId, user)
                    .cards
                    .single()
                    .cardToken
            return submitAction(sessionId, user, SubmitPsyncActionRequestDto(token, action))
        }

        // Acts on a specific target's card (deck may have multiple cards).
        suspend fun PsyncSessionService.actOn(
            sessionId: UUID,
            user: UUID,
            target: UUID,
            action: String,
        ): com.pynapli.shared.network.dto.PsyncActionResponseDto {
            val token =
                getDeck(sessionId, user)
                    .cards
                    .first { it.card.userId == target.toString() }
                    .cardToken
            return submitAction(sessionId, user, SubmitPsyncActionRequestDto(token, action))
        }

        // Seeds an active single-card session over [target], returns its id.
        suspend fun PsyncSessionService.activeSessionOver(
            initiator: UUID,
            partner: UUID,
            target: UUID,
        ): UUID {
            val sessionId = readyFor(initiator, partner)
            start(sessionId, initiator)
            start(sessionId, partner)
            return sessionId
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

        // ---- PS-3: match resolution + recap --------------------------------------------

        "complete resolves a together match when both like a together-open target who liked back" {
            runBlocking {
                val (initiator, partner) = seedLinkedPair()
                val target = UUID.randomUUID()
                dataSource.insertUserWithProfile(target, "Target")
                dataSource.setTogetherEnabled(target)
                dataSource.insertSwipeLike(target, initiator) // target reciprocated

                val svc = deckService(listOf(target))
                val sessionId = svc.activeSessionOver(initiator, partner, target)
                svc.actOnSingle(sessionId, initiator, "like")
                svc.actOnSingle(sessionId, partner, "like")

                val recap = svc.complete(sessionId, initiator)
                assertEquals("completed", recap.status)
                assertEquals(1, recap.profilesReviewed)
                assertEquals(1, recap.bothLikedCount)
                assertEquals(1, recap.togetherMatchCount)
                assertEquals(0, recap.soloMatchCount)
                assertEquals(listOf(target.toString()), recap.togetherMatches.map { it.targetUserId })
                // The canonical match exists for BOTH partners.
                assertEquals(1, dataSource.countMatchesFor(initiator))
                assertEquals(1, dataSource.countMatchesFor(partner))
            }
        }

        "a one-sided like with reciprocation yields a private solo passthrough match" {
            runBlocking {
                val (initiator, partner) = seedLinkedPair()
                val target = UUID.randomUUID()
                dataSource.insertUserWithProfile(target, "Target")
                dataSource.insertSwipeLike(target, initiator) // target liked the initiator only

                val svc = deckService(listOf(target))
                val sessionId = svc.activeSessionOver(initiator, partner, target)
                svc.actOnSingle(sessionId, initiator, "like")
                svc.actOnSingle(sessionId, partner, "pass")

                val recap = svc.complete(sessionId, initiator)
                assertEquals(0, recap.togetherMatchCount)
                assertEquals(1, recap.soloMatchCount) // the initiator's own solo match
                assertEquals(0, recap.bothLikedCount)
                // The partner's recap never shows the initiator's private solo match.
                val partnerRecap = svc.getRecap(sessionId, partner)
                assertEquals(0, partnerRecap.soloMatchCount)
                assertEquals(1, dataSource.countMatchesFor(initiator))
                assertEquals(0, dataSource.countMatchesFor(partner))
            }
        }

        "both like but the target is not together-open -> a solo match per reciprocated partner" {
            runBlocking {
                val (initiator, partner) = seedLinkedPair()
                val target = UUID.randomUUID()
                dataSource.insertUserWithProfile(target, "Target")
                dataSource.insertSwipeLike(target, initiator)
                dataSource.insertSwipeLike(target, partner) // liked both, but not together-open

                val svc = deckService(listOf(target))
                val sessionId = svc.activeSessionOver(initiator, partner, target)
                svc.actOnSingle(sessionId, initiator, "like")
                svc.actOnSingle(sessionId, partner, "like")

                val recap = svc.complete(sessionId, initiator)
                assertEquals(0, recap.togetherMatchCount)
                assertEquals(1, recap.bothLikedCount)
                assertEquals(1, recap.soloMatchCount)
                assertEquals(1, svc.getRecap(sessionId, partner).soloMatchCount)
            }
        }

        "a block applied mid-session overrides match resolution" {
            runBlocking {
                val (initiator, partner) = seedLinkedPair()
                val target = UUID.randomUUID()
                dataSource.insertUserWithProfile(target, "Target")
                dataSource.setTogetherEnabled(target)
                dataSource.insertSwipeLike(target, initiator)

                // Two-card deck so acting on the target alone does NOT auto-complete the session.
                val filler = UUID.randomUUID()
                dataSource.insertUserWithProfile(filler, "Filler")
                val svc = deckService(listOf(target, filler))
                val sessionId = svc.activeSessionOver(initiator, partner, target)
                // Both like the target while it's still valid...
                svc.actOn(sessionId, initiator, target, "like")
                svc.actOn(sessionId, partner, target, "like")
                // ...then a block lands and the session is completed explicitly.
                dataSource.insertBlock(initiator, target)
                svc.complete(sessionId, initiator)

                val recap = svc.getRecap(sessionId, initiator)
                assertEquals(0, recap.togetherMatchCount)
                assertEquals(0, recap.soloMatchCount)
                assertEquals(0, dataSource.countMatchesFor(initiator))
            }
        }

        "the deck auto-completes once both partners act on every card" {
            runBlocking {
                val (initiator, partner) = seedLinkedPair()
                val target = UUID.randomUUID()
                dataSource.insertUserWithProfile(target, "Target")
                dataSource.setTogetherEnabled(target)
                dataSource.insertSwipeLike(target, initiator)

                val svc = deckService(listOf(target))
                val sessionId = svc.activeSessionOver(initiator, partner, target)

                val first = svc.actOnSingle(sessionId, initiator, "like")
                assertFalse(first.sessionCompleted) // partner hasn't finished yet
                val second = svc.actOnSingle(sessionId, partner, "like")
                assertTrue(second.sessionCompleted)

                assertEquals("completed", dataSource.statusOf(sessionId))
                assertEquals(1, svc.getRecap(sessionId, initiator).togetherMatchCount)
            }
        }

        "getRecap rejects a session that is not completed, and complete is idempotent" {
            runBlocking {
                val (initiator, partner) = seedLinkedPair()
                val target = UUID.randomUUID()
                dataSource.insertUserWithProfile(target, "Target")
                dataSource.setTogetherEnabled(target)
                dataSource.insertSwipeLike(target, initiator)

                val svc = deckService(listOf(target))
                val sessionId = svc.activeSessionOver(initiator, partner, target)

                val notDone =
                    assertFailsWith<PsyncException> { svc.getRecap(sessionId, initiator) }
                assertEquals("PSYNC_INVALID_STATE", notDone.code)

                svc.actOnSingle(sessionId, initiator, "like")
                svc.actOnSingle(sessionId, partner, "like")
                val first = svc.complete(sessionId, initiator)
                val second = svc.complete(sessionId, partner) // idempotent re-complete
                assertEquals(first.togetherMatchCount, second.togetherMatchCount)
                assertEquals(1, dataSource.countMatchesFor(initiator)) // not double-inserted
            }
        }

        // ---- PS-4: matches surface in Activity -----------------------------------------

        "a together match surfaces in both partners' Activity feed and is not double-emitted" {
            runBlocking {
                val (initiator, partner) = seedLinkedPair()
                val target = UUID.randomUUID()
                dataSource.insertUserWithProfile(target, "Target")
                dataSource.setTogetherEnabled(target)
                dataSource.insertSwipeLike(target, initiator)

                val svc = deckServiceWithActivity(listOf(target))
                val sessionId = svc.activeSessionOver(initiator, partner, target)
                svc.actOnSingle(sessionId, initiator, "like")
                // The partner's like exhausts the deck -> auto-complete resolves + emits.
                val finished = svc.actOnSingle(sessionId, partner, "like")
                assertTrue(finished.sessionCompleted)

                // Both partners get one match activity row; the target gets one per partner.
                assertEquals(1, dataSource.countActivityEvents(initiator, "match"))
                assertEquals(1, dataSource.countActivityEvents(partner, "match"))
                assertEquals(2, dataSource.countActivityEvents(target, "match"))

                // Re-completing must not duplicate the activity rows.
                svc.complete(sessionId, initiator)
                assertEquals(1, dataSource.countActivityEvents(initiator, "match"))
                assertEquals(2, dataSource.countActivityEvents(target, "match"))
            }
        }

        "a private solo match surfaces only for the matched partner, not the other" {
            runBlocking {
                val (initiator, partner) = seedLinkedPair()
                val target = UUID.randomUUID()
                dataSource.insertUserWithProfile(target, "Target")
                dataSource.insertSwipeLike(target, initiator) // target liked only the initiator

                val svc = deckServiceWithActivity(listOf(target))
                val sessionId = svc.activeSessionOver(initiator, partner, target)
                svc.actOnSingle(sessionId, initiator, "like")
                svc.actOnSingle(sessionId, partner, "pass")
                svc.complete(sessionId, initiator)

                assertEquals(1, dataSource.countActivityEvents(initiator, "match"))
                assertEquals(0, dataSource.countActivityEvents(partner, "match"))
                assertEquals(1, dataSource.countActivityEvents(target, "match"))
            }
        }

        // ---- PS-4b: realtime session channel + nudge -----------------------------------

        "session transitions broadcast convenience deltas to the right partners" {
            runBlocking {
                val (initiator, partner) = seedLinkedPair()
                val target = UUID.randomUUID()
                dataSource.insertUserWithProfile(target, "Target")
                val broadcaster = PsyncBroadcaster(replay = 64)
                val svc = deckService(listOf(target), broadcaster = broadcaster)

                val sessionId = svc.readyFor(initiator, partner)
                svc.start(sessionId, initiator) // single tap -> partner_started to the partner
                svc.start(sessionId, partner) // both in -> session_active to both
                svc.actOnSingle(sessionId, initiator, "like") // initiator finishes -> partner_finished to partner
                svc.actOnSingle(sessionId, partner, "like") // both done -> session_completed to both

                val deltas = broadcaster.deltas.replayCache
                // partner_started went only to the partner (the one who hadn't tapped yet).
                assertEquals(
                    listOf(partner),
                    deltas.filter { it.type == PsyncBroadcaster.PARTNER_STARTED }.map { it.recipientUserId },
                )
                // session_active + session_completed each fan out to BOTH participants.
                assertEquals(2, deltas.count { it.type == PsyncBroadcaster.SESSION_ACTIVE })
                assertEquals(2, deltas.count { it.type == PsyncBroadcaster.SESSION_COMPLETED })
                // partner_finished went only to the partner (initiator finished first).
                assertEquals(
                    listOf(partner),
                    deltas.filter { it.type == PsyncBroadcaster.PARTNER_FINISHED }.map { it.recipientUserId },
                )
                // No delta ever names a swiped target.
                assertTrue(deltas.all { it.sessionId == sessionId })
            }
        }

        "pause and resume broadcast to both partners" {
            runBlocking {
                val (initiator, partner) = seedLinkedPair()
                val target = UUID.randomUUID()
                dataSource.insertUserWithProfile(target, "Target")
                val broadcaster = PsyncBroadcaster(replay = 64)
                val svc = deckService(listOf(target), broadcaster = broadcaster)
                val sessionId = svc.activeSessionOver(initiator, partner, target)

                svc.pause(sessionId, initiator)
                svc.resume(sessionId, partner)

                val deltas = broadcaster.deltas.replayCache
                assertEquals(2, deltas.count { it.type == PsyncBroadcaster.SESSION_PAUSED })
                assertEquals(2, deltas.count { it.type == PsyncBroadcaster.SESSION_RESUMED })
            }
        }

        "nudge pings only the other partner and is refused on cooldown" {
            runBlocking {
                val (initiator, partner) = seedLinkedPair()
                val target = UUID.randomUUID()
                dataSource.insertUserWithProfile(target, "Target")
                val broadcaster = PsyncBroadcaster(replay = 64)
                val svc = deckService(listOf(target), broadcaster = broadcaster, rateLimitService = countingRateLimiter())
                val sessionId = svc.activeSessionOver(initiator, partner, target)

                assertTrue(svc.nudge(sessionId, initiator).sent)
                val cooled = assertFailsWith<PsyncException> { svc.nudge(sessionId, initiator) }
                assertEquals("PSYNC_NUDGE_COOLDOWN", cooled.code)

                val nudges = broadcaster.deltas.replayCache.filter { it.type == PsyncBroadcaster.NUDGE }
                assertEquals(listOf(partner), nudges.map { it.recipientUserId })
            }
        }

        "nudge rejects a non-participant" {
            runBlocking {
                val (initiator, partner) = seedLinkedPair()
                val target = UUID.randomUUID()
                dataSource.insertUserWithProfile(target, "Target")
                val outsider = UUID.randomUUID()
                dataSource.insertUserWithProfile(outsider, "Outsider")
                val svc = deckService(listOf(target), broadcaster = PsyncBroadcaster(replay = 8))
                val sessionId = svc.activeSessionOver(initiator, partner, target)

                val error = assertFailsWith<PsyncException> { svc.nudge(sessionId, outsider) }
                assertEquals("PSYNC_SESSION_NOT_FOUND", error.code)
            }
        }

        // ---- PS-6a: deck-size entitlement gating ---------------------------------------

        "create rejects a deck larger than the initiator's tier allows (Free caps at 10)" {
            runBlocking {
                val (initiator, _) = seedLinkedPair()
                val svc = freeTierService()

                val error =
                    assertFailsWith<PsyncException> {
                        svc.create(initiator, CreatePsyncSessionRequestDto(deckSize = 25))
                    }
                assertEquals("PSYNC_DECK_SIZE_NOT_ALLOWED", error.code)
            }
        }

        "create allows a deck within the initiator's tier (Free up to 10)" {
            runBlocking {
                val (initiator, _) = seedLinkedPair()
                val svc = freeTierService()

                val session = svc.create(initiator, CreatePsyncSessionRequestDto(deckSize = 10))
                assertEquals("invited", session.status)
                assertEquals(10, session.deckSize)
            }
        }

        // ---- PS-6b: mid-session block invalidates the target in the live deck ----------

        "a block applied mid-session removes the target from the live deck" {
            runBlocking {
                val (initiator, partner) = seedLinkedPair()
                val keep = UUID.randomUUID()
                val dropped = UUID.randomUUID()
                dataSource.insertUserWithProfile(keep, "Keep")
                dataSource.insertUserWithProfile(dropped, "Dropped")

                val svc = deckService(listOf(keep, dropped))
                val sessionId = svc.activeSessionOver(initiator, partner, keep)
                // Deck starts with both cards.
                assertEquals(
                    listOf(keep.toString(), dropped.toString()),
                    svc.getDeck(sessionId, initiator).cards.map { it.card.userId },
                )

                // The partner blocks one target mid-session.
                dataSource.insertBlock(partner, dropped)

                assertEquals(
                    listOf(keep.toString()),
                    svc.getDeck(sessionId, initiator).cards.map { it.card.userId },
                )
                // The other partner's live deck drops it too.
                assertEquals(
                    listOf(keep.toString()),
                    svc.getDeck(sessionId, partner).cards.map { it.card.userId },
                )
            }
        }

        // ---- PS-6c: ending the partner link cancels a live Psync session ---------------

        "unlinking the partner cancels the active Psync session" {
            runBlocking {
                val (initiator, partner) = seedLinkedPair()
                val created = service().create(initiator, CreatePsyncSessionRequestDto())
                val sessionId = UUID.fromString(created.id)
                assertEquals("invited", dataSource.statusOf(sessionId))

                val unlinked =
                    com.pynapli.server.profilelink
                        .JdbcProfileLinkRepository(dataSource)
                        .unlinkCurrent(initiator)

                assertTrue(unlinked)
                assertEquals("cancelled", dataSource.statusOf(sessionId))
            }
        }

        // ---- PS-6d: opt-in solo-share in the recap -------------------------------------

        "the partner's solo count appears in the recap only when the partner opted to share" {
            runBlocking {
                val (initiator, partner) = seedLinkedPair()
                val target = UUID.randomUUID()
                dataSource.insertUserWithProfile(target, "Target")
                dataSource.insertSwipeLike(target, partner) // target liked the PARTNER only

                val svc = deckService(listOf(target))
                val sessionId = svc.activeSessionOver(initiator, partner, target)
                svc.actOnSingle(sessionId, initiator, "pass")
                svc.actOnSingle(sessionId, partner, "like") // partner gets a solo passthrough; auto-completes

                // Private by default — the initiator's recap hides the partner's solo count.
                assertNull(svc.getRecap(sessionId, initiator).partnerSoloMatchCount)
                assertEquals(0, svc.getRecap(sessionId, initiator).soloMatchCount)

                // After the partner opts in, the initiator's recap surfaces the shared count.
                dataSource.setSharePsyncSolo(partner)
                assertEquals(1, svc.getRecap(sessionId, initiator).partnerSoloMatchCount)
            }
        }

        // ---- PS-6e: advanced-filter gating ---------------------------------------------

        "create rejects advanced Psync filters on the Free tier" {
            runBlocking {
                val (initiator, _) = seedLinkedPair()
                val svc = freeTierService()

                val error =
                    assertFailsWith<PsyncException> {
                        svc.create(
                            initiator,
                            CreatePsyncSessionRequestDto(
                                deckSize = 5,
                                filters = SwipeDeckRequestDto(lifestyleTags = listOf("soft_swap")),
                            ),
                        )
                    }
                assertEquals("PSYNC_ADVANCED_FILTERS_NOT_ALLOWED", error.code)
            }
        }

        "create allows basic Psync filters on the Free tier" {
            runBlocking {
                val (initiator, _) = seedLinkedPair()
                val svc = freeTierService()

                val session =
                    svc.create(
                        initiator,
                        CreatePsyncSessionRequestDto(
                            deckSize = 5,
                            filters = SwipeDeckRequestDto(minimumAge = 25, maximumAge = 40, verifiedOnly = true),
                        ),
                    )
                assertEquals("invited", session.status)
            }
        }

        // ---- PS-4c: dedicated Psync Activity feed cards --------------------------------

        "creating an invite records a psync_invite card for the invitee only" {
            runBlocking {
                val (initiator, partner) = seedLinkedPair()
                deckServiceWithActivity(emptyList()).create(initiator, CreatePsyncSessionRequestDto())

                assertEquals(1, dataSource.countActivityEvents(partner, "psync_invite"))
                assertEquals(0, dataSource.countActivityEvents(initiator, "psync_invite"))
            }
        }

        "starting records a psync_started card for both partners" {
            runBlocking {
                val (initiator, partner) = seedLinkedPair()
                val target = UUID.randomUUID()
                dataSource.insertUserWithProfile(target, "Target")
                val svc = deckServiceWithActivity(listOf(target))
                svc.activeSessionOver(initiator, partner, target)

                assertEquals(1, dataSource.countActivityEvents(initiator, "psync_started"))
                assertEquals(1, dataSource.countActivityEvents(partner, "psync_started"))
            }
        }

        "completing records a psync_recap card for both partners (once)" {
            runBlocking {
                val (initiator, partner) = seedLinkedPair()
                val target = UUID.randomUUID()
                dataSource.insertUserWithProfile(target, "Target")
                val svc = deckServiceWithActivity(listOf(target))
                val sessionId = svc.activeSessionOver(initiator, partner, target)
                svc.actOnSingle(sessionId, initiator, "like")
                svc.actOnSingle(sessionId, partner, "like") // auto-completes
                svc.complete(sessionId, initiator) // idempotent re-complete

                assertEquals(1, dataSource.countActivityEvents(initiator, "psync_recap"))
                assertEquals(1, dataSource.countActivityEvents(partner, "psync_recap"))
            }
        }

        "expiry records a psync_expired card for both partners" {
            runBlocking {
                val now = Instant.parse("2026-06-01T20:00:00Z")
                val (initiator, partner) = seedLinkedPair()
                deckServiceWithActivity(emptyList(), Clock.fixed(now, ZoneOffset.UTC))
                    .create(initiator, CreatePsyncSessionRequestDto())

                val swept =
                    deckServiceWithActivity(emptyList(), Clock.fixed(now.plus(Duration.ofHours(25)), ZoneOffset.UTC))
                        .expireStale()

                assertEquals(1, swept)
                assertEquals(1, dataSource.countActivityEvents(initiator, "psync_expired"))
                assertEquals(1, dataSource.countActivityEvents(partner, "psync_expired"))
            }
        }
    })

// PS-4b: in-memory rate limiter — psync_nudge policy is limit 1 / 60s, so the first nudge for a
// key is allowed and the second is denied.
private fun countingRateLimiter(): com.pynapli.server.safety.RateLimitService =
    com.pynapli.server.safety.RateLimitService(
        object : com.pynapli.server.safety.RateLimitRepository {
            private val counts = mutableMapOf<String, Long>()

            override suspend fun increment(
                key: String,
                windowSeconds: Long,
            ): com.pynapli.server.safety.RateLimitCounter {
                val next = (counts[key] ?: 0L) + 1L
                counts[key] = next
                return com.pynapli.server.safety
                    .RateLimitCounter(count = next, ttlSeconds = windowSeconds)
            }
        },
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

private fun DataSource.setTogetherEnabled(userId: UUID) {
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO discovery_settings (user_id, together_enabled) VALUES (?, true)
                ON CONFLICT (user_id) DO UPDATE SET together_enabled = true
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeUpdate()
            }
    }
}

private fun DataSource.setSharePsyncSolo(userId: UUID) {
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO discovery_settings (user_id, share_psync_solo_matches) VALUES (?, true)
                ON CONFLICT (user_id) DO UPDATE SET share_psync_solo_matches = true
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeUpdate()
            }
    }
}

private fun DataSource.insertSwipeLike(
    actorUserId: UUID,
    targetUserId: UUID,
) {
    connection.use { connection ->
        connection
            .prepareStatement(
                "INSERT INTO swipe_actions (id, actor_user_id, target_user_id, action) VALUES (?, ?, ?, 'like')",
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, actorUserId)
                statement.setObject(3, targetUserId)
                statement.executeUpdate()
            }
    }
}

private fun DataSource.countMatchesFor(userId: UUID): Int =
    connection.use { connection ->
        connection
            .prepareStatement("SELECT COUNT(*) FROM matches WHERE user_a = ? OR user_b = ?")
            .use { statement ->
                statement.setObject(1, userId)
                statement.setObject(2, userId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
    }

private fun DataSource.countActivityEvents(
    recipientUserId: UUID,
    eventType: String,
): Int =
    connection.use { connection ->
        connection
            .prepareStatement(
                "SELECT COUNT(*) FROM activity_events WHERE recipient_user_id = ? AND event_type = ? AND deleted_at IS NULL",
            ).use { statement ->
                statement.setObject(1, recipientUserId)
                statement.setString(2, eventType)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
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
