package com.pynapli.server.swipe

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.subscriptions.LinkedPartnerSubscriptionRecord
import com.pynapli.server.subscriptions.StoredEntitlementRecord
import com.pynapli.server.subscriptions.SubscriptionRecord
import com.pynapli.server.subscriptions.SubscriptionRepository
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.shared.network.dto.SubscriptionTier
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TopPicksServiceTest {
    private val now = Instant.parse("2026-05-17T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun freeTierGetsOnePickPerDay() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000a01")
        val candidates = (1..3).map { swipeCardRecord(uuid(it + 10), "Pick$it") }
        val service = topPicksService(viewer, tier = SubscriptionTier.FREE, candidates = candidates)

        val response = service.getCurrentPicks(viewer)

        assertEquals(1, response.tierLimit)
        assertEquals(1, response.picks.size)
        assertEquals(candidates.first().userId.toString(), response.picks.single().card.userId)
    }

    @Test
    fun paidTierGetsUpToTenPicksPerDay() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000a02")
        val candidates = (1..15).map { swipeCardRecord(uuid(it + 20), "Pick$it") }
        val service = topPicksService(viewer, tier = SubscriptionTier.SELECT, candidates = candidates)

        val response = service.getCurrentPicks(viewer)

        assertEquals(10, response.tierLimit)
        assertEquals(10, response.picks.size)
        assertEquals(
            candidates.take(10).map { it.userId.toString() },
            response.picks.map { it.card.userId }
        )
    }

    @Test
    fun repeatReadsWithin24hReturnSameSession() = runBlocking {
        // Spec L308: "Picks refresh every 24 hours" — stable within the window.
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000a03")
        val candidates = listOf(swipeCardRecord(uuid(30), "Maya"))
        val swipeRepo = StubSwipeRepository(seededCards = candidates)
        val topPicksRepo = InMemoryTopPicksRepository()
        val service = TopPicksService(
            topPicksRepository = topPicksRepo,
            swipeRepository = swipeRepo,
            subscriptionService = paidSubscriptionService(viewer, SubscriptionTier.SOCIAL),
            clock = clock
        )

        val first = service.getCurrentPicks(viewer)
        val second = service.getCurrentPicks(viewer)

        assertEquals(first.sessionId, second.sessionId)
        assertEquals(1, topPicksRepo.sessionCount(viewer))
    }

    @Test
    fun expiredSessionTriggersFreshGeneration() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000a04")
        val candidates = listOf(swipeCardRecord(uuid(40), "Maya"))
        val swipeRepo = StubSwipeRepository(seededCards = candidates)
        val topPicksRepo = InMemoryTopPicksRepository()
        val clockAt = Clock.fixed(now, ZoneOffset.UTC)
        val service = TopPicksService(
            topPicksRepository = topPicksRepo,
            swipeRepository = swipeRepo,
            subscriptionService = paidSubscriptionService(viewer, SubscriptionTier.SELECT),
            clock = clockAt
        )
        val first = service.getCurrentPicks(viewer)

        // Roll the clock past expiry, build a service against the same repos.
        val later = Clock.fixed(now.plus(Duration.ofHours(25)), ZoneOffset.UTC)
        val laterService = TopPicksService(
            topPicksRepository = topPicksRepo,
            swipeRepository = swipeRepo,
            subscriptionService = paidSubscriptionService(viewer, SubscriptionTier.SELECT),
            clock = later
        )
        val second = laterService.getCurrentPicks(viewer)

        assertNotEquals(first.sessionId, second.sessionId)
        assertEquals(2, topPicksRepo.sessionCount(viewer))
    }

    @Test
    fun freshGenerationFailsWhenViewerHasNoLocation() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000a05")
        val service = TopPicksService(
            topPicksRepository = InMemoryTopPicksRepository(),
            swipeRepository = StubSwipeRepository(viewerHasLocation = false),
            subscriptionService = paidSubscriptionService(viewer, SubscriptionTier.SOCIAL),
            clock = clock
        )

        val error = assertFailsWith<TopPicksException> { service.getCurrentPicks(viewer) }
        assertEquals("LOCATION_REQUIRED", error.code)
    }

    @Test
    fun freshGenerationAppliesVerifiedPreferredRankingBias() = runBlocking {
        // Spec L311: picks are "verified, active, aligned intent, good standing".
        // We assert the verifiedPreferred ranking signal is passed through to the
        // swipe repo so the ORDER BY soft_score path engages.
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000a06")
        val swipeRepo = StubSwipeRepository(seededCards = listOf(swipeCardRecord(uuid(60), "Maya")))
        val service = TopPicksService(
            topPicksRepository = InMemoryTopPicksRepository(),
            swipeRepository = swipeRepo,
            subscriptionService = paidSubscriptionService(viewer, SubscriptionTier.SELECT),
            clock = clock
        )

        service.getCurrentPicks(viewer)

        assertTrue(swipeRepo.queryDeckFilters.first().verifiedPreferred)
    }

    private fun topPicksService(
        viewer: UUID,
        tier: String,
        candidates: List<SwipeCardRecord>
    ): TopPicksService {
        val subscriptionService = if (tier == SubscriptionTier.FREE) {
            SubscriptionService(TopPicksStubSubscriptionRepository(), clock = clock)
        } else {
            paidSubscriptionService(viewer, tier)
        }
        return TopPicksService(
            topPicksRepository = InMemoryTopPicksRepository(),
            swipeRepository = StubSwipeRepository(seededCards = candidates),
            subscriptionService = subscriptionService,
            clock = clock
        )
    }

    private fun paidSubscriptionService(userId: UUID, tier: String): SubscriptionService =
        SubscriptionService(
            TopPicksStubSubscriptionRepository(
                own = mapOf(
                    userId to SubscriptionRecord(
                        subscriptionId = UUID.randomUUID(),
                        userId = userId,
                        tier = tier,
                        status = "active",
                        currentPeriodStart = Instant.parse("2026-05-01T00:00:00Z"),
                        currentPeriodEnd = Instant.parse("2026-06-01T00:00:00Z")
                    )
                )
            ),
            clock = clock
        )

    private fun swipeCardRecord(userId: UUID, displayName: String): SwipeCardRecord =
        SwipeCardRecord(
            userId = userId,
            displayName = displayName,
            displayAge = 30,
            thumbnailMediaAssetId = null,
            showDistance = true,
            distanceMeters = 1_000.0
        )

    private fun uuid(value: Int): UUID =
        UUID.fromString("00000000-0000-0000-0000-${value.toString().padStart(12, '0')}")
}

private class StubSwipeRepository(
    private val viewerHasLocation: Boolean = true,
    private val seededCards: List<SwipeCardRecord> = emptyList()
) : SwipeRepository {
    val queryDeckFilters = mutableListOf<DiscoveryFilters>()
    private val deckSessions = mutableMapOf<UUID, SwipeDeckSessionRecord>()

    override suspend fun queryDeck(
        viewerUserId: UUID,
        radiusMeters: Int,
        limit: Int,
        filters: DiscoveryFilters,
        excludedTargetUserIds: Set<UUID>
    ): SwipeDeckResult {
        queryDeckFilters += filters
        return SwipeDeckResult(viewerHadLocation = viewerHasLocation, cards = seededCards.take(limit))
    }

    override suspend fun upsertDeckSession(
        session: SwipeDeckSessionRecord,
        cards: List<SwipeDeckSessionCardRecord>
    ) {
        deckSessions[session.sessionId] = session
    }

    override suspend fun findDeckSession(sessionId: UUID, viewerUserId: UUID): SwipeDeckSessionRecord? =
        deckSessions[sessionId]?.takeIf { it.viewerUserId == viewerUserId }

    // The rest of SwipeRepository is not exercised by TopPicksService — return safe defaults.
    override suspend fun listDeckSessionTargetIds(sessionId: UUID): Set<UUID> = emptySet()
    override suspend fun markDeckCardActionRecorded(
        sessionId: UUID,
        cardTokenHash: String,
        targetUserId: UUID,
        action: String,
        now: Instant
    ) = Unit
    override suspend fun isActionTargetEligible(
        viewerUserId: UUID,
        targetUserId: UUID,
        radiusMeters: Int,
        requireIntroNotesAllowed: Boolean
    ): Boolean = true

    override suspend fun isDeckCardActionable(
        sessionId: UUID,
        cardTokenHash: String,
        viewerUserId: UUID,
        targetUserId: UUID,
        now: Instant
    ): Boolean = true
    override suspend fun findSwipeActionById(actionId: UUID, actorUserId: UUID): SwipeActionRecord? = null
    override suspend fun findLatestRewindableAction(
        viewerUserId: UUID,
        radiusMeters: Int
    ): SwipeRewindCandidateRecord? = null
    override suspend fun recordSwipeAction(
        actionId: UUID,
        actorUserId: UUID,
        targetUserId: UUID,
        action: String,
        noteText: String?,
        consumedInventoryId: UUID?,
        travelLocationId: UUID?,
        matchIdFactory: () -> UUID
    ): SwipeActionResult = SwipeActionResult(recorded = false, match = null)
    override suspend fun rewindSwipeAction(
        actionId: UUID,
        actorUserId: UUID,
        targetUserId: UUID,
        session: SwipeDeckSessionRecord,
        card: SwipeDeckSessionCardRecord,
        rewoundAt: Instant
    ): Boolean = false
    override suspend fun listMatches(viewerUserId: UUID, limit: Int): List<MatchSummaryRecord> = emptyList()
}

private class InMemoryTopPicksRepository : TopPicksRepository {
    private val sessions = mutableMapOf<UUID, MutableList<TopPickSessionWithCards>>()

    override suspend fun findCurrentSession(userId: UUID, now: Instant): TopPickSessionWithCards? =
        sessions[userId].orEmpty()
            .filter { it.session.expiresAt.isAfter(now) }
            .maxByOrNull { it.session.generatedAt }

    override suspend fun createSession(
        sessionId: UUID,
        userId: UUID,
        deckSessionId: UUID,
        generatedAt: Instant,
        expiresAt: Instant,
        cards: List<TopPickCardInsert>
    ): TopPickSessionWithCards {
        val record = TopPickSessionWithCards(
            session = TopPickSessionRecord(sessionId, userId, deckSessionId, generatedAt, expiresAt),
            cards = cards.map {
                TopPickCardRecord(it.cardId, sessionId, it.targetUserId, it.position, it.cardTokenHash, it.dealtAt)
            }
        )
        sessions.getOrPut(userId) { mutableListOf() } += record
        return record
    }

    fun sessionCount(userId: UUID): Int = sessions[userId]?.size ?: 0
}

private class TopPicksStubSubscriptionRepository(
    private val own: Map<UUID, SubscriptionRecord> = emptyMap()
) : SubscriptionRepository {
    override suspend fun findBestActiveOwnSubscription(userId: UUID, now: Instant): SubscriptionRecord? = own[userId]
    override suspend fun findBestActiveLinkedPartnerSubscription(
        userId: UUID,
        now: Instant
    ): LinkedPartnerSubscriptionRecord? = null
    override suspend fun findBestActiveStoredEntitlement(userId: UUID, now: Instant): StoredEntitlementRecord? = null
    override suspend fun consumeIntroNoteInventory(userId: UUID, now: Instant): Boolean = false
}
