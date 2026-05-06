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
        showDistance = false,
        distanceMeters = null,
        albumRequestStatus = null
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
    private val seededEvents: List<ActivityEventRecord> = emptyList()
) : ActivityRepository {
    val profileViews = mutableListOf<Pair<UUID, UUID>>()
    val flirts = mutableListOf<Pair<UUID, UUID>>()
    val albumRequests = mutableListOf<Pair<UUID, UUID>>()

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

    override suspend fun getAlbumRequestEvent(recipientUserId: UUID, eventId: UUID): AlbumRequestEventRecord? = null

    override suspend fun listActivity(recipientUserId: UUID, limit: Int): List<ActivityEventRecord> =
        seededEvents.take(limit)
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
