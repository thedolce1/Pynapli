package com.pynapli.server.grid

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.presence.PresenceRepository
import com.pynapli.server.presence.PresenceService
import com.pynapli.server.presence.PresenceState
import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.subscriptions.LinkedPartnerSubscriptionRecord
import com.pynapli.server.subscriptions.StoredEntitlementRecord
import com.pynapli.server.subscriptions.SubscriptionRecord
import com.pynapli.server.subscriptions.SubscriptionRepository
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.shared.network.dto.GridQueryRequestDto
import com.pynapli.shared.network.dto.SubscriptionTier
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GridServiceTest {
    @Test
    fun queryGridRequiresViewerLocation() = runBlocking {
        val service = GridService(
            StaticGridRepository(GridQueryResult(viewerHadLocation = false, cards = emptyList()))
        )

        val error = assertFailsWith<GridException> {
            service.queryGrid(UUID.randomUUID(), GridQueryRequestDto())
        }

        assertEquals("LOCATION_REQUIRED", error.code)
    }

    @Test
    fun queryGridReturnsMinimalCards() = runBlocking {
        val targetId = UUID.randomUUID()
        val service = GridService(
            StaticGridRepository(
                GridQueryResult(
                    viewerHadLocation = true,
                    cards = listOf(
                        GridCardRecord(
                            userId = targetId,
                            displayName = "Alex",
                            showDistance = true,
                            distanceMeters = 1_240.0
                        )
                    )
                )
            )
        )

        val response = service.queryGrid(UUID.randomUUID(), GridQueryRequestDto())

        assertEquals(1, response.items.size)
        assertEquals(targetId.toString(), response.items.first().userId)
        assertEquals("Alex", response.items.first().displayName)
        assertEquals("1.2 km", response.items.first().distanceLabel)
        assertEquals(1_200, response.items.first().distanceMeters)
        assertEquals(false, response.items.first().online)
        assertNull(response.items.first().thumbnailUrl)
    }

    @Test
    fun queryGridHidesDistanceWhenTargetDoesNotShowDistance() = runBlocking {
        val service = GridService(
            StaticGridRepository(
                GridQueryResult(
                    viewerHadLocation = true,
                    cards = listOf(
                        GridCardRecord(
                            userId = UUID.randomUUID(),
                            displayName = "Alex",
                            showDistance = false,
                            distanceMeters = 300.0
                        )
                    )
                )
            )
        )

        val response = service.queryGrid(UUID.randomUUID(), GridQueryRequestDto())

        assertNull(response.items.first().distanceLabel)
        assertNull(response.items.first().distanceMeters)
    }

    @Test
    fun queryGridUsesPresenceForOnlineIndicator() = runBlocking {
        val viewerId = UUID.randomUUID()
        val targetId = UUID.randomUUID()
        val service = GridService(
            repository = StaticGridRepository(
                GridQueryResult(
                    viewerHadLocation = true,
                    cards = listOf(
                        GridCardRecord(
                            userId = targetId,
                            displayName = "Alex",
                            showDistance = true,
                            distanceMeters = 300.0
                        )
                    )
                )
            ),
            presenceService = PresenceService(StaticPresenceRepository(setOf(targetId)))
        )

        val response = service.queryGrid(viewerId, GridQueryRequestDto())

        assertEquals(true, response.items.first().online)
    }

    @Test
    fun queryGridIncludesApprovedProfileThumbnailUrlWhenProjected() = runBlocking {
        val targetId = UUID.randomUUID()
        val mediaAssetId = UUID.randomUUID()
        val service = GridService(
            repository = StaticGridRepository(
                GridQueryResult(
                    viewerHadLocation = true,
                    cards = listOf(
                        GridCardRecord(
                            userId = targetId,
                            displayName = "Alex",
                            thumbnailMediaAssetId = mediaAssetId,
                            showDistance = true,
                            distanceMeters = 300.0
                        )
                    )
                )
            ),
            mediaAssetUrlResolver = StaticMediaAssetUrlResolver()
        )

        val response = service.queryGrid(UUID.randomUUID(), GridQueryRequestDto())

        assertEquals("https://media.example/$mediaAssetId", response.items.first().thumbnailUrl)
    }

    @Test
    fun queryGridIncludesLinkedPartnerMiniWhenProjected() = runBlocking {
        val targetId = UUID.randomUUID()
        val partnerId = UUID.randomUUID()
        val partnerMediaAssetId = UUID.randomUUID()
        val service = GridService(
            repository = StaticGridRepository(
                GridQueryResult(
                    viewerHadLocation = true,
                    cards = listOf(
                        GridCardRecord(
                            userId = targetId,
                            displayName = "Maya",
                            showDistance = true,
                            distanceMeters = 300.0,
                            linkedPartnerUserId = partnerId,
                            linkedPartnerDisplayName = "Noa",
                            linkedPartnerThumbnailMediaAssetId = partnerMediaAssetId
                        )
                    )
                )
            ),
            mediaAssetUrlResolver = StaticMediaAssetUrlResolver()
        )

        val response = service.queryGrid(UUID.randomUUID(), GridQueryRequestDto())

        val linkedPartner = response.items.first().linkedPartner
        assertEquals(partnerId.toString(), linkedPartner?.userId)
        assertEquals("Noa", linkedPartner?.displayName)
        assertEquals("https://media.example/$partnerMediaAssetId", linkedPartner?.thumbnailUrl)
    }

    @Test
    fun queryGridRejectsAdvancedFiltersForFreeUsers() = runBlocking {
        val viewerId = UUID.randomUUID()
        val service = GridService(
            repository = StaticGridRepository(GridQueryResult(viewerHadLocation = true, cards = emptyList())),
            viewerTrustRepository = StaticGridViewerTrustRepository(ageVerifiedUserIds = setOf(viewerId)),
            subscriptionService = freeSubscriptionService()
        )

        val error = assertFailsWith<GridException> {
            service.queryGrid(viewerId, GridQueryRequestDto(lifestyleTags = listOf("soft_swap")))
        }

        assertEquals("ENTITLEMENT_REQUIRED", error.code)
    }

    @Test
    fun queryGridRejectsLifestyleFiltersForUnverifiedViewers() = runBlocking {
        val viewerId = UUID.randomUUID()
        val service = GridService(
            repository = StaticGridRepository(GridQueryResult(viewerHadLocation = true, cards = emptyList())),
            viewerTrustRepository = StaticGridViewerTrustRepository(ageVerifiedUserIds = emptySet()),
            subscriptionService = paidSubscriptionService(viewerId, SubscriptionTier.SOCIAL)
        )

        val error = assertFailsWith<GridException> {
            service.queryGrid(viewerId, GridQueryRequestDto(lifestyleTags = listOf("soft_swap")))
        }

        assertEquals("AGE_VERIFICATION_REQUIRED", error.code)
    }

    @Test
    fun queryGridAllowsSafetyAndBasicIntentFiltersForFreeUsers() = runBlocking {
        val viewerId = UUID.randomUUID()
        val targetId = UUID.randomUUID()
        val service = GridService(
            repository = StaticGridRepository(
                GridQueryResult(
                    viewerHadLocation = true,
                    cards = listOf(GridCardRecord(targetId, "Alex", showDistance = true, distanceMeters = 300.0))
                )
            ),
            presenceService = PresenceService(StaticPresenceRepository(setOf(targetId))),
            subscriptionService = freeSubscriptionService()
        )

        val response = service.queryGrid(
            viewerId,
            GridQueryRequestDto(
                onlineOnly = true,
                verifiedOnly = true,
                facePhotoVerifiedOnly = true,
                ageVerifiedOnly = true,
                discoveryStyles = listOf("solo"),
                intents = listOf("dating"),
                logisticsTags = listOf("public_meet_first"),
                tonightAvailableOnly = true
            )
        )

        assertEquals(listOf(targetId.toString()), response.items.map { it.userId })
        assertEquals(true, response.adSupported)
        assertEquals(false, response.advancedFiltersApplied)
    }

    @Test
    fun queryGridAllowsOnlineOnlyFilterForPaidUsers() = runBlocking {
        val viewerId = UUID.randomUUID()
        val onlineTarget = UUID.randomUUID()
        val offlineTarget = UUID.randomUUID()
        val service = GridService(
            repository = StaticGridRepository(
                GridQueryResult(
                    viewerHadLocation = true,
                    cards = listOf(
                        GridCardRecord(onlineTarget, "Online", showDistance = true, distanceMeters = 100.0),
                        GridCardRecord(offlineTarget, "Offline", showDistance = true, distanceMeters = 200.0)
                    )
                )
            ),
            presenceService = PresenceService(StaticPresenceRepository(setOf(onlineTarget))),
            subscriptionService = paidSubscriptionService(viewerId, SubscriptionTier.SOCIAL)
        )

        val response = service.queryGrid(viewerId, GridQueryRequestDto(onlineOnly = true))

        assertEquals(listOf(onlineTarget.toString()), response.items.map { it.userId })
        assertEquals(false, response.adSupported)
        assertEquals(false, response.advancedFiltersApplied)
    }

    @Test
    fun queryGridReturnsSignedCursorAndPassesDecodedAnchorToRepository() = runBlocking {
        val viewerId = UUID.randomUUID()
        val targetId = UUID.randomUUID()
        val anchor = GridCursorAnchor(
            boostBucket = 1,
            distanceBucketMeters = 1_240,
            userId = targetId
        )
        val repository = CapturingGridRepository(
            GridQueryResult(
                viewerHadLocation = true,
                cards = listOf(
                    GridCardRecord(
                        userId = targetId,
                        displayName = "Alex",
                        showDistance = true,
                        distanceMeters = 1_240.0,
                        distanceBucketMeters = 1_240
                    )
                ),
                nextCursorAnchor = anchor
            )
        )
        val service = GridService(repository)

        val firstPage = service.queryGrid(viewerId, GridQueryRequestDto(limit = 1))
        val nextCursor = assertNotNull(firstPage.nextCursor)
        service.queryGrid(viewerId, GridQueryRequestDto(limit = 1, cursor = nextCursor))

        assertEquals(listOf(null, anchor), repository.receivedCursors)
    }

    @Test
    fun queryGridRejectsCursorFromDifferentFilterScope() = runBlocking {
        val viewerId = UUID.randomUUID()
        val targetId = UUID.randomUUID()
        val service = GridService(
            StaticGridRepository(
                GridQueryResult(
                    viewerHadLocation = true,
                    cards = listOf(
                        GridCardRecord(
                            userId = targetId,
                            displayName = "Alex",
                            showDistance = true,
                            distanceMeters = 1_240.0,
                            distanceBucketMeters = 1_240
                        )
                    ),
                    nextCursorAnchor = GridCursorAnchor(
                        boostBucket = 1,
                        distanceBucketMeters = 1_240,
                        userId = targetId
                    )
                )
            )
        )

        val firstPage = service.queryGrid(viewerId, GridQueryRequestDto(limit = 1))
        val nextCursor = assertNotNull(firstPage.nextCursor)
        val error = assertFailsWith<GridException> {
            service.queryGrid(viewerId, GridQueryRequestDto(limit = 2, cursor = nextCursor))
        }

        assertEquals("INVALID_GRID_CURSOR", error.code)
    }

    @Test
    fun queryGridRejectsTamperedCursor() = runBlocking {
        val viewerId = UUID.randomUUID()
        val targetId = UUID.randomUUID()
        val service = GridService(
            StaticGridRepository(
                GridQueryResult(
                    viewerHadLocation = true,
                    cards = listOf(
                        GridCardRecord(
                            userId = targetId,
                            displayName = "Alex",
                            showDistance = true,
                            distanceMeters = 1_240.0,
                            distanceBucketMeters = 1_240
                        )
                    ),
                    nextCursorAnchor = GridCursorAnchor(
                        boostBucket = 1,
                        distanceBucketMeters = 1_240,
                        userId = targetId
                    )
                )
            )
        )

        val firstPage = service.queryGrid(viewerId, GridQueryRequestDto(limit = 1))
        val tamperedCursor = assertNotNull(firstPage.nextCursor).dropLast(1) + "x"
        val error = assertFailsWith<GridException> {
            service.queryGrid(viewerId, GridQueryRequestDto(limit = 1, cursor = tamperedCursor))
        }

        assertEquals("INVALID_GRID_CURSOR", error.code)
    }
}

private class StaticGridRepository(
    private val result: GridQueryResult
) : GridRepository {
    override suspend fun queryNearby(
        viewerUserId: UUID,
        radiusMeters: Int,
        limit: Int,
        filters: DiscoveryFilters,
        cursor: GridCursorAnchor?
    ): GridQueryResult = result
}

private class CapturingGridRepository(
    private val result: GridQueryResult
) : GridRepository {
    val receivedCursors = mutableListOf<GridCursorAnchor?>()

    override suspend fun queryNearby(
        viewerUserId: UUID,
        radiusMeters: Int,
        limit: Int,
        filters: DiscoveryFilters,
        cursor: GridCursorAnchor?
    ): GridQueryResult {
        receivedCursors += cursor
        return result
    }
}

private class StaticPresenceRepository(
    private val onlineIds: Set<UUID>
) : PresenceRepository {
    override suspend fun heartbeat(userId: UUID): PresenceState = PresenceState(online = true, expiresInSeconds = 300)

    override suspend fun onlineUserIds(userIds: Collection<UUID>): Set<UUID> =
        userIds.filterTo(mutableSetOf()) { it in onlineIds }
}

private class StaticGridViewerTrustRepository(
    private val ageVerifiedUserIds: Set<UUID>
) : GridViewerTrustRepository {
    override suspend fun isAgeVerified(userId: UUID): Boolean = userId in ageVerifiedUserIds
}

private class StaticMediaAssetUrlResolver : MediaAssetUrlResolver {
    override fun publicContentUrl(mediaAssetId: UUID): String = "https://media.example/$mediaAssetId"
}

private fun freeSubscriptionService(): SubscriptionService =
    SubscriptionService(StaticSubscriptionRepository())

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
