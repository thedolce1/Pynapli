package com.pynapli.server.activity

import com.pynapli.server.notifications.NotificationEventDraft
import com.pynapli.server.notifications.NotificationKind
import com.pynapli.server.notifications.NotificationOutboxRecord
import com.pynapli.server.notifications.NotificationPublisher
import com.pynapli.server.safety.BlockRepository
import com.pynapli.server.safety.BlockRecord
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
import kotlin.test.assertTrue

class ActivityServiceTest {
    @Test
    fun recordProfileViewPublishesAggregatedNotificationWhenRecorded() = runBlocking {
        val viewerUserId = UUID.randomUUID()
        val targetUserId = UUID.randomUUID()
        val repository = InMemoryActivityRepository()
        val notifications = RecordingNotificationPublisher()
        val service = ActivityService(repository, notificationPublisher = notifications)

        service.recordProfileView(viewerUserId, targetUserId)

        assertEquals(listOf(viewerUserId to targetUserId), repository.profileViews)
        assertEquals(NotificationKind.PROFILE_VIEW, notifications.events.single().kind)
        assertEquals(targetUserId, notifications.events.single().recipientUserId)
        assertEquals(viewerUserId, notifications.events.single().actorUserId)
    }

    @Test
    fun recordProfileViewSkipsNotificationWhenRepositoryIgnoresView() = runBlocking {
        val repository = InMemoryActivityRepository(allowProfileViews = false)
        val notifications = RecordingNotificationPublisher()
        val service = ActivityService(repository, notificationPublisher = notifications)

        service.recordProfileView(UUID.randomUUID(), UUID.randomUUID())

        assertEquals(emptyList(), repository.profileViews)
        assertEquals(emptyList(), notifications.events)
    }

    @Test
    fun recordProfileViewSuppressesQuarantinedViewer() = runBlocking {
        val viewerUserId = UUID.randomUUID()
        val targetUserId = UUID.randomUUID()
        val repository = InMemoryActivityRepository()
        val notifications = RecordingNotificationPublisher()
        val service = ActivityService(
            repository = repository,
            notificationPublisher = notifications,
            riskPolicyService = restrictedRiskPolicy()
        )

        service.recordProfileView(viewerUserId, targetUserId)

        assertEquals(emptyList(), repository.profileViews)
        assertEquals(emptyList(), notifications.events)
    }

    @Test
    fun recordProfileViewSuppressesRateLimitedViewerBeforeRecording() = runBlocking {
        val viewerUserId = UUID.randomUUID()
        val targetUserId = UUID.randomUUID()
        val repository = InMemoryActivityRepository()
        val notifications = RecordingNotificationPublisher()
        val service = ActivityService(
            repository = repository,
            notificationPublisher = notifications,
            rateLimitService = denyingRateLimitService()
        )

        service.recordProfileView(viewerUserId, targetUserId)

        assertEquals(emptyList(), repository.profileViews)
        assertEquals(emptyList(), notifications.events)
    }

    @Test
    fun sendFlirtRecordsSparkSignal() = runBlocking {
        val senderUserId = UUID.randomUUID()
        val targetUserId = UUID.randomUUID()
        val repository = InMemoryActivityRepository()
        val notifications = RecordingNotificationPublisher()
        val service = ActivityService(repository, notificationPublisher = notifications)

        val response = service.sendFlirt(senderUserId, targetUserId)

        assertEquals(true, response.sent)
        assertEquals(listOf(senderUserId to targetUserId), repository.flirts)
        assertEquals(NotificationKind.FLIRT, notifications.events.single().kind)
        assertEquals(targetUserId, notifications.events.single().recipientUserId)
    }

    @Test
    fun sendFlirtRejectsSelfTarget() = runBlocking {
        val userId = UUID.randomUUID()
        val service = ActivityService(InMemoryActivityRepository())

        val error = assertFailsWith<ActivityException> {
            service.sendFlirt(userId, userId)
        }

        assertEquals("INVALID_FLIRT_TARGET", error.code)
    }

    @Test
    fun sendFlirtRejectsProfilesThatDoNotAcceptFlirts() = runBlocking {
        val service = ActivityService(InMemoryActivityRepository(allowFlirts = false))

        val error = assertFailsWith<ActivityException> {
            service.sendFlirt(UUID.randomUUID(), UUID.randomUUID())
        }

        assertEquals("FLIRT_NOT_ALLOWED", error.code)
    }

    @Test
    fun sendFlirtRejectsBlockedTargetBeforeRecording() = runBlocking {
        val senderUserId = UUID.randomUUID()
        val targetUserId = UUID.randomUUID()
        val repository = InMemoryActivityRepository()
        val service = ActivityService(
            repository = repository,
            blockRepository = StaticBlockRepository(mapOf(senderUserId to setOf(targetUserId)))
        )

        val error = assertFailsWith<ActivityException> {
            service.sendFlirt(senderUserId, targetUserId)
        }

        assertEquals("FLIRT_NOT_ALLOWED", error.code)
        assertEquals(emptyList(), repository.flirts)
    }

    @Test
    fun sendFlirtRejectsRateLimitedSenderBeforeRecording() = runBlocking {
        val senderUserId = UUID.randomUUID()
        val targetUserId = UUID.randomUUID()
        val repository = InMemoryActivityRepository()
        val service = ActivityService(
            repository = repository,
            rateLimitService = denyingRateLimitService()
        )

        val error = assertFailsWith<ActivityException> {
            service.sendFlirt(senderUserId, targetUserId)
        }

        assertEquals("RATE_LIMITED", error.code)
        assertEquals(emptyList(), repository.flirts)
    }

    @Test
    fun sendFlirtRejectsRiskRestrictedSenderBeforeRecording() = runBlocking {
        val senderUserId = UUID.randomUUID()
        val targetUserId = UUID.randomUUID()
        val repository = InMemoryActivityRepository()
        val service = ActivityService(
            repository = repository,
            riskPolicyService = restrictedRiskPolicy()
        )

        val error = assertFailsWith<ActivityException> {
            service.sendFlirt(senderUserId, targetUserId)
        }

        assertEquals("RISK_RESTRICTED", error.code)
        assertEquals(emptyList(), repository.flirts)
    }

    @Test
    fun requestAlbumAccessRecordsAlbumRequestSignal() = runBlocking {
        val requesterUserId = UUID.randomUUID()
        val targetUserId = UUID.randomUUID()
        val repository = InMemoryActivityRepository()
        val notifications = RecordingNotificationPublisher()
        val service = ActivityService(repository, notificationPublisher = notifications)

        val response = service.requestAlbumAccess(requesterUserId, targetUserId)

        assertEquals(true, response.requested)
        assertEquals(listOf(requesterUserId to targetUserId), repository.albumRequests)
        assertEquals(NotificationKind.ALBUM_REQUEST, notifications.events.single().kind)
        assertEquals(targetUserId, notifications.events.single().recipientUserId)
    }

    @Test
    fun requestAlbumAccessRejectsSelfTarget() = runBlocking {
        val userId = UUID.randomUUID()
        val service = ActivityService(InMemoryActivityRepository())

        val error = assertFailsWith<ActivityException> {
            service.requestAlbumAccess(userId, userId)
        }

        assertEquals("INVALID_ALBUM_REQUEST_TARGET", error.code)
    }

    @Test
    fun requestAlbumAccessRejectsProfilesThatDoNotAcceptAlbumRequests() = runBlocking {
        val service = ActivityService(InMemoryActivityRepository(allowAlbumRequests = false))

        val error = assertFailsWith<ActivityException> {
            service.requestAlbumAccess(UUID.randomUUID(), UUID.randomUUID())
        }

        assertEquals("ALBUM_REQUEST_NOT_ALLOWED", error.code)
    }

    @Test
    fun requestAlbumAccessRejectsBlockedTargetBeforeRecording() = runBlocking {
        val requesterUserId = UUID.randomUUID()
        val targetUserId = UUID.randomUUID()
        val repository = InMemoryActivityRepository()
        val service = ActivityService(
            repository = repository,
            blockRepository = StaticBlockRepository(mapOf(requesterUserId to setOf(targetUserId)))
        )

        val error = assertFailsWith<ActivityException> {
            service.requestAlbumAccess(requesterUserId, targetUserId)
        }

        assertEquals("ALBUM_REQUEST_NOT_ALLOWED", error.code)
        assertEquals(emptyList(), repository.albumRequests)
    }

    @Test
    fun requestAlbumAccessRejectsRateLimitedRequesterBeforeRecording() = runBlocking {
        val requesterUserId = UUID.randomUUID()
        val targetUserId = UUID.randomUUID()
        val repository = InMemoryActivityRepository()
        val service = ActivityService(
            repository = repository,
            rateLimitService = denyingRateLimitService()
        )

        val error = assertFailsWith<ActivityException> {
            service.requestAlbumAccess(requesterUserId, targetUserId)
        }

        assertEquals("RATE_LIMITED", error.code)
        assertEquals(emptyList(), repository.albumRequests)
    }

    @Test
    fun requestAlbumAccessRejectsRiskRestrictedRequesterBeforeRecording() = runBlocking {
        val requesterUserId = UUID.randomUUID()
        val targetUserId = UUID.randomUUID()
        val repository = InMemoryActivityRepository()
        val service = ActivityService(
            repository = repository,
            riskPolicyService = restrictedRiskPolicy()
        )

        val error = assertFailsWith<ActivityException> {
            service.requestAlbumAccess(requesterUserId, targetUserId)
        }

        assertEquals("RISK_RESTRICTED", error.code)
        assertEquals(emptyList(), repository.albumRequests)
    }

    @Test
    fun recordSwipeInterestWritesLikesAndIntroNotesForActivityTabs() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000002001")
        val target = UUID.fromString("00000000-0000-0000-0000-000000002002")
        val repository = InMemoryActivityRepository()
        val service = ActivityService(repository)

        service.recordSwipeInterest(viewer, target, "swipe_like")
        service.recordSwipeInterest(viewer, target, "intro_note")

        assertEquals(
            listOf(
                Triple(viewer, target, "swipe_like"),
                Triple(viewer, target, "intro_note")
            ),
            repository.swipeInterests
        )
    }

    @Test
    fun recordSwipeInterestRejectsUnknownEventType() = runBlocking {
        val service = ActivityService(InMemoryActivityRepository())

        val error = assertFailsWith<ActivityException> {
            service.recordSwipeInterest(UUID.randomUUID(), UUID.randomUUID(), "super_like")
        }

        assertEquals("INVALID_ACTIVITY_EVENT_TYPE", error.code)
    }

    @Test
    fun recordMatchWritesBothActivityDirections() = runBlocking {
        val userA = UUID.fromString("00000000-0000-0000-0000-000000002011")
        val userB = UUID.fromString("00000000-0000-0000-0000-000000002012")
        val matchId = UUID.fromString("00000000-0000-0000-0000-000000002013")
        val repository = InMemoryActivityRepository()
        val service = ActivityService(repository)

        service.recordMatch(userA, userB, matchId)

        assertEquals(
            listOf(
                Triple(userA, userB, matchId),
                Triple(userB, userA, matchId)
            ),
            repository.matches
        )
    }

    @Test
    fun recordPartnerChatInviteWritesInviteeActivity() = runBlocking {
        val inviter = UUID.fromString("00000000-0000-0000-0000-000000002021")
        val invitee = UUID.fromString("00000000-0000-0000-0000-000000002022")
        val conversationId = UUID.fromString("00000000-0000-0000-0000-000000002023")
        val repository = InMemoryActivityRepository()
        val service = ActivityService(repository)

        service.recordPartnerChatInvite(inviter, invitee, conversationId)

        assertEquals(listOf(Triple(inviter, invitee, conversationId)), repository.partnerChatInvites)
    }

    @Test
    fun listActivityFiltersOutEventsFromBlockedRelatedUsers() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000501")
        val blockedActor = UUID.fromString("00000000-0000-0000-0000-000000000502")
        val blockerOfViewer = UUID.fromString("00000000-0000-0000-0000-000000000503")
        val visibleActor = UUID.fromString("00000000-0000-0000-0000-000000000504")

        val seededEvents = listOf(
            sampleEvent(blockedActor, "viewed_profile", "Alex"),
            sampleEvent(blockerOfViewer, "flirt", "Sam"),
            sampleEvent(visibleActor, "viewed_profile", "Maya")
        )
        val service = ActivityService(
            repository = InMemoryActivityRepository(seededEvents = seededEvents),
            blockRepository = StaticBlockRepository(
                relatedByUser = mapOf(viewer to setOf(blockedActor, blockerOfViewer))
            )
        )

        val response = service.listActivity(viewer)

        assertEquals(1, response.items.size)
        assertEquals(visibleActor.toString(), response.items.single().actorUserId)
    }

    @Test
    fun listActivitySuppressesRiskRestrictedActors() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000511")
        val quarantinedActor = UUID.fromString("00000000-0000-0000-0000-000000000512")
        val service = ActivityService(
            repository = InMemoryActivityRepository(
                seededEvents = listOf(sampleEvent(quarantinedActor, "flirt", "Spam"))
            ),
            riskPolicyService = restrictedRiskPolicy()
        )

        val response = service.listActivity(viewer)

        assertEquals(emptyList(), response.items)
    }

    @Test
    fun listActivityIsUnchangedWhenNoBlockRepositoryIsAttached() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000601")
        val actor = UUID.fromString("00000000-0000-0000-0000-000000000602")
        val service = ActivityService(
            repository = InMemoryActivityRepository(
                seededEvents = listOf(sampleEvent(actor, "viewed_profile", "Maya"))
            )
        )

        val response = service.listActivity(viewer)

        assertEquals(1, response.items.size)
        assertTrue(response.items.single().actorUserId == actor.toString())
    }

    @Test
    fun listActivityReturnsRoundedDistanceMetersWhenVisible() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000701")
        val actor = UUID.fromString("00000000-0000-0000-0000-000000000702")
        val service = ActivityService(
            repository = InMemoryActivityRepository(
                seededEvents = listOf(
                    sampleEvent(actor, "viewed_profile", "Maya").copy(
                        showDistance = true,
                        distanceMeters = 1_240.0
                    )
                )
            )
        )

        val response = service.listActivity(viewer)

        assertEquals("1.2 km", response.items.single().distanceLabel)
        assertEquals(1_200, response.items.single().distanceMeters)
    }

    @Test
    fun listActivityLimitsRevealDepthForFreeUsers() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000801")
        val firstActor = UUID.fromString("00000000-0000-0000-0000-000000000802")
        val secondActor = UUID.fromString("00000000-0000-0000-0000-000000000803")
        val service = ActivityService(
            repository = InMemoryActivityRepository(
                seededEvents = listOf(
                    sampleEvent(firstActor, "profile_view", "Maya"),
                    sampleEvent(secondActor, "flirt", "Alex")
                )
            ),
            subscriptionService = SubscriptionService(StaticSubscriptionRepository())
        )

        val response = service.listActivity(viewer)

        assertEquals(listOf(firstActor.toString()), response.items.map { it.actorUserId })
        assertEquals(1, response.revealLimit)
        assertEquals(1, response.gatedCount)
        assertEquals(SubscriptionTier.FREE, response.entitlementTier)
        assertEquals(true, response.adSupported)
    }

    @Test
    fun listActivityReturnsFullRevealForSelectUsers() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000804")
        val firstActor = UUID.fromString("00000000-0000-0000-0000-000000000805")
        val secondActor = UUID.fromString("00000000-0000-0000-0000-000000000806")
        val service = ActivityService(
            repository = InMemoryActivityRepository(
                seededEvents = listOf(
                    sampleEvent(firstActor, "profile_view", "Maya"),
                    sampleEvent(secondActor, "flirt", "Alex")
                )
            ),
            subscriptionService = paidSubscriptionService(viewer, SubscriptionTier.SELECT)
        )

        val response = service.listActivity(viewer)

        assertEquals(listOf(firstActor.toString(), secondActor.toString()), response.items.map { it.actorUserId })
        assertEquals(null, response.revealLimit)
        assertEquals(0, response.gatedCount)
        assertEquals(SubscriptionTier.SELECT, response.entitlementTier)
        assertEquals(false, response.adSupported)
    }

    @Test
    fun listActivityFiltersByRequestedTab() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000901")
        val viewActor = UUID.fromString("00000000-0000-0000-0000-000000000902")
        val flirtActor = UUID.fromString("00000000-0000-0000-0000-000000000903")
        val service = ActivityService(
            repository = InMemoryActivityRepository(
                seededEvents = listOf(
                    sampleEvent(viewActor, "profile_view", "Maya"),
                    sampleEvent(flirtActor, "flirt", "Alex")
                )
            )
        )

        val response = service.listActivity(viewer, tabText = "flirts")

        assertEquals(listOf("flirt"), response.items.map { it.type })
        assertEquals(listOf(flirtActor.toString()), response.items.map { it.actorUserId })
    }

    @Test
    fun listActivityRejectsUnknownTabs() = runBlocking {
        val service = ActivityService(InMemoryActivityRepository())

        val error = assertFailsWith<ActivityException> {
            service.listActivity(UUID.randomUUID(), tabText = "surveillance")
        }

        assertEquals("INVALID_ACTIVITY_TAB", error.code)
    }

    @Test
    fun listActivityReturnsCursorAndContinuesNewestFirst() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000911")
        val firstActor = UUID.fromString("00000000-0000-0000-0000-000000000912")
        val secondActor = UUID.fromString("00000000-0000-0000-0000-000000000913")
        val thirdActor = UUID.fromString("00000000-0000-0000-0000-000000000914")
        val cursorCodec = ActivityCursorCodec("test-activity-cursor-secret")
        val service = ActivityService(
            repository = InMemoryActivityRepository(
                seededEvents = listOf(
                    sampleEvent(firstActor, "flirt", "Maya").copy(occurredAt = Instant.parse("2026-04-28T12:03:00Z")),
                    sampleEvent(secondActor, "flirt", "Alex").copy(occurredAt = Instant.parse("2026-04-28T12:02:00Z")),
                    sampleEvent(thirdActor, "flirt", "Sam").copy(occurredAt = Instant.parse("2026-04-28T12:01:00Z"))
                )
            ),
            cursorCodec = cursorCodec
        )

        val firstPage = service.listActivity(viewer, limit = 1, tabText = "flirts")
        val cursor = assertNotNull(firstPage.nextCursor)
        val secondPage = service.listActivity(viewer, limit = 2, tabText = "flirts", cursorText = cursor)

        assertEquals(listOf(firstActor.toString()), firstPage.items.map { it.actorUserId })
        assertEquals(listOf(secondActor.toString(), thirdActor.toString()), secondPage.items.map { it.actorUserId })
        assertEquals(null, secondPage.nextCursor)
    }

    @Test
    fun listActivityRejectsCursorForDifferentTabScope() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000921")
        val actor = UUID.fromString("00000000-0000-0000-0000-000000000922")
        val cursorCodec = ActivityCursorCodec("test-activity-cursor-secret")
        val cursor = cursorCodec.encode(
            ActivityCursorCodec.scopeHash(viewer, ActivityFeedTab.FLIRTS),
            ActivityCursorAnchor(
                occurredAt = Instant.parse("2026-04-28T12:00:00Z"),
                eventId = UUID.randomUUID(),
                seenCount = 1
            )
        )
        val service = ActivityService(
            repository = InMemoryActivityRepository(
                seededEvents = listOf(sampleEvent(actor, "profile_view", "Maya"))
            ),
            cursorCodec = cursorCodec
        )

        val error = assertFailsWith<ActivityException> {
            service.listActivity(viewer, tabText = "views", cursorText = cursor)
        }

        assertEquals("INVALID_ACTIVITY_CURSOR", error.code)
    }

    @Test
    fun dismissActivityEventSoftDeletesOwnedItem() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000931")
        val eventId = UUID.fromString("00000000-0000-0000-0000-000000000932")
        val repository = InMemoryActivityRepository(
            seededEvents = listOf(
                sampleEvent(UUID.randomUUID(), "flirt", "Maya").copy(eventId = eventId)
            )
        )
        val service = ActivityService(repository)

        val response = service.dismissActivityEvent(viewer, eventId)
        val feed = service.listActivity(viewer)

        assertEquals(true, response.dismissed)
        assertEquals(eventId.toString(), response.eventId)
        assertEquals(emptyList(), feed.items)
    }

    @Test
    fun getActivitySummaryMapsMeaningfulBadgeCountsWithoutViews() = runBlocking {
        val latest = Instant.parse("2026-05-19T12:30:00Z")
        val service = ActivityService(
            InMemoryActivityRepository(
                summaryRecord = ActivitySummaryRecord(
                    profileViewCount = 12,
                    flirtCount = 2,
                    likeCount = 3,
                    matchCount = 1,
                    albumRequestCount = 1,
                    partnerInviteCount = 1,
                    latestBadgeOccurredAt = latest
                )
            )
        )

        val response = service.getActivitySummary(UUID.randomUUID())

        assertEquals(8, response.badgeCount)
        assertEquals(12, response.profileViewCount)
        assertEquals(2, response.flirtCount)
        assertEquals(3, response.likeCount)
        assertEquals(1, response.matchCount)
        assertEquals(1, response.albumRequestCount)
        assertEquals(1, response.partnerInviteCount)
        assertEquals(latest.toString(), response.latestBadgeOccurredAt)
    }

    @Test
    fun markActivityEventsSeenMarksOnlyRequestedOwnedEvents() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000941")
        val firstEventId = UUID.fromString("00000000-0000-0000-0000-000000000942")
        val secondEventId = UUID.fromString("00000000-0000-0000-0000-000000000943")
        val repository = InMemoryActivityRepository(
            seededEvents = listOf(
                sampleEvent(UUID.randomUUID(), "flirt", "Maya").copy(eventId = firstEventId),
                sampleEvent(UUID.randomUUID(), "match", "Alex").copy(eventId = secondEventId)
            )
        )
        val service = ActivityService(repository)

        val response = service.markActivityEventsSeen(
            recipientUserId = viewer,
            eventIdTexts = listOf(firstEventId.toString(), secondEventId.toString(), firstEventId.toString())
        )

        assertEquals(2, response.markedCount)
        assertEquals(setOf(firstEventId, secondEventId), repository.seenEventIds)
    }

    @Test
    fun markActivityEventsSeenRejectsInvalidEventId() = runBlocking {
        val service = ActivityService(InMemoryActivityRepository())

        val error = assertFailsWith<ActivityException> {
            service.markActivityEventsSeen(UUID.randomUUID(), listOf("not-a-uuid"))
        }

        assertEquals("INVALID_ACTIVITY_EVENT", error.code)
    }
}

private fun sampleEvent(actorUserId: UUID, type: String, displayName: String): ActivityEventRecord =
    ActivityEventRecord(
        eventId = UUID.randomUUID(),
        type = type,
        actorUserId = actorUserId,
        actorDisplayName = displayName,
        actorProfilePhotoMediaAssetId = null,
        count = 1,
        occurredAt = Instant.parse("2026-04-28T12:00:00Z"),
        seen = false,
        showDistance = false,
        distanceMeters = null,
        albumRequestStatus = null,
        matchId = null,
        conversationId = null
    )

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
            lastEventAt = Instant.parse("2026-04-28T12:00:00Z"),
            lastReviewedAt = null,
            updatedAt = Instant.parse("2026-04-28T12:00:00Z")
        )
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

private class InMemoryActivityRepository(
    private val allowProfileViews: Boolean = true,
    private val allowFlirts: Boolean = true,
    private val allowAlbumRequests: Boolean = true,
    private val seededEvents: List<ActivityEventRecord> = emptyList(),
    private val summaryRecord: ActivitySummaryRecord = ActivitySummaryRecord(
        profileViewCount = 0,
        flirtCount = 0,
        likeCount = 0,
        matchCount = 0,
        albumRequestCount = 0,
        partnerInviteCount = 0,
        latestBadgeOccurredAt = null
    )
) : ActivityRepository {
    val profileViews = mutableListOf<Pair<UUID, UUID>>()
    val flirts = mutableListOf<Pair<UUID, UUID>>()
    val albumRequests = mutableListOf<Pair<UUID, UUID>>()
    val swipeInterests = mutableListOf<Triple<UUID, UUID, String>>()
    val matches = mutableListOf<Triple<UUID, UUID, UUID>>()
    val partnerChatInvites = mutableListOf<Triple<UUID, UUID, UUID>>()
    val seenEventIds = mutableSetOf<UUID>()

    override suspend fun recordProfileView(viewerUserId: UUID, targetUserId: UUID): Boolean {
        if (!allowProfileViews) return false
        profileViews += viewerUserId to targetUserId
        return true
    }

    override suspend fun recordFlirt(senderUserId: UUID, targetUserId: UUID): Boolean {
        if (!allowFlirts) return false
        flirts += senderUserId to targetUserId
        return true
    }

    override suspend fun recordAlbumRequest(requesterUserId: UUID, targetUserId: UUID): Boolean {
        if (!allowAlbumRequests) return false
        albumRequests += requesterUserId to targetUserId
        return true
    }

    override suspend fun recordSwipeInterest(actorUserId: UUID, targetUserId: UUID, eventType: String): Boolean {
        swipeInterests += Triple(actorUserId, targetUserId, eventType)
        return true
    }

    override suspend fun recordMatch(recipientUserId: UUID, actorUserId: UUID, matchId: UUID): Boolean {
        matches += Triple(recipientUserId, actorUserId, matchId)
        return true
    }

    override suspend fun recordPartnerChatInvite(inviterUserId: UUID, inviteeUserId: UUID, conversationId: UUID): Boolean {
        partnerChatInvites += Triple(inviterUserId, inviteeUserId, conversationId)
        return true
    }

    override suspend fun getAlbumRequestEvent(recipientUserId: UUID, eventId: UUID): AlbumRequestEventRecord? = null

    override suspend fun listActivity(query: ActivityFeedQuery): List<ActivityEventRecord> {
        val cursor = query.cursor
        return seededEvents
            .asSequence()
            .filter { query.eventTypes.isEmpty() || it.type in query.eventTypes }
            .filter {
                cursor == null ||
                    it.occurredAt < cursor.occurredAt ||
                    (it.occurredAt == cursor.occurredAt && it.eventId < cursor.eventId)
            }
            .filterNot { it.eventId in dismissedEventIds }
            .take(query.limit)
            .toList()
    }

    override suspend fun getActivitySummary(recipientUserId: UUID): ActivitySummaryRecord = summaryRecord

    override suspend fun markActivityEventsSeen(recipientUserId: UUID, eventIds: Set<UUID>): Int {
        val ownedEventIds = seededEvents.mapTo(mutableSetOf()) { it.eventId }
        val newlySeen = eventIds.filter { it in ownedEventIds && it !in dismissedEventIds && it !in seenEventIds }
        seenEventIds += newlySeen
        return newlySeen.size
    }

    override suspend fun dismissActivityEvent(recipientUserId: UUID, eventId: UUID): Boolean {
        val exists = seededEvents.any { it.eventId == eventId }
        if (exists) dismissedEventIds += eventId
        return exists
    }

    private val dismissedEventIds = mutableSetOf<UUID>()
}

private class RecordingNotificationPublisher : NotificationPublisher {
    val events = mutableListOf<NotificationEventDraft>()

    override suspend fun enqueue(notification: NotificationEventDraft): NotificationOutboxRecord? {
        events += notification
        return null
    }
}

private fun paidSubscriptionService(userId: UUID, tier: String): SubscriptionService =
    SubscriptionService(
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
            )
        )
    )

private class StaticSubscriptionRepository(
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
