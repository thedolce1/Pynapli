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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
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
            swipeActionRequest(viewer, target, SwipeAction.LIKE)
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
            swipeActionRequest(target, viewer, SwipeAction.LIKE)
        )
        val response = service.submitAction(
            viewer,
            swipeActionRequest(viewer, target, SwipeAction.LIKE)
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
            swipeActionRequest(target, viewer, SwipeAction.LIKE)
        )
        val response = service.submitAction(
            viewer,
            swipeActionRequest(viewer, target, SwipeAction.PASS)
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
                swipeActionRequest(viewer, viewer, SwipeAction.LIKE)
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
                swipeActionRequest(viewer, target, "super_like")
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
                swipeActionRequest(viewer, target, SwipeAction.LIKE)
            )
        }

        assertEquals("INVALID_SWIPE_TARGET", error.code)
        assertEquals(emptyMap(), repository.actionsSnapshot())
    }

    @Test
    fun actionWithoutDeckSessionIsRejectedBeforeRecording() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000245")
        val target = UUID.fromString("00000000-0000-0000-0000-000000000246")
        val repository = InMemorySwipeRepository()
        val service = SwipeService(repository)

        val error = assertFailsWith<SwipeException> {
            service.submitAction(
                viewer,
                SwipeActionRequestDto(
                    actionId = UUID.randomUUID().toString(),
                    targetUserId = target.toString(),
                    action = SwipeAction.LIKE
                )
            )
        }

        assertEquals("INVALID_SWIPE_DECK_SESSION", error.code)
        assertEquals(emptyMap(), repository.actionsSnapshot())
    }

    @Test
    fun actionWithMismatchedCardTokenIsRejectedBeforeRecording() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000247")
        val target = UUID.fromString("00000000-0000-0000-0000-000000000248")
        val otherTarget = UUID.fromString("00000000-0000-0000-0000-000000000249")
        val repository = InMemorySwipeRepository()
        val service = SwipeService(repository)

        val error = assertFailsWith<SwipeException> {
            service.submitAction(
                viewer,
                swipeActionRequest(viewer, otherTarget, SwipeAction.LIKE).copy(
                    targetUserId = target.toString()
                )
            )
        }

        assertEquals("INVALID_SWIPE_CARD_TOKEN", error.code)
        assertEquals(emptyMap(), repository.actionsSnapshot())
    }

    @Test
    fun expiredDeckSessionIsRejectedBeforeRecording() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000250")
        val target = UUID.fromString("00000000-0000-0000-0000-000000000251")
        val repository = InMemorySwipeRepository()
        val issuedAt = Instant.parse("2026-04-28T18:00:00Z")
        val codec = SwipeDeckTokenCodec()
        val service = SwipeService(
            repository = repository,
            deckTokenCodec = codec,
            clock = Clock.fixed(issuedAt.plus(Duration.ofMinutes(31)), ZoneOffset.UTC)
        )

        val error = assertFailsWith<SwipeException> {
            service.submitAction(
                viewer,
                swipeActionRequest(viewer, target, SwipeAction.LIKE, issuedAt = issuedAt, deckTokenCodec = codec)
            )
        }

        assertEquals("STALE_SWIPE_DECK_SESSION", error.code)
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
                swipeActionRequest(viewer, target, SwipeAction.LIKE)
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
                swipeActionRequest(viewer, target, SwipeAction.LIKE)
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
                swipeActionRequest(
                    viewerUserId = viewer,
                    targetUserId = target,
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
            swipeActionRequest(
                viewerUserId = viewer,
                targetUserId = target,
                action = SwipeAction.INTRO_NOTE,
                noteText = "Hi, your vibe caught my eye."
            )
        )

        assertTrue(response.recorded)
        assertEquals(SwipeAction.INTRO_NOTE, repository.actionsSnapshot().getValue(viewer to target))
        assertEquals(0, subscriptionRepository.introNotesRemaining(viewer))
    }

    @Test
    fun idempotentIntroNoteRetryDoesNotConsumeInventoryAgain() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000252")
        val target = UUID.fromString("00000000-0000-0000-0000-000000000253")
        val actionId = UUID.fromString("00000000-0000-0000-0000-000000000254")
        val repository = InMemorySwipeRepository()
        val subscriptionRepository = StaticSubscriptionRepository.paid(viewer, SubscriptionTier.SELECT, introNotes = 1)
        val service = SwipeService(
            repository = repository,
            subscriptionService = SubscriptionService(subscriptionRepository)
        )
        val request = swipeActionRequest(
            viewerUserId = viewer,
            targetUserId = target,
            action = SwipeAction.INTRO_NOTE,
            noteText = "Hi, your vibe caught my eye.",
            actionId = actionId
        )

        val first = service.submitAction(viewer, request)
        val retry = service.submitAction(viewer, request)

        assertTrue(first.recorded)
        assertTrue(retry.recorded)
        assertEquals(SwipeAction.INTRO_NOTE, repository.actionsSnapshot().getValue(viewer to target))
        assertEquals(0, subscriptionRepository.introNotesRemaining(viewer))
    }

    @Test
    fun reusedActionIdWithDifferentPayloadIsRejectedBeforeSideEffects() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000255")
        val firstTarget = UUID.fromString("00000000-0000-0000-0000-000000000256")
        val secondTarget = UUID.fromString("00000000-0000-0000-0000-000000000257")
        val actionId = UUID.fromString("00000000-0000-0000-0000-000000000258")
        val repository = InMemorySwipeRepository()
        val service = SwipeService(repository)

        service.submitAction(viewer, swipeActionRequest(viewer, firstTarget, SwipeAction.LIKE, actionId = actionId))
        val error = assertFailsWith<SwipeException> {
            service.submitAction(viewer, swipeActionRequest(viewer, secondTarget, SwipeAction.LIKE, actionId = actionId))
        }

        assertEquals("INVALID_SWIPE_ACTION_ID", error.code)
        assertEquals(mapOf(viewer to firstTarget to SwipeAction.LIKE), repository.actionsSnapshot())
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
                swipeActionRequest(
                    viewerUserId = viewer,
                    targetUserId = target,
                    action = SwipeAction.INTRO_NOTE,
                    noteText = "Hi, your vibe caught my eye."
                )
            )
        }

        assertEquals("INTRO_NOTE_INVENTORY_REQUIRED", error.code)
        assertEquals(emptyMap(), repository.actionsSnapshot())
    }

    @Test
    fun pineappleLikeConsumesInventoryAndCanCreateMatch() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000156")
        val target = UUID.fromString("00000000-0000-0000-0000-000000000157")
        val repository = InMemorySwipeRepository()
        val subscriptionRepository = StaticSubscriptionRepository.paid(
            userId = viewer,
            tier = SubscriptionTier.SOCIAL,
            introNotes = 0,
            pineappleLikes = 1
        )
        val service = SwipeService(
            repository = repository,
            subscriptionService = SubscriptionService(subscriptionRepository)
        )

        service.submitAction(
            target,
            swipeActionRequest(target, viewer, SwipeAction.LIKE)
        )
        val response = service.submitAction(
            viewer,
            swipeActionRequest(viewer, target, SwipeAction.PINEAPPLE_LIKE)
        )

        assertTrue(response.recorded)
        assertNotNull(response.match)
        assertEquals(SwipeAction.PINEAPPLE_LIKE, repository.actionsSnapshot().getValue(viewer to target))
        assertEquals(0, subscriptionRepository.pineappleLikesRemaining(viewer))
    }

    @Test
    fun pineappleLikeRequiresAvailableInventory() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000158")
        val target = UUID.fromString("00000000-0000-0000-0000-000000000159")
        val repository = InMemorySwipeRepository()
        val service = SwipeService(
            repository = repository,
            subscriptionService = paidSubscriptionService(
                userId = viewer,
                tier = SubscriptionTier.SELECT,
                introNotes = 0,
                pineappleLikes = 0
            )
        )

        val error = assertFailsWith<SwipeException> {
            service.submitAction(
                viewer,
                swipeActionRequest(viewer, target, SwipeAction.PINEAPPLE_LIKE)
            )
        }

        assertEquals("PINEAPPLE_LIKE_INVENTORY_REQUIRED", error.code)
        assertEquals(emptyMap(), repository.actionsSnapshot())
    }

    @Test
    fun deckQueryRejectsAdvancedFiltersForFreeUsers() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000160")
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
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000161")
        val target = UUID.fromString("00000000-0000-0000-0000-000000000162")
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
            swipeActionRequest(viewer, target, SwipeAction.PASS)
        )

        val error = assertFailsWith<SwipeException> {
            service.submitAction(
                viewer,
                swipeActionRequest(viewer, target, SwipeAction.LIKE)
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
    fun deckQueryTokensAuthorizeReturnedCardAction() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000260")
        val target = UUID.fromString("00000000-0000-0000-0000-000000000261")
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
        val card = deck.items.single()
        val response = service.submitAction(
            viewer,
            SwipeActionRequestDto(
                actionId = UUID.randomUUID().toString(),
                deckSessionId = deck.deckSessionId,
                cardToken = card.rankToken,
                targetUserId = target.toString(),
                action = SwipeAction.LIKE
            )
        )

        assertTrue(deck.deckSessionId.isNotBlank())
        assertTrue(deck.generatedAt?.isNotBlank() == true)
        assertTrue(card.rankToken.isNotBlank())
        assertTrue(response.recorded)
        assertEquals(SwipeAction.LIKE, repository.actionsSnapshot().getValue(viewer to target))
    }

    @Test
    fun deckQueryCursorContinuesPersistedSessionWithoutDuplicateCards() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000265")
        val targets = listOf(
            UUID.fromString("00000000-0000-0000-0000-000000000266"),
            UUID.fromString("00000000-0000-0000-0000-000000000267"),
            UUID.fromString("00000000-0000-0000-0000-000000000268")
        )
        val repository = InMemorySwipeRepository(
            seededCards = targets.mapIndexed { index, target ->
                SwipeCardRecord(
                    userId = target,
                    displayName = "Card ${index + 1}",
                    displayAge = 31,
                    thumbnailMediaAssetId = null,
                    showDistance = true,
                    distanceMeters = 800.0 + index
                )
            }
        )
        val service = SwipeService(repository)

        val firstPage = service.queryDeck(viewer, SwipeDeckRequestDto(limit = 2))
        val secondPage = service.queryDeck(viewer, SwipeDeckRequestDto(limit = 2, cursor = firstPage.nextCursor))

        assertEquals(targets.take(2).map { it.toString() }, firstPage.items.map { it.userId })
        assertNotNull(firstPage.nextCursor)
        assertEquals(listOf(targets.last().toString()), secondPage.items.map { it.userId })
        assertNull(secondPage.nextCursor)
        assertEquals(firstPage.deckSessionId, secondPage.deckSessionId)
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
    fun rewindRequiresPaidEntitlement() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000273")
        val target = UUID.fromString("00000000-0000-0000-0000-000000000274")
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
        val service = SwipeService(
            repository = repository,
            subscriptionService = freeSubscriptionService()
        )
        val deck = service.queryDeck(viewer, SwipeDeckRequestDto(limit = 5))
        service.submitAction(
            viewer,
            SwipeActionRequestDto(
                actionId = UUID.randomUUID().toString(),
                deckSessionId = deck.deckSessionId,
                cardToken = deck.items.single().rankToken,
                targetUserId = target.toString(),
                action = SwipeAction.PASS
            )
        )

        val error = assertFailsWith<SwipeException> {
            service.rewind(viewer)
        }

        assertEquals("ENTITLEMENT_REQUIRED", error.code)
        assertEquals(SwipeAction.PASS, repository.actionsSnapshot().getValue(viewer to target))
    }

    @Test
    fun paidRewindRestoresLatestEligibleCardWithFreshToken() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000275")
        val target = UUID.fromString("00000000-0000-0000-0000-000000000276")
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
        val service = SwipeService(
            repository = repository,
            subscriptionService = paidSubscriptionService(viewer, SubscriptionTier.SOCIAL, introNotes = 0)
        )
        val deck = service.queryDeck(viewer, SwipeDeckRequestDto(limit = 5))
        service.submitAction(
            viewer,
            SwipeActionRequestDto(
                actionId = UUID.randomUUID().toString(),
                deckSessionId = deck.deckSessionId,
                cardToken = deck.items.single().rankToken,
                targetUserId = target.toString(),
                action = SwipeAction.PASS
            )
        )

        val rewind = service.rewind(viewer)
        val restoredCard = assertNotNull(rewind.card)
        val replay = service.submitAction(
            viewer,
            SwipeActionRequestDto(
                actionId = UUID.randomUUID().toString(),
                deckSessionId = rewind.deckSessionId,
                cardToken = restoredCard.rankToken,
                targetUserId = target.toString(),
                action = SwipeAction.LIKE
            )
        )

        assertTrue(rewind.rewound)
        assertEquals(target.toString(), restoredCard.userId)
        assertTrue(rewind.deckSessionId.isNotBlank())
        assertTrue(restoredCard.rankToken.isNotBlank())
        assertTrue(replay.recorded)
        assertEquals(SwipeAction.LIKE, repository.actionsSnapshot().getValue(viewer to target))
    }

    @Test
    fun rewoundActionIdRetryDoesNotReapplyDecision() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000277")
        val target = UUID.fromString("00000000-0000-0000-0000-000000000278")
        val actionId = UUID.fromString("00000000-0000-0000-0000-000000000279")
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
        val service = SwipeService(
            repository = repository,
            subscriptionService = paidSubscriptionService(viewer, SubscriptionTier.SELECT, introNotes = 0)
        )
        val deck = service.queryDeck(viewer, SwipeDeckRequestDto(limit = 5))
        val request = SwipeActionRequestDto(
            actionId = actionId.toString(),
            deckSessionId = deck.deckSessionId,
            cardToken = deck.items.single().rankToken,
            targetUserId = target.toString(),
            action = SwipeAction.PASS
        )
        service.submitAction(viewer, request)
        service.rewind(viewer)

        val retry = service.submitAction(viewer, request)

        assertEquals(false, retry.recorded)
        assertEquals(emptyMap(), repository.actionsSnapshot())
    }

    @Test
    fun matchedActionCannotBeRewound() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000280")
        val target = UUID.fromString("00000000-0000-0000-0000-000000000281")
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
        val service = SwipeService(
            repository = repository,
            subscriptionService = paidSubscriptionService(viewer, SubscriptionTier.SOCIAL, introNotes = 0)
        )

        service.submitAction(target, swipeActionRequest(target, viewer, SwipeAction.LIKE))
        val deck = service.queryDeck(viewer, SwipeDeckRequestDto(limit = 5))
        service.submitAction(
            viewer,
            SwipeActionRequestDto(
                actionId = UUID.randomUUID().toString(),
                deckSessionId = deck.deckSessionId,
                cardToken = deck.items.single().rankToken,
                targetUserId = target.toString(),
                action = SwipeAction.LIKE
            )
        )

        val rewind = service.rewind(viewer)

        assertEquals(false, rewind.rewound)
        assertEquals(SwipeAction.LIKE, repository.actionsSnapshot().getValue(viewer to target))
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

private fun swipeActionRequest(
    viewerUserId: UUID,
    targetUserId: UUID,
    action: String,
    noteText: String? = null,
    actionId: UUID = UUID.randomUUID(),
    issuedAt: Instant = Instant.now(),
    deckTokenCodec: SwipeDeckTokenCodec = SwipeDeckTokenCodec()
): SwipeActionRequestDto {
    val scopeHash = SwipeDeckTokenCodec.deckScopeHash(
        viewerUserId = viewerUserId,
        radiusMeters = 100_000,
        limit = 60,
        filters = DiscoveryFilters()
    )
    val deckSession = deckTokenCodec.issueDeckSession(
        viewerUserId = viewerUserId,
        scopeHash = scopeHash,
        now = issuedAt
    )
    return SwipeActionRequestDto(
        actionId = actionId.toString(),
        deckSessionId = deckSession.deckSessionId,
        cardToken = deckTokenCodec.issueCardToken(
            deckSessionId = deckSession.deckSessionId,
            viewerUserId = viewerUserId,
            targetUserId = targetUserId,
            scopeHash = scopeHash,
            generatedAt = deckSession.generatedAt,
            expiresAt = deckSession.expiresAt,
            position = 0
        ),
        targetUserId = targetUserId.toString(),
        action = action,
        noteText = noteText
    )
}

private class InMemorySwipeRepository(
    private val viewerHasLocation: Boolean = true,
    private val seededCards: List<SwipeCardRecord> = emptyList(),
    private val ineligibleTargets: Set<Pair<UUID, UUID>> = emptySet(),
    seededMatches: List<MatchRecord> = emptyList()
) : SwipeRepository {
    private val actions = mutableMapOf<Pair<UUID, UUID>, String>()
    private val actionRecords = mutableMapOf<UUID, SwipeActionRecord>()
    private val actionOrder = mutableListOf<UUID>()
    private val matches = seededMatches.toMutableList()
    private val now = Instant.parse("2026-04-28T18:00:00Z")

    override suspend fun queryDeck(
        viewerUserId: UUID,
        radiusMeters: Int,
        limit: Int,
        filters: DiscoveryFilters,
        excludedTargetUserIds: Set<UUID>
    ): SwipeDeckResult {
        if (!viewerHasLocation) return SwipeDeckResult(viewerHadLocation = false, cards = emptyList())
        return SwipeDeckResult(
            viewerHadLocation = true,
            cards = seededCards
                .filterNot { it.userId in excludedTargetUserIds }
                .filterNot { viewerUserId to it.userId in actions }
                .take(limit)
        )
    }

    private val deckSessions = mutableMapOf<UUID, SwipeDeckSessionRecord>()
    private val deckCards = mutableListOf<SwipeDeckSessionCardRecord>()
    private val actionedDeckCards = mutableSetOf<Pair<UUID, String>>()

    override suspend fun upsertDeckSession(
        session: SwipeDeckSessionRecord,
        cards: List<SwipeDeckSessionCardRecord>
    ) {
        deckSessions[session.sessionId] = session
        for (card in cards) {
            if (deckCards.none { it.sessionId == card.sessionId && it.targetUserId == card.targetUserId }) {
                deckCards += card
            }
        }
    }

    override suspend fun findDeckSession(sessionId: UUID, viewerUserId: UUID): SwipeDeckSessionRecord? =
        deckSessions[sessionId]?.takeIf { it.viewerUserId == viewerUserId }

    override suspend fun listDeckSessionTargetIds(sessionId: UUID): Set<UUID> =
        deckCards.filter { it.sessionId == sessionId }.mapTo(mutableSetOf()) { it.targetUserId }

    override suspend fun isDeckCardActionable(
        sessionId: UUID,
        cardTokenHash: String,
        viewerUserId: UUID,
        targetUserId: UUID,
        now: Instant
    ): Boolean {
        if (deckSessions.isEmpty()) return true
        val session = deckSessions[sessionId] ?: return false
        return session.viewerUserId == viewerUserId &&
            session.expiresAt.isAfter(now) &&
            sessionId to cardTokenHash !in actionedDeckCards &&
            deckCards.any {
                it.sessionId == sessionId &&
                    it.viewerUserId == viewerUserId &&
                    it.targetUserId == targetUserId &&
                    it.cardTokenHash == cardTokenHash
            }
    }

    override suspend fun markDeckCardActionRecorded(
        sessionId: UUID,
        cardTokenHash: String,
        targetUserId: UUID,
        action: String,
        now: Instant
    ) {
        actionedDeckCards += sessionId to cardTokenHash
    }

    override suspend fun isActionTargetEligible(
        viewerUserId: UUID,
        targetUserId: UUID,
        radiusMeters: Int,
        requireIntroNotesAllowed: Boolean
    ): Boolean =
        viewerUserId to targetUserId !in ineligibleTargets &&
            viewerUserId to targetUserId !in actions

    override suspend fun findSwipeActionById(actionId: UUID, actorUserId: UUID): SwipeActionRecord? {
        val record = actionRecords[actionId]?.takeIf { it.actorUserId == actorUserId } ?: return null
        return record.copy(match = findMatch(record.actorUserId, record.targetUserId))
    }

    override suspend fun findLatestRewindableAction(
        viewerUserId: UUID,
        radiusMeters: Int
    ): SwipeRewindCandidateRecord? {
        val record = actionOrder.asReversed()
            .asSequence()
            .mapNotNull { actionRecords[it] }
            .firstOrNull { record ->
                record.actorUserId == viewerUserId &&
                    record.rewoundAt == null &&
                    record.action in REWINDABLE_ACTIONS &&
                    findMatch(record.actorUserId, record.targetUserId) == null
            } ?: return null
        val card = seededCards.firstOrNull { it.userId == record.targetUserId } ?: SwipeCardRecord(
            userId = record.targetUserId,
            displayName = "Pynapli User",
            displayAge = null,
            thumbnailMediaAssetId = null,
            showDistance = true,
            distanceMeters = 1_000.0
        )
        return SwipeRewindCandidateRecord(
            actionId = record.actionId,
            targetUserId = record.targetUserId,
            action = record.action,
            card = card
        )
    }

    override suspend fun recordSwipeAction(
        actionId: UUID,
        actorUserId: UUID,
        targetUserId: UUID,
        action: String,
        noteText: String?,
        matchIdFactory: () -> UUID
    ): SwipeActionResult {
        actionRecords[actionId]?.takeIf { it.actorUserId == actorUserId }?.let { existing ->
            return SwipeActionResult(recorded = true, match = findMatch(existing.actorUserId, existing.targetUserId))
        }
        if (actorUserId to targetUserId in actions) {
            return SwipeActionResult(recorded = false, match = null)
        }
        actions[actorUserId to targetUserId] = action
        val match = if (action in POSITIVE_ACTIONS && actions[targetUserId to actorUserId] in POSITIVE_ACTIONS) {
            val (a, b) = canonicalPair(actorUserId, targetUserId)
            matches.firstOrNull { it.userA == a && it.userB == b } ?: MatchRecord(
                matchId = matchIdFactory(),
                userA = a,
                userB = b,
                createdAt = now
            ).also { matches.add(it) }
        } else {
            null
        }
        actionRecords[actionId] = SwipeActionRecord(
            actionId = actionId,
            actorUserId = actorUserId,
            targetUserId = targetUserId,
            action = action,
            noteText = noteText,
            createdAt = now,
            rewoundAt = null,
            match = match
        )
        actionOrder += actionId
        return SwipeActionResult(recorded = true, match = match)
    }

    override suspend fun rewindSwipeAction(
        actionId: UUID,
        actorUserId: UUID,
        targetUserId: UUID,
        session: SwipeDeckSessionRecord,
        card: SwipeDeckSessionCardRecord,
        rewoundAt: Instant
    ): Boolean {
        val record = actionRecords[actionId]
            ?.takeIf { it.actorUserId == actorUserId && it.targetUserId == targetUserId }
            ?: return false
        if (record.rewoundAt != null || record.action !in REWINDABLE_ACTIONS) return false
        if (findMatch(actorUserId, targetUserId) != null) return false
        actionRecords[actionId] = record.copy(rewoundAt = rewoundAt)
        actions.remove(actorUserId to targetUserId)
        upsertDeckSession(session, listOf(card))
        return true
    }

    private fun findMatch(actorUserId: UUID, targetUserId: UUID): MatchRecord? {
        val (a, b) = canonicalPair(actorUserId, targetUserId)
        return matches.firstOrNull { it.userA == a && it.userB == b }
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

    companion object {
        private val POSITIVE_ACTIONS = setOf(SwipeAction.LIKE, SwipeAction.PINEAPPLE_LIKE)
        private val REWINDABLE_ACTIONS = setOf(SwipeAction.LIKE, SwipeAction.PASS)
    }
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

private fun paidSubscriptionService(
    userId: UUID,
    tier: String,
    introNotes: Int,
    pineappleLikes: Int = 0
): SubscriptionService =
    SubscriptionService(StaticSubscriptionRepository.paid(userId, tier, introNotes, pineappleLikes))

private class StaticSubscriptionRepository(
    private val own: Map<UUID, SubscriptionRecord> = emptyMap(),
    introNotes: Map<UUID, Int> = emptyMap(),
    pineappleLikes: Map<UUID, Int> = emptyMap()
) : SubscriptionRepository {
    private val introNoteInventory = introNotes.toMutableMap()
    private val pineappleLikeInventory = pineappleLikes.toMutableMap()

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

    override suspend fun consumePineappleLikeInventory(userId: UUID, now: Instant): Boolean {
        val available = pineappleLikeInventory[userId] ?: 0
        if (available <= 0) return false
        pineappleLikeInventory[userId] = available - 1
        return true
    }

    fun introNotesRemaining(userId: UUID): Int = introNoteInventory[userId] ?: 0

    fun pineappleLikesRemaining(userId: UUID): Int = pineappleLikeInventory[userId] ?: 0

    companion object {
        fun paid(
            userId: UUID,
            tier: String,
            introNotes: Int,
            pineappleLikes: Int = 0
        ): StaticSubscriptionRepository =
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
                introNotes = mapOf(userId to introNotes),
                pineappleLikes = mapOf(userId to pineappleLikes)
            )
    }
}
