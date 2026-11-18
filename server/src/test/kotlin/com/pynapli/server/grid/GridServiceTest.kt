package com.pynapli.server.grid

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.presence.PresenceRepository
import com.pynapli.server.presence.PresenceService
import com.pynapli.server.presence.PresenceState
import com.pynapli.server.safety.HiddenProfileRecord
import com.pynapli.server.safety.HiddenProfileRepository
import com.pynapli.server.safety.HiddenProfileSummaryRecord
import com.pynapli.server.subscriptions.SubscriptionRecord
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.server.testsupport.StaticSubscriptionRepository
import com.pynapli.server.testsupport.denyingRateLimitService
import com.pynapli.shared.network.dto.GridQueryRequestDto
import com.pynapli.shared.network.dto.SubscriptionTier
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GridServiceTest {
    @Test
    fun queryGridRequiresViewerLocation() =
        runBlocking {
            val service =
                GridService(
                    StaticGridRepository(GridQueryResult(viewerHadLocation = false, cards = emptyList())),
                )

            val error =
                assertFailsWith<GridException> {
                    service.queryGrid(UUID.randomUUID(), GridQueryRequestDto())
                }

            assertEquals("LOCATION_REQUIRED", error.code)
        }

    @Test
    fun queryGridReturnsMinimalCards() =
        runBlocking {
            val targetId = UUID.randomUUID()
            val service =
                GridService(
                    StaticGridRepository(
                        GridQueryResult(
                            viewerHadLocation = true,
                            cards =
                                listOf(
                                    GridCardRecord(
                                        userId = targetId,
                                        displayName = "Alex",
                                        showDistance = true,
                                        distanceMeters = 1_240.0,
                                    ),
                                ),
                        ),
                    ),
                )

            val response = service.queryGrid(UUID.randomUUID(), GridQueryRequestDto())

            assertEquals(1, response.items.size)
            assertEquals(targetId.toString(), response.items.first().userId)
            assertEquals("Alex", response.items.first().displayName)
            // AUDIT-10: anything under 2 km reads as the coarse "1 km" bucket.
            assertEquals("1.0 km", response.items.first().distanceLabel)
            assertEquals(1_000, response.items.first().distanceMeters)
            assertEquals(false, response.items.first().online)
            assertNull(response.items.first().thumbnailUrl)
        }

    @Test
    fun queryGridHidesDistanceWhenTargetDoesNotShowDistance() =
        runBlocking {
            val service =
                GridService(
                    StaticGridRepository(
                        GridQueryResult(
                            viewerHadLocation = true,
                            cards =
                                listOf(
                                    GridCardRecord(
                                        userId = UUID.randomUUID(),
                                        displayName = "Alex",
                                        showDistance = false,
                                        distanceMeters = 300.0,
                                    ),
                                ),
                        ),
                    ),
                )

            val response = service.queryGrid(UUID.randomUUID(), GridQueryRequestDto())

            assertNull(response.items.first().distanceLabel)
            assertNull(response.items.first().distanceMeters)
        }

    @Test
    fun queryGridRemovesHiddenProfilesAndHiddenLinkedPartnerMini() =
        runBlocking {
            val viewerId = UUID.randomUUID()
            val visibleTarget = UUID.randomUUID()
            val hiddenTarget = UUID.randomUUID()
            val hiddenPartner = UUID.randomUUID()
            val service =
                GridService(
                    repository =
                        StaticGridRepository(
                            GridQueryResult(
                                viewerHadLocation = true,
                                cards =
                                    listOf(
                                        GridCardRecord(
                                            userId = hiddenTarget,
                                            displayName = "Hidden",
                                            showDistance = true,
                                            distanceMeters = 200.0,
                                        ),
                                        GridCardRecord(
                                            userId = visibleTarget,
                                            displayName = "Visible",
                                            showDistance = true,
                                            distanceMeters = 300.0,
                                            linkedPartnerUserId = hiddenPartner,
                                            linkedPartnerDisplayName = "Hidden partner",
                                        ),
                                    ),
                            ),
                        ),
                    hiddenProfileRepository =
                        StaticHiddenProfileRepository(
                            hiddenByUser = mapOf(viewerId to setOf(hiddenTarget, hiddenPartner)),
                        ),
                )

            val response = service.queryGrid(viewerId, GridQueryRequestDto())

            assertEquals(listOf(visibleTarget.toString()), response.items.map { it.userId })
            assertNull(response.items.single().linkedPartner)
        }

    @Test
    fun queryGridUsesPresenceForOnlineIndicator() =
        runBlocking {
            val viewerId = UUID.randomUUID()
            val targetId = UUID.randomUUID()
            val service =
                GridService(
                    repository =
                        StaticGridRepository(
                            GridQueryResult(
                                viewerHadLocation = true,
                                cards =
                                    listOf(
                                        GridCardRecord(
                                            userId = targetId,
                                            displayName = "Alex",
                                            showDistance = true,
                                            distanceMeters = 300.0,
                                        ),
                                    ),
                            ),
                        ),
                    presenceService = PresenceService(StaticPresenceRepository(setOf(targetId))),
                )

            val response = service.queryGrid(viewerId, GridQueryRequestDto())

            assertEquals(true, response.items.first().online)
        }

    @Test
    fun queryGridIncludesApprovedProfileThumbnailUrlWhenProjected() =
        runBlocking {
            val targetId = UUID.randomUUID()
            val mediaAssetId = UUID.randomUUID()
            val service =
                GridService(
                    repository =
                        StaticGridRepository(
                            GridQueryResult(
                                viewerHadLocation = true,
                                cards =
                                    listOf(
                                        GridCardRecord(
                                            userId = targetId,
                                            displayName = "Alex",
                                            thumbnailMediaAssetId = mediaAssetId,
                                            showDistance = true,
                                            distanceMeters = 300.0,
                                        ),
                                    ),
                            ),
                        ),
                    mediaAssetUrlResolver = StaticMediaAssetUrlResolver(),
                )

            val response = service.queryGrid(UUID.randomUUID(), GridQueryRequestDto())

            assertEquals("https://media.example/$mediaAssetId", response.items.first().thumbnailUrl)
        }

    @Test
    fun queryGridIncludesLinkedPartnerMiniWhenProjected() =
        runBlocking {
            val targetId = UUID.randomUUID()
            val partnerId = UUID.randomUUID()
            val partnerMediaAssetId = UUID.randomUUID()
            val service =
                GridService(
                    repository =
                        StaticGridRepository(
                            GridQueryResult(
                                viewerHadLocation = true,
                                cards =
                                    listOf(
                                        GridCardRecord(
                                            userId = targetId,
                                            displayName = "Maya",
                                            showDistance = true,
                                            distanceMeters = 300.0,
                                            linkedPartnerUserId = partnerId,
                                            linkedPartnerDisplayName = "Noa",
                                            linkedPartnerThumbnailMediaAssetId = partnerMediaAssetId,
                                        ),
                                    ),
                            ),
                        ),
                    mediaAssetUrlResolver = StaticMediaAssetUrlResolver(),
                )

            val response = service.queryGrid(UUID.randomUUID(), GridQueryRequestDto())

            val linkedPartner = response.items.first().linkedPartner
            assertEquals(partnerId.toString(), linkedPartner?.userId)
            assertEquals("Noa", linkedPartner?.displayName)
            assertEquals("https://media.example/$partnerMediaAssetId", linkedPartner?.thumbnailUrl)
        }

    @Test
    fun queryGridRejectsAdvancedFiltersForFreeUsers() =
        runBlocking {
            val viewerId = UUID.randomUUID()
            val service =
                GridService(
                    repository = StaticGridRepository(GridQueryResult(viewerHadLocation = true, cards = emptyList())),
                    viewerTrustRepository = StaticGridViewerTrustRepository(ageVerifiedUserIds = setOf(viewerId)),
                    subscriptionService = freeSubscriptionService(),
                )

            val error =
                assertFailsWith<GridException> {
                    service.queryGrid(viewerId, GridQueryRequestDto(lifestyleTags = listOf("soft_swap")))
                }

            assertEquals("ENTITLEMENT_REQUIRED", error.code)
        }

    @Test
    fun queryGridRejectsLifestyleFiltersForUnverifiedViewers() =
        runBlocking {
            val viewerId = UUID.randomUUID()
            val service =
                GridService(
                    repository = StaticGridRepository(GridQueryResult(viewerHadLocation = true, cards = emptyList())),
                    viewerTrustRepository = StaticGridViewerTrustRepository(ageVerifiedUserIds = emptySet()),
                    subscriptionService = paidSubscriptionService(viewerId, SubscriptionTier.SOCIAL),
                )

            val error =
                assertFailsWith<GridException> {
                    service.queryGrid(viewerId, GridQueryRequestDto(lifestyleTags = listOf("soft_swap")))
                }

            assertEquals("AGE_VERIFICATION_REQUIRED", error.code)
        }

    @Test
    fun queryGridAllowsSafetyAndBasicIntentFiltersForFreeUsers() =
        runBlocking {
            val viewerId = UUID.randomUUID()
            val targetId = UUID.randomUUID()
            val service =
                GridService(
                    repository =
                        StaticGridRepository(
                            GridQueryResult(
                                viewerHadLocation = true,
                                cards = listOf(GridCardRecord(targetId, "Alex", showDistance = true, distanceMeters = 300.0)),
                            ),
                        ),
                    presenceService = PresenceService(StaticPresenceRepository(setOf(targetId))),
                    subscriptionService = freeSubscriptionService(),
                )

            val response =
                service.queryGrid(
                    viewerId,
                    GridQueryRequestDto(
                        onlineOnly = true,
                        verifiedOnly = true,
                        facePhotoVerifiedOnly = true,
                        ageVerifiedOnly = true,
                        discoveryStyles = listOf("solo"),
                        intents = listOf("dating"),
                        logisticsTags = listOf("public_meet_first"),
                        tonightAvailableOnly = true,
                    ),
                )

            assertEquals(listOf(targetId.toString()), response.items.map { it.userId })
            assertEquals(false, response.advancedFiltersApplied)
        }

    @Test
    fun queryGridAllowsOnlineOnlyFilterForPaidUsers() =
        runBlocking {
            val viewerId = UUID.randomUUID()
            val onlineTarget = UUID.randomUUID()
            val offlineTarget = UUID.randomUUID()
            val service =
                GridService(
                    repository =
                        StaticGridRepository(
                            GridQueryResult(
                                viewerHadLocation = true,
                                cards =
                                    listOf(
                                        GridCardRecord(onlineTarget, "Online", showDistance = true, distanceMeters = 100.0),
                                        GridCardRecord(offlineTarget, "Offline", showDistance = true, distanceMeters = 200.0),
                                    ),
                            ),
                        ),
                    presenceService = PresenceService(StaticPresenceRepository(setOf(onlineTarget))),
                    subscriptionService = paidSubscriptionService(viewerId, SubscriptionTier.SOCIAL),
                )

            val response = service.queryGrid(viewerId, GridQueryRequestDto(onlineOnly = true))

            assertEquals(listOf(onlineTarget.toString()), response.items.map { it.userId })
            assertEquals(false, response.advancedFiltersApplied)
        }

    @Test
    fun queryGridOverFetchesToFillOnlineOnlyPage() =
        runBlocking {
            val viewerId = UUID.randomUUID()
            val repository = LimitAwareGridRepository(totalCards = 100)
            // Every 4th candidate is online (25%). Filling a 12-card online page therefore needs
            // ~48 candidates — far more than an un-over-fetched page of 12 would surface (~3 online).
            val onlineIds =
                (0 until 100 step 4)
                    .map { UUID.nameUUIDFromBytes("grid-limit-card-$it".toByteArray()) }
                    .toSet()
            val service =
                GridService(
                    repository = repository,
                    presenceService = PresenceService(StaticPresenceRepository(onlineIds)),
                    subscriptionService = paidSubscriptionService(viewerId, SubscriptionTier.SOCIAL),
                )

            val response = service.queryGrid(viewerId, GridQueryRequestDto(onlineOnly = true, limit = 12))

            // The page fills to the requested size; without over-fetch it would have been ~3.
            assertEquals(12, response.items.size)
            // Every returned card is online.
            assertEquals(
                emptyList(),
                response.items.map { UUID.fromString(it.userId) }.filterNot { it in onlineIds },
            )
            // The repository was asked for more than the page size (the over-fetch).
            assertTrue(repository.receivedLimits.first() > 12)
            // Pagination still advances (a next cursor is issued for the remaining online candidates).
            assertTrue(response.nextCursor != null)
        }

    @Test
    fun queryGridAppliesFreeTierGridVolumeLimit() =
        runBlocking {
            val viewerId = UUID.randomUUID()
            val repository = LimitAwareGridRepository(totalCards = 90)
            val service =
                GridService(
                    repository = repository,
                    subscriptionService = freeSubscriptionService(),
                )

            val response = service.queryGrid(viewerId, GridQueryRequestDto(limit = 120))

            assertEquals(listOf(60), repository.receivedLimits)
            assertEquals(60, response.items.size)
            assertNull(response.nextCursor)
            assertEquals(true, response.resultLimitReached)
            assertEquals(SubscriptionTier.FREE, response.resultLimitTier)
        }

    @Test
    fun queryGridCarriesSeenCountThroughPaidTierCursor() =
        runBlocking {
            val viewerId = UUID.randomUUID()
            val repository = LimitAwareGridRepository(totalCards = 220)
            val service =
                GridService(
                    repository = repository,
                    subscriptionService = paidSubscriptionService(viewerId, SubscriptionTier.SOCIAL),
                )

            val firstPage = service.queryGrid(viewerId, GridQueryRequestDto(limit = 90))
            val nextCursor = assertNotNull(firstPage.nextCursor)
            service.queryGrid(viewerId, GridQueryRequestDto(limit = 90, cursor = nextCursor))

            assertEquals(listOf(90, 90), repository.receivedLimits)
            assertEquals(90, firstPage.items.size)
            assertEquals(90, repository.receivedCursors[1]?.seenCount)
            assertEquals(false, firstPage.resultLimitReached)
        }

    @Test
    fun queryGridRejectsRateLimitedViewerBeforeRepositoryQuery() =
        runBlocking {
            val repository = LimitAwareGridRepository(totalCards = 10)
            val service =
                GridService(
                    repository = repository,
                    rateLimitService = denyingRateLimitService(),
                )

            val error =
                assertFailsWith<GridException> {
                    service.queryGrid(UUID.randomUUID(), GridQueryRequestDto())
                }

            assertEquals("RATE_LIMITED", error.code)
            assertEquals(emptyList(), repository.receivedLimits)
        }

    // L-07: a page of one card per request would let a client walk the Grid card by card; the
    // server enforces a minimum page size (the free tier's final short page is still allowed).
    @Test
    fun queryGridRaisesTinyPageRequestsToTheMinimumPageSize() =
        runBlocking {
            val repository = LimitAwareGridRepository(totalCards = 40)
            val service = GridService(repository)

            val first = service.queryGrid(UUID.randomUUID(), GridQueryRequestDto(limit = 1))
            service.queryGrid(UUID.randomUUID(), GridQueryRequestDto(limit = 0))
            service.queryGrid(UUID.randomUUID(), GridQueryRequestDto(limit = -5))

            assertEquals(listOf(12, 12, 12), repository.receivedLimits)
            assertEquals(12, first.items.size)
        }

    @Test
    fun queryGridAllowsAShortFinalPageUnderTheFreeTierDepthCap() =
        runBlocking {
            val viewerId = UUID.randomUUID()
            val repository = LimitAwareGridRepository(totalCards = 200)
            // Free tier: 60 results deep. Pages of 25 -> 25, 25, then a short final page of 10.
            val service = GridService(repository, subscriptionService = freeSubscriptionService())

            val first = service.queryGrid(viewerId, GridQueryRequestDto(limit = 25))
            val second = service.queryGrid(viewerId, GridQueryRequestDto(limit = 25, cursor = assertNotNull(first.nextCursor)))
            val third = service.queryGrid(viewerId, GridQueryRequestDto(limit = 25, cursor = assertNotNull(second.nextCursor)))

            assertEquals(listOf(25, 25, 10), repository.receivedLimits)
            assertEquals(10, third.items.size)
            assertNull(third.nextCursor)
            assertEquals(true, third.resultLimitReached)
        }

    @Test
    fun queryGridReturnsSignedCursorAndPassesDecodedAnchorToRepository() =
        runBlocking {
            val viewerId = UUID.randomUUID()
            val targetId = UUID.randomUUID()
            val anchor =
                GridCursorAnchor(
                    boostBucket = 1,
                    orderBucket = 1_240,
                    userId = targetId,
                )
            val repository =
                CapturingGridRepository(
                    GridQueryResult(
                        viewerHadLocation = true,
                        cards =
                            listOf(
                                GridCardRecord(
                                    userId = targetId,
                                    displayName = "Alex",
                                    showDistance = true,
                                    distanceMeters = 1_240.0,
                                    orderBucket = 1_240,
                                ),
                            ),
                        nextCursorAnchor = anchor,
                    ),
                )
            val service = GridService(repository)

            val firstPage = service.queryGrid(viewerId, GridQueryRequestDto(limit = 1))
            val nextCursor = assertNotNull(firstPage.nextCursor)
            service.queryGrid(viewerId, GridQueryRequestDto(limit = 1, cursor = nextCursor))

            assertEquals(listOf(null, anchor.copy(seenCount = 1)), repository.receivedCursors)
        }

    @Test
    fun queryGridRejectsCursorFromDifferentFilterScope() =
        runBlocking {
            val viewerId = UUID.randomUUID()
            val targetId = UUID.randomUUID()
            val service =
                GridService(
                    StaticGridRepository(
                        GridQueryResult(
                            viewerHadLocation = true,
                            cards =
                                listOf(
                                    GridCardRecord(
                                        userId = targetId,
                                        displayName = "Alex",
                                        showDistance = true,
                                        distanceMeters = 1_240.0,
                                        orderBucket = 1_240,
                                    ),
                                ),
                            nextCursorAnchor =
                                GridCursorAnchor(
                                    boostBucket = 1,
                                    orderBucket = 1_240,
                                    userId = targetId,
                                ),
                        ),
                    ),
                )

            val firstPage = service.queryGrid(viewerId, GridQueryRequestDto(limit = 1))
            val nextCursor = assertNotNull(firstPage.nextCursor)
            val error =
                assertFailsWith<GridException> {
                    service.queryGrid(viewerId, GridQueryRequestDto(limit = 24, cursor = nextCursor))
                }

            assertEquals("INVALID_GRID_CURSOR", error.code)
        }

    @Test
    fun queryGridRejectsTamperedCursor() =
        runBlocking {
            val viewerId = UUID.randomUUID()
            val targetId = UUID.randomUUID()
            val service =
                GridService(
                    StaticGridRepository(
                        GridQueryResult(
                            viewerHadLocation = true,
                            cards =
                                listOf(
                                    GridCardRecord(
                                        userId = targetId,
                                        displayName = "Alex",
                                        showDistance = true,
                                        distanceMeters = 1_240.0,
                                        orderBucket = 1_240,
                                    ),
                                ),
                            nextCursorAnchor =
                                GridCursorAnchor(
                                    boostBucket = 1,
                                    orderBucket = 1_240,
                                    userId = targetId,
                                ),
                        ),
                    ),
                )

            val firstPage = service.queryGrid(viewerId, GridQueryRequestDto(limit = 1))
            val issued = assertNotNull(firstPage.nextCursor)
            val tamperedCursor = issued.dropLast(1) + if (issued.last() == 'x') 'y' else 'x'
            val error =
                assertFailsWith<GridException> {
                    service.queryGrid(viewerId, GridQueryRequestDto(limit = 1, cursor = tamperedCursor))
                }

            assertEquals("INVALID_GRID_CURSOR", error.code)
        }

    // FILTERS_SPEC "Default deck / never-empty": an empty first page at the requested radius
    // widens once to the maximum radius so the viewer still sees nearby-ish people.
    @Test
    fun queryGridWidensToMaxRadiusWhenFirstPageEmpty() =
        runBlocking {
            val targetId = UUID.randomUUID()
            val repository =
                RadiusAwareGridRepository(
                    cardsAtMaxRadius =
                        listOf(GridCardRecord(targetId, "Far Alex", showDistance = true, distanceMeters = 80_000.0)),
                )
            val service = GridService(repository)

            val response = service.queryGrid(UUID.randomUUID(), GridQueryRequestDto(radiusMeters = 25_000))

            assertEquals(listOf(25_000, 100_000), repository.receivedRadii)
            assertEquals(listOf(targetId.toString()), response.items.map { it.userId })
        }

    @Test
    fun queryGridDoesNotPaginateAWidenedFallbackPage() =
        runBlocking {
            val targetId = UUID.randomUUID()
            val repository =
                RadiusAwareGridRepository(
                    cardsAtMaxRadius =
                        listOf(
                            GridCardRecord(
                                targetId,
                                "Far Alex",
                                showDistance = true,
                                distanceMeters = 80_000.0,
                                orderBucket = 80_000,
                            ),
                        ),
                    nextCursorAnchorAtMax =
                        GridCursorAnchor(boostBucket = 0, orderBucket = 80_000, userId = targetId),
                )
            val service = GridService(repository)

            val response = service.queryGrid(UUID.randomUUID(), GridQueryRequestDto(radiusMeters = 25_000))

            assertEquals(1, response.items.size)
            assertNull(response.nextCursor)
            assertEquals(false, response.resultLimitReached)
        }

    @Test
    fun queryGridDoesNotWidenWhenFirstPageHasCards() =
        runBlocking {
            val targetId = UUID.randomUUID()
            val repository =
                RadiusAwareGridRepository(
                    cardsAtMaxRadius =
                        listOf(GridCardRecord(targetId, "Near Alex", showDistance = true, distanceMeters = 500.0)),
                    minMatchRadiusMeters = 1_000,
                )
            val service = GridService(repository)

            val response = service.queryGrid(UUID.randomUUID(), GridQueryRequestDto(radiusMeters = 25_000))

            assertEquals(listOf(25_000), repository.receivedRadii)
            assertEquals(listOf(targetId.toString()), response.items.map { it.userId })
        }
}

private class StaticGridRepository(
    private val result: GridQueryResult,
) : GridRepository {
    override suspend fun queryNearby(
        viewerUserId: UUID,
        radiusMeters: Int,
        limit: Int,
        filters: DiscoveryFilters,
        cursor: GridCursorAnchor?,
    ): GridQueryResult = result
}

private class CapturingGridRepository(
    private val result: GridQueryResult,
) : GridRepository {
    val receivedCursors = mutableListOf<GridCursorAnchor?>()

    override suspend fun queryNearby(
        viewerUserId: UUID,
        radiusMeters: Int,
        limit: Int,
        filters: DiscoveryFilters,
        cursor: GridCursorAnchor?,
    ): GridQueryResult {
        receivedCursors += cursor
        return result
    }
}

// Returns matches only at/above `minMatchRadiusMeters` (defaults to the service max), so a
// narrow first page is empty and the never-empty widening re-query at the max radius fills it.
private class RadiusAwareGridRepository(
    private val cardsAtMaxRadius: List<GridCardRecord>,
    private val nextCursorAnchorAtMax: GridCursorAnchor? = null,
    private val minMatchRadiusMeters: Int = 100_000,
) : GridRepository {
    val receivedRadii = mutableListOf<Int>()

    override suspend fun queryNearby(
        viewerUserId: UUID,
        radiusMeters: Int,
        limit: Int,
        filters: DiscoveryFilters,
        cursor: GridCursorAnchor?,
    ): GridQueryResult {
        receivedRadii += radiusMeters
        return if (radiusMeters >= minMatchRadiusMeters) {
            GridQueryResult(viewerHadLocation = true, cards = cardsAtMaxRadius, nextCursorAnchor = nextCursorAnchorAtMax)
        } else {
            GridQueryResult(viewerHadLocation = true, cards = emptyList())
        }
    }
}

private class LimitAwareGridRepository(
    totalCards: Int,
) : GridRepository {
    val receivedLimits = mutableListOf<Int>()
    val receivedCursors = mutableListOf<GridCursorAnchor?>()

    private val cards =
        List(totalCards) { index ->
            GridCardRecord(
                userId = UUID.nameUUIDFromBytes("grid-limit-card-$index".toByteArray()),
                displayName = "Grid $index",
                showDistance = true,
                distanceMeters = index.toDouble(),
                orderBucket = index,
            )
        }

    override suspend fun queryNearby(
        viewerUserId: UUID,
        radiusMeters: Int,
        limit: Int,
        filters: DiscoveryFilters,
        cursor: GridCursorAnchor?,
    ): GridQueryResult {
        receivedLimits += limit
        receivedCursors += cursor
        val startIndex = cursor?.orderBucket?.plus(1) ?: 0
        val page = cards.drop(startIndex).take(limit)
        val hasMore = cards.size > startIndex + limit
        return GridQueryResult(
            viewerHadLocation = true,
            cards = page,
            nextCursorAnchor = if (hasMore && page.isNotEmpty()) page.last().toCursorAnchor() else null,
        )
    }
}

private class StaticPresenceRepository(
    private val onlineIds: Set<UUID>,
) : PresenceRepository {
    override suspend fun heartbeat(userId: UUID): PresenceState = PresenceState(online = true, expiresInSeconds = 300)

    override suspend fun onlineUserIds(userIds: Collection<UUID>): Set<UUID> = userIds.filterTo(mutableSetOf()) { it in onlineIds }
}

private class StaticGridViewerTrustRepository(
    private val ageVerifiedUserIds: Set<UUID>,
) : GridViewerTrustRepository {
    override suspend fun isAgeVerified(userId: UUID): Boolean = userId in ageVerifiedUserIds
}

private class StaticHiddenProfileRepository(
    private val hiddenByUser: Map<UUID, Set<UUID>>,
) : HiddenProfileRepository {
    override suspend fun hideProfile(
        hiderUserId: UUID,
        hiddenUserId: UUID,
        hideIdFactory: () -> UUID,
    ): HiddenProfileRecord = error("Not implemented")

    override suspend fun unhideProfile(
        hiderUserId: UUID,
        hiddenUserId: UUID,
    ): Boolean = false

    override suspend fun listHiddenProfiles(hiderUserId: UUID): List<HiddenProfileSummaryRecord> = emptyList()

    override suspend fun listHiddenProfileIds(hiderUserId: UUID): Set<UUID> = hiddenByUser[hiderUserId].orEmpty()
}

private class StaticMediaAssetUrlResolver : MediaAssetUrlResolver {
    override fun publicContentUrl(mediaAssetId: UUID): String = "https://media.example/$mediaAssetId"
}

private fun freeSubscriptionService(): SubscriptionService = SubscriptionService(StaticSubscriptionRepository())

private fun paidSubscriptionService(
    userId: UUID,
    tier: String,
): SubscriptionService =
    SubscriptionService(
        StaticSubscriptionRepository(
            own =
                mapOf(
                    userId to
                        SubscriptionRecord(
                            subscriptionId = UUID.randomUUID(),
                            userId = userId,
                            tier = tier,
                            status = "active",
                            currentPeriodStart = Instant.parse("2026-05-01T00:00:00Z"),
                            currentPeriodEnd = Instant.parse("2026-06-01T00:00:00Z"),
                        ),
                ),
        ),
    )
