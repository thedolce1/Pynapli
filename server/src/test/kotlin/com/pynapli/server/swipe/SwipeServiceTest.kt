package com.pynapli.server.swipe

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.shared.network.dto.SwipeAction
import com.pynapli.shared.network.dto.SwipeActionRequestDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.notifications.NotificationEventDraft
import com.pynapli.server.notifications.NotificationKind
import com.pynapli.server.notifications.NotificationOutboxRecord
import com.pynapli.server.notifications.NotificationPublisher
import com.pynapli.server.safety.BlockRecord
import com.pynapli.server.safety.BlockRepository
import com.pynapli.server.safety.BlockSummaryRecord
import com.pynapli.server.safety.RateLimitCounter
import com.pynapli.server.safety.RateLimitRepository
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.AccountRiskStateRecord
import com.pynapli.server.safety.RiskPolicyService
import com.pynapli.server.subscriptions.LinkedPartnerSubscriptionRecord
import com.pynapli.server.subscriptions.StoredEntitlementRecord
import com.pynapli.server.subscriptions.SubscriptionRecord
import com.pynapli.server.subscriptions.SubscriptionRepository
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.shared.network.dto.SubscriptionTier
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwipeServiceTest {
    @Test
    fun likeWithoutPriorIsRecordedButNotMatched() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val target = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val repository = InMemorySwipeRepository()
        val service = SwipeService(repository)

        val response = service.submitAction(
            viewer,
            SwipeActionRequestDto(targetUserId = target.toString(), action = SwipeAction.LIKE)
        )

        assertTrue(response.recorded)
        assertNull(response.match)
    }

    @Test
    fun mutualLikeReturnsMatch() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000010")
        val target = UUID.fromString("00000000-0000-0000-0000-000000000011")
        val repository = InMemorySwipeRepository()
        val notifications = RecordingNotificationPublisher()
        val service = SwipeService(repository, notificationPublisher = notifications)

        service.submitAction(
            target,
            SwipeActionRequestDto(targetUserId = viewer.toString(), action = SwipeAction.LIKE)
        )
        val response = service.submitAction(
            viewer,
            SwipeActionRequestDto(targetUserId = target.toString(), action = SwipeAction.LIKE)
        )

        assertTrue(response.recorded)
        val match = assertNotNull(response.match)
        assertEquals(target.toString(), match.matchedUserId)
        assertEquals(NotificationKind.MATCH, notifications.events.single().kind)
        assertEquals(target, notifications.events.single().recipientUserId)
    }

    @Test
    fun passDoesNotCreateMatch() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000020")
        val target = UUID.fromString("00000000-0000-0000-0000-000000000021")
        val repository = InMemorySwipeRepository()
        val service = SwipeService(repository)

        service.submitAction(
            target,
            SwipeActionRequestDto(targetUserId = viewer.toString(), action = SwipeAction.LIKE)
        )
        val response = service.submitAction(
            viewer,
            SwipeActionRequestDto(targetUserId = target.toString(), action = SwipeAction.PASS)
        )

        assertTrue(response.recorded)
        assertNull(response.match)
    }

    @Test
    fun selfTargetIsRejected() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000030")
        val service = SwipeService(InMemorySwipeRepository())

        val error = assertFailsWith<SwipeException> {
            service.submitAction(
                viewer,
                SwipeActionRequestDto(targetUserId = viewer.toString(), action = SwipeAction.LIKE)
            )
        }

        assertEquals("INVALID_SWIPE_TARGET", error.code)
    }

    @Test
    fun unknownActionIsRejected() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000040")
        val target = UUID.fromString("00000000-0000-0000-0000-000000000041")
        val service = SwipeService(InMemorySwipeRepository())

        val error = assertFailsWith<SwipeException> {
            service.submitAction(
                viewer,
                SwipeActionRequestDto(targetUserId = target.toString(), action = "super_like")
            )
        }

        assertEquals("INVALID_SWIPE_ACTION", error.code)
    }

    @Test
    fun ineligibleTargetIsRejectedBeforeRecording() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000045")
        val target = UUID.fromString("00000000-0000-0000-0000-000000000046")
        val repository = InMemorySwipeRepository(ineligibleTargets = setOf(viewer to target))
        val service = SwipeService(repository)

        val error = assertFailsWith<SwipeException> {
            service.submitAction(
                viewer,
                SwipeActionRequestDto(targetUserId = target.toString(), action = SwipeAction.LIKE)
            )
        }

        assertEquals("INVALID_SWIPE_TARGET", error.code)
        assertEquals(emptyMap(), repository.actionsSnapshot())
    }

    @Test
    fun rateLimitedActionIsRejectedBeforeRecording() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000043")
        val target = UUID.fromString("00000000-0000-0000-0000-000000000044")
        val repository = InMemorySwipeRepository()
        val service = SwipeService(
            repository = repository,
            rateLimitService = denyingRateLimitService()
        )

        val error = assertFailsWith<SwipeException> {
            service.submitAction(
                viewer,
                SwipeActionRequestDto(targetUserId = target.toString(), action = SwipeAction.LIKE)
            )
        }

        assertEquals("RATE_LIMITED", error.code)
        assertEquals(emptyMap(), repository.actionsSnapshot())
    }

    @Test
    fun riskRestrictedActionIsRejectedBeforeRecording() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000049")
        val target = UUID.fromString("00000000-0000-0000-0000-000000000149")
        val repository = InMemorySwipeRepository()
        val service = SwipeService(
            repository = repository,
            riskPolicyService = restrictedRiskPolicy()
        )

        val error = assertFailsWith<SwipeException> {
            service.submitAction(
                viewer,
                SwipeActionRequestDto(targetUserId = target.toString(), action = SwipeAction.LIKE)
            )
        }

        assertEquals("RISK_RESTRICTED", error.code)
        assertEquals(emptyMap(), repository.actionsSnapshot())
    }

    @Test
    fun introNoteRequiresPaidEntitlement() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000150")
        val target = UUID.fromString("00000000-0000-0000-0000-000000000151")
        val repository = InMemorySwipeRepository()
        val service = SwipeService(
            repository = repository,
            subscriptionService = freeSubscriptionService()
        )

        val error = assertFailsWith<SwipeException> {
            service.submitAction(
                viewer,
                SwipeActionRequestDto(
                    targetUserId = target.toString(),
                    action = SwipeAction.INTRO_NOTE,
                    noteText = "Hi, your vibe caught my eye."
                )
            )
        }

        assertEquals("ENTITLEMENT_REQUIRED", error.code)
        assertEquals(emptyMap(), repository.actionsSnapshot())
    }

    @Test
    fun introNoteConsumesInventoryAndRecordsAction() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000152")
        val target = UUID.fromString("00000000-0000-0000-0000-000000000153")
        val repository = InMemorySwipeRepository()
        val subscriptionRepository = StaticSubscriptionRepository.paid(viewer, SubscriptionTier.SELECT, introNotes = 1)
        val service = SwipeService(
            repository = repository,
            subscriptionService = SubscriptionService(subscriptionRepository)
        )

        val response = service.submitAction(
            viewer,
            SwipeActionRequestDto(
                targetUserId = target.toString(),
                action = SwipeAction.INTRO_NOTE,
                noteText = "Hi, your vibe caught my eye."
            )
        )

        assertTrue(response.recorded)
        assertEquals(SwipeAction.INTRO_NOTE, repository.actionsSnapshot().getValue(viewer to target))
        assertEquals(0, subscriptionRepository.introNotesRemaining(viewer))
    }

    @Test
    fun introNoteRequiresAvailableInventory() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000154")
        val target = UUID.fromString("00000000-0000-0000-0000-000000000155")
        val repository = InMemorySwipeRepository()
        val service = SwipeService(
            repository = repository,
            subscriptionService = paidSubscriptionService(viewer, SubscriptionTier.SELECT, introNotes = 0)
        )

        val error = assertFailsWith<SwipeException> {
            service.submitAction(
                viewer,
                SwipeActionRequestDto(
                    targetUserId = target.toString(),
                    action = SwipeAction.INTRO_NOTE,
                    noteText = "Hi, your vibe caught my eye."
                )
            )
        }

        assertEquals("INTRO_NOTE_INVENTORY_REQUIRED", error.code)
        assertEquals(emptyMap(), repository.actionsSnapshot())
    }

    @Test
    fun deckQueryRejectsAdvancedFiltersForFreeUsers() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000156")
        val service = SwipeService(
            repository = InMemorySwipeRepository(),
            subscriptionService = freeSubscriptionService()
        )

        val error = assertFailsWith<SwipeException> {
            service.queryDeck(viewer, SwipeDeckRequestDto(minimumAge = 35))
        }

        assertEquals("ENTITLEMENT_REQUIRED", error.code)
    }

    @Test
    fun deckQueryAllowsAdvancedFiltersForPaidUsers() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000157")
        val target = UUID.fromString("00000000-0000-0000-0000-000000000158")
        val service = SwipeService(
            repository = InMemorySwipeRepository(
                seededCards = listOf(
                    SwipeCardRecord(
                        userId = target,
                        displayName = "Maya",
                        displayAge = 37,
                        thumbnailMediaAssetId = null,
                        showDistance = true,
                        distanceMeters = 850.0
                    )
                )
            ),
            subscriptionService = paidSubscriptionService(viewer, SubscriptionTier.SOCIAL, introNotes = 0)
        )

        val deck = service.queryDeck(viewer, SwipeDeckRequestDto(minimumAge = 35))

        assertEquals(listOf(target.toString()), deck.items.map { it.userId })
        assertEquals(false, deck.adSupported)
        assertEquals(true, deck.advancedFiltersApplied)
    }

    @Test
    fun repeatedDecisionIsRejectedWithoutChangingOriginalAction() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000047")
        val target = UUID.fromString("00000000-0000-0000-0000-000000000048")
        val repository = InMemorySwipeRepository()
        val service = SwipeService(repository)
        service.submitAction(
            viewer,
            SwipeActionRequestDto(targetUserId = target.toString(), action = SwipeAction.PASS)
        )

        val error = assertFailsWith<SwipeException> {
            service.submitAction(
                viewer,
                SwipeActionRequestDto(targetUserId = target.toString(), action = SwipeAction.LIKE)
            )
        }

        assertEquals("INVALID_SWIPE_TARGET", error.code)
        assertEquals(SwipeAction.PASS, repository.actionsSnapshot().getValue(viewer to target))
    }

    @Test
    fun deckQueryWithoutLocationFails() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000050")
        val service = SwipeService(InMemorySwipeRepository(viewerHasLocation = false))

        val error = assertFailsWith<SwipeException> {
            service.queryDeck(viewer, SwipeDeckRequestDto())
        }

        assertEquals("LOCATION_REQUIRED", error.code)
    }

    @Test
    fun deckQueryReturnsCardsAtCoercedLimit() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000060")
        val target = UUID.fromString("00000000-0000-0000-0000-000000000061")
        val repository = InMemorySwipeRepository(
            seededCards = listOf(
                SwipeCardRecord(
                    userId = target,
                    displayName = "Maya",
                    displayAge = 31,
                    thumbnailMediaAssetId = null,
                    showDistance = true,
                    distanceMeters = 850.0
                )
            )
        )
        val service = SwipeService(repository)

        val deck = service.queryDeck(viewer, SwipeDeckRequestDto(limit = 5))

        assertEquals(1, deck.items.size)
        assertEquals(target.toString(), deck.items.single().userId)
        assertEquals("800 m", deck.items.single().distanceLabel)
        assertEquals(800, deck.items.single().distanceMeters)
    }

    @Test
    fun deckQueryIncludesLinkedPartnerMiniWhenProjected() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000062")
        val target = UUID.fromString("00000000-0000-0000-0000-000000000063")
        val partner = UUID.fromString("00000000-0000-0000-0000-000000000064")
        val partnerMediaAssetId = UUID.fromString("00000000-0000-0000-0000-000000000164")
        val service = SwipeService(
            repository = InMemorySwipeRepository(
                seededCards = listOf(
                    SwipeCardRecord(
                        userId = target,
                        displayName = "Maya",
                        displayAge = 31,
                        thumbnailMediaAssetId = null,
                        showDistance = true,
                        distanceMeters = 850.0,
                        linkedPartnerUserId = partner,
                        linkedPartnerDisplayName = "Noa",
                        linkedPartnerThumbnailMediaAssetId = partnerMediaAssetId
                    )
                )
            ),
            mediaAssetUrlResolver = StaticMediaAssetUrlResolver()
        )

        val deck = service.queryDeck(viewer, SwipeDeckRequestDto(limit = 5))

        val linkedPartner = deck.items.single().linkedPartner
        assertEquals(partner.toString(), linkedPartner?.userId)
        assertEquals("Noa", linkedPartner?.displayName)
        assertEquals("https://media.example/$partnerMediaAssetId", linkedPartner?.thumbnailUrl)
    }

    @Test
    fun listMatchesFiltersBlockedRelatedUsers() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000070")
        val blockedMatch = UUID.fromString("00000000-0000-0000-0000-000000000071")
        val visibleMatch = UUID.fromString("00000000-0000-0000-0000-000000000072")
        val service = SwipeService(
            repository = InMemorySwipeRepository(
                seededMatches = listOf(
                    MatchRecord(
                        matchId = UUID.fromString("00000000-0000-0000-0000-000000000171"),
                        userA = viewer,
                        userB = blockedMatch,
                        createdAt = Instant.parse("2026-04-28T18:00:00Z")
                    ),
                    MatchRecord(
                        matchId = UUID.fromString("00000000-0000-0000-0000-000000000172"),
                        userA = viewer,
                        userB = visibleMatch,
                        createdAt = Instant.parse("2026-04-28T18:01:00Z")
                    )
                )
            ),
            blockRepository = StaticBlockRepository(
                relatedByUser = mapOf(viewer to setOf(blockedMatch))
            )
        )

        val response = service.listMatches(viewer)

        assertEquals(1, response.matches.size)
        assertEquals(visibleMatch.toString(), response.matches.single().matchedUserId)
    }

    @Test
    fun listMatchesSuppressesRiskRestrictedMatches() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000080")
        val quarantinedMatch = UUID.fromString("00000000-0000-0000-0000-000000000081")
        val service = SwipeService(
            repository = InMemorySwipeRepository(
                seededMatches = listOf(
                    MatchRecord(
                        matchId = UUID.fromString("00000000-0000-0000-0000-000000000181"),
                        userA = viewer,
                        userB = quarantinedMatch,
                        createdAt = Instant.parse("2026-04-28T18:00:00Z")
                    )
                )
            ),
            riskPolicyService = restrictedRiskPolicy()
        )

        val response = service.listMatches(viewer)

        assertEquals(emptyList(), response.matches)
    }
}

private class InMemorySwipeRepository(
    private val viewerHasLocation: Boolean = true,
    private val seededCards: List<SwipeCardRecord> = emptyList(),
    private val ineligibleTargets: Set<Pair<UUID, UUID>> = emptySet(),
    seededMatches: List<MatchRecord> = emptyList()
) : SwipeRepository {
    private val actions = mutableMapOf<Pair<UUID, UUID>, String>()
    private val matches = seededMatches.toMutableList()
    private val now = Instant.parse("2026-04-28T18:00:00Z")

    override suspend fun queryDeck(
        viewerUserId: UUID,
        radiusMeters: Int,
        limit: Int,
        filters: DiscoveryFilters
    ): SwipeDeckResult {
        if (!viewerHasLocation) return SwipeDeckResult(viewerHadLocation = false, cards = emptyList())
        return SwipeDeckResult(viewerHadLocation = true, cards = seededCards.take(limit))
    }

    override suspend fun isActionTargetEligible(
        viewerUserId: UUID,
        targetUserId: UUID,
        radiusMeters: Int,
        requireIntroNotesAllowed: Boolean
    ): Boolean =
        viewerUserId to targetUserId !in ineligibleTargets &&
            viewerUserId to targetUserId !in actions

    override suspend fun recordSwipeAction(
        actorUserId: UUID,
        targetUserId: UUID,
        action: String,
        noteText: String?,
        actionIdFactory: () -> UUID,
        matchIdFactory: () -> UUID
    ): SwipeActionResult {
        if (actorUserId to targetUserId in actions) {
            return SwipeActionResult(recorded = false, match = null)
        }
        actions[actorUserId to targetUserId] = action
        if (action != "like") return SwipeActionResult(recorded = true, match = null)
        val theyLikedMe = actions[targetUserId to actorUserId] == "like"
        if (!theyLikedMe) return SwipeActionResult(recorded = true, match = null)

        val (a, b) = canonicalPair(actorUserId, targetUserId)
        val existing = matches.firstOrNull { it.userA == a && it.userB == b }
        val match = existing ?: MatchRecord(
            matchId = matchIdFactory(),
            userA = a,
            userB = b,
            createdAt = now
        ).also { matches.add(it) }
        return SwipeActionResult(recorded = true, match = match)
    }

    override suspend fun listMatches(viewerUserId: UUID, limit: Int): List<MatchSummaryRecord> =
        matches.filter { viewerUserId == it.userA || viewerUserId == it.userB }.map { match ->
            MatchSummaryRecord(
                matchId = match.matchId,
                matchedUserId = if (match.userA == viewerUserId) match.userB else match.userA,
                matchedDisplayName = null,
                matchedThumbnailMediaAssetId = null,
                createdAt = match.createdAt
            )
        }

    fun actionsSnapshot(): Map<Pair<UUID, UUID>, String> = actions.toMap()
}

private class StaticBlockRepository(
    private val relatedByUser: Map<UUID, Set<UUID>>
) : BlockRepository {
    override suspend fun block(
        blockerUserId: UUID,
        blockedUserId: UUID,
        reason: String?,
        blockIdFactory: () -> UUID
    ): BlockRecord = throw UnsupportedOperationException("not used")

    override suspend fun unblock(blockerUserId: UUID, blockedUserId: UUID): Boolean = false

    override suspend fun listBlocks(blockerUserId: UUID): List<BlockSummaryRecord> = emptyList()

    override suspend fun isBlockedEitherWay(userIdA: UUID, userIdB: UUID): Boolean =
        relatedByUser[userIdA]?.contains(userIdB) == true ||
            relatedByUser[userIdB]?.contains(userIdA) == true

    override suspend fun listBlockedRelatedUserIds(userId: UUID): Set<UUID> =
        relatedByUser[userId].orEmpty()
}

private fun denyingRateLimitService(): RateLimitService =
    RateLimitService(
        object : RateLimitRepository {
            override suspend fun increment(key: String, windowSeconds: Long): RateLimitCounter =
                RateLimitCounter(count = Long.MAX_VALUE, ttlSeconds = windowSeconds)
        }
    )

private fun restrictedRiskPolicy(tier: String = "quarantine"): RiskPolicyService =
    RiskPolicyService { userId ->
        AccountRiskStateRecord(
            userId = userId,
            score = 70,
            tier = tier,
            lastEventAt = Instant.parse("2026-04-28T18:00:00Z"),
            lastReviewedAt = null,
            updatedAt = Instant.parse("2026-04-28T18:00:00Z")
        )
    }

private class StaticMediaAssetUrlResolver : MediaAssetUrlResolver {
    override fun publicContentUrl(mediaAssetId: UUID): String = "https://media.example/$mediaAssetId"
}

private class RecordingNotificationPublisher : NotificationPublisher {
    val events = mutableListOf<NotificationEventDraft>()

    override suspend fun enqueue(notification: NotificationEventDraft): NotificationOutboxRecord? {
        events += notification
        return null
    }
}

private fun freeSubscriptionService(): SubscriptionService =
    SubscriptionService(StaticSubscriptionRepository())

private fun paidSubscriptionService(userId: UUID, tier: String, introNotes: Int): SubscriptionService =
    SubscriptionService(StaticSubscriptionRepository.paid(userId, tier, introNotes))

private class StaticSubscriptionRepository(
    private val own: Map<UUID, SubscriptionRecord> = emptyMap(),
    introNotes: Map<UUID, Int> = emptyMap()
) : SubscriptionRepository {
    private val introNoteInventory = introNotes.toMutableMap()

    override suspend fun findBestActiveOwnSubscription(userId: UUID, now: Instant): SubscriptionRecord? = own[userId]

    override suspend fun findBestActiveLinkedPartnerSubscription(
        userId: UUID,
        now: Instant
    ): LinkedPartnerSubscriptionRecord? = null

    override suspend fun findBestActiveStoredEntitlement(userId: UUID, now: Instant): StoredEntitlementRecord? = null

    override suspend fun consumeIntroNoteInventory(userId: UUID, now: Instant): Boolean {
        val available = introNoteInventory[userId] ?: 0
        if (available <= 0) return false
        introNoteInventory[userId] = available - 1
        return true
    }

    fun introNotesRemaining(userId: UUID): Int = introNoteInventory[userId] ?: 0

    companion object {
        fun paid(userId: UUID, tier: String, introNotes: Int): StaticSubscriptionRepository =
            StaticSubscriptionRepository(
                own = mapOf(
                    userId to SubscriptionRecord(
                        subscriptionId = UUID.randomUUID(),
                        userId = userId,
                        tier = tier,
                        status = "active",
                        currentPeriodStart = Instant.parse("2026-05-01T00:00:00Z"),
                        currentPeriodEnd = Instant.parse("2026-06-01T00:00:00Z")
                    )
                ),
                introNotes = mapOf(userId to introNotes)
            )
    }
}
