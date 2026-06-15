package com.pynapli.server.profile

import com.pynapli.server.activity.ActivityEventRecord
import com.pynapli.server.activity.ActivityFeedQuery
import com.pynapli.server.activity.ActivityRepository
import com.pynapli.server.activity.ActivityService
import com.pynapli.server.activity.ActivitySummaryRecord
import com.pynapli.server.activity.AlbumRequestEventRecord
import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.presence.PresenceRepository
import com.pynapli.server.presence.PresenceService
import com.pynapli.server.presence.PresenceState
import com.pynapli.server.safety.AccountRiskStateRecord
import com.pynapli.server.safety.RateLimitCounter
import com.pynapli.server.safety.RateLimitRepository
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RiskPolicyService
import com.pynapli.shared.network.dto.UpdateDiscoverySettingsRequestDto
import com.pynapli.shared.network.dto.UpdateProfileRequestDto
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class ProfileServiceTest {
    private val clock = Clock.fixed(Instant.parse("2026-04-24T00:00:00Z"), ZoneOffset.UTC)

    @Test
    fun updateProfileStoresBasicOnboardingFields() =
        runBlocking {
            val userId = UUID.randomUUID()
            val service = ProfileService(InMemoryProfileRepository(userId), clock)

            val me =
                service.updateProfile(
                    userId,
                    UpdateProfileRequestDto(
                        displayName = " Alex ",
                        birthdate = "1990-01-15",
                        gender = "Man",
                        orientation = "Bisexual",
                        relationshipContext = "Single",
                        discoveryStyle = "solo",
                    ),
                )

            assertEquals("Alex", me.profile.displayName)
            assertEquals(36, me.profile.displayAge)
            assertEquals("man", me.profile.gender)
            assertEquals("solo", me.profile.discoveryStyle)
            assertEquals(100, me.profile.profileCompletenessScore)
        }

    @Test
    fun updateProfileRejectsUnderageBirthdate() =
        runBlocking {
            val userId = UUID.randomUUID()
            val service = ProfileService(InMemoryProfileRepository(userId), clock)

            val error =
                assertFailsWith<ProfileException> {
                    service.updateProfile(userId, UpdateProfileRequestDto(birthdate = "2010-01-01"))
                }

            assertEquals("AGE_RESTRICTED", error.code)
        }

    @Test
    fun getMeReturnsDefaultProfileAndDiscoverySettings() =
        runBlocking {
            val userId = UUID.randomUUID()
            val service = ProfileService(InMemoryProfileRepository(userId), clock)

            val me = service.getMe(userId)

            assertNotNull(me.profile)
            assertEquals("social", me.profile.discoveryStyle)
            assertEquals(true, me.discoverySettings.gridVisible)
            assertEquals(true, me.discoverySettings.allowGridMessages)
        }

    @Test
    fun updateDiscoverySettingsStoresVisibilityAndDistanceToggles() =
        runBlocking {
            val userId = UUID.randomUUID()
            val service = ProfileService(InMemoryProfileRepository(userId), clock)

            val me =
                service.updateDiscoverySettings(
                    userId,
                    UpdateDiscoverySettingsRequestDto(
                        gridVisible = false,
                        swipeVisible = true,
                        showDistance = false,
                    ),
                )

            assertEquals(false, me.discoverySettings.gridVisible)
            assertEquals(true, me.discoverySettings.swipeVisible)
            assertEquals(false, me.discoverySettings.showDistance)
        }

    @Test
    fun updateDiscoverySettingsStoresActivityControls() =
        runBlocking {
            val userId = UUID.randomUUID()
            val service = ProfileService(InMemoryProfileRepository(userId), clock)

            val me =
                service.updateDiscoverySettings(
                    userId,
                    UpdateDiscoverySettingsRequestDto(
                        showInViewedLists = false,
                        allowGridMessages = true,
                        gridMessagePolicy = "matches_only",
                        allowFlirts = false,
                        allowIntroNotes = false,
                        allowAlbumRequests = false,
                    ),
                )

            assertEquals(false, me.discoverySettings.showInViewedLists)
            assertEquals(true, me.discoverySettings.allowGridMessages)
            assertEquals("matches_only", me.discoverySettings.gridMessagePolicy)
            assertEquals(false, me.discoverySettings.allowFlirts)
            assertEquals(false, me.discoverySettings.allowIntroNotes)
            assertEquals(false, me.discoverySettings.allowAlbumRequests)
        }

    @Test
    fun updateDiscoverySettingsStoresPartnerVisibility() =
        runBlocking {
            val userId = UUID.randomUUID()
            val service = ProfileService(InMemoryProfileRepository(userId), clock)

            // Default before any write.
            assertEquals("public", service.getMe(userId).discoverySettings.partnerVisibility)

            val me =
                service.updateDiscoverySettings(
                    userId,
                    UpdateDiscoverySettingsRequestDto(partnerVisibility = "Matches_Only"),
                )

            assertEquals("matches_only", me.discoverySettings.partnerVisibility)
        }

    @Test
    fun updateDiscoverySettingsRejectsUnsupportedPartnerVisibility() =
        runBlocking {
            val userId = UUID.randomUUID()
            val service = ProfileService(InMemoryProfileRepository(userId), clock)

            val error =
                assertFailsWith<ProfileException> {
                    service.updateDiscoverySettings(
                        userId,
                        UpdateDiscoverySettingsRequestDto(partnerVisibility = "friends"),
                    )
                }

            assertEquals("INVALID_PARTNER_VISIBILITY", error.code)
        }

    @Test
    fun updateDiscoverySettingsRejectsUnsupportedGridMessagePolicy() =
        runBlocking {
            val userId = UUID.randomUUID()
            val service = ProfileService(InMemoryProfileRepository(userId), clock)

            val error =
                assertFailsWith<ProfileException> {
                    service.updateDiscoverySettings(
                        userId,
                        UpdateDiscoverySettingsRequestDto(gridMessagePolicy = "verified_only"),
                    )
                }

            assertEquals("INVALID_GRID_MESSAGE_POLICY", error.code)
        }

    @Test
    fun getPublicProfileReturnsOnlyPublicFields() =
        runBlocking {
            val userId = UUID.randomUUID()
            val service = ProfileService(InMemoryProfileRepository(userId), clock)

            service.updateProfile(
                userId,
                UpdateProfileRequestDto(
                    displayName = "Maya",
                    birthdate = "1991-02-11",
                    gender = "Woman",
                    orientation = "Pansexual",
                    relationshipContext = "Linked",
                    discoveryStyle = "both",
                ),
            )

            val profile = service.getPublicProfile(UUID.randomUUID(), userId)

            assertEquals(userId.toString(), profile.userId)
            assertEquals("Maya", profile.displayName)
            assertEquals(35, profile.displayAge)
            assertEquals("linked", profile.relationshipContext)
            assertEquals("both", profile.discoveryStyle)
            assertEquals("1.2 km", profile.distanceLabel)
            assertEquals(1_200, profile.distanceMeters)
            assertEquals(true, profile.allowGridMessages)
            assertEquals(true, profile.allowFlirts)
        }

    @Test
    fun getPublicProfileIncludesLinkedPartnerMiniWhenProjected() =
        runBlocking {
            val userId = UUID.randomUUID()
            val partnerId = UUID.randomUUID()
            val partnerMediaAssetId = UUID.randomUUID()
            val service =
                ProfileService(
                    repository =
                        InMemoryProfileRepository(
                            userId = userId,
                            linkedPartnerUserId = partnerId,
                            linkedPartnerThumbnailMediaAssetId = partnerMediaAssetId,
                        ),
                    clock = clock,
                    mediaAssetUrlResolver = StaticMediaAssetUrlResolver(),
                )

            val profile = service.getPublicProfile(UUID.randomUUID(), userId)

            assertEquals(partnerId.toString(), profile.linkedPartner?.userId)
            assertEquals("Noa", profile.linkedPartner?.displayName)
            assertEquals("https://media.example/$partnerMediaAssetId", profile.linkedPartner?.thumbnailUrl)
        }

    @Test
    fun getPublicProfileRecordsProfileViewActivity() =
        runBlocking {
            val userId = UUID.randomUUID()
            val viewerUserId = UUID.randomUUID()
            val activityRepository = InMemoryActivityRepository()
            val service =
                ProfileService(
                    repository = InMemoryProfileRepository(userId),
                    clock = clock,
                    activityService = ActivityService(activityRepository),
                )

            service.getPublicProfile(viewerUserId, userId)

            assertEquals(listOf(viewerUserId to userId), activityRepository.profileViews)
        }

    @Test
    fun getPublicProfileHydratesOnlineFromPresence() =
        runBlocking {
            val userId = UUID.randomUUID()
            val service =
                ProfileService(
                    repository = InMemoryProfileRepository(userId),
                    clock = clock,
                    presenceService = PresenceService(StaticPresenceRepository(setOf(userId))),
                )

            val profile = service.getPublicProfile(UUID.randomUUID(), userId)

            assertEquals(true, profile.online)
        }

    @Test
    fun getPublicProfileReportsOfflineTargetAndDefaultsWithoutPresence() =
        runBlocking {
            val userId = UUID.randomUUID()
            // Presence wired, but the target is not in the online set.
            val withPresence =
                ProfileService(
                    repository = InMemoryProfileRepository(userId),
                    clock = clock,
                    presenceService = PresenceService(StaticPresenceRepository(emptySet())),
                )
            assertEquals(false, withPresence.getPublicProfile(UUID.randomUUID(), userId).online)

            // No presence service at all -> defaults offline rather than failing the fetch.
            val withoutPresence = ProfileService(InMemoryProfileRepository(userId), clock)
            assertEquals(false, withoutPresence.getPublicProfile(UUID.randomUUID(), userId).online)
        }

    @Test
    fun getPublicProfileHidesMissingOrUnavailableProfiles() =
        runBlocking {
            val service = ProfileService(InMemoryProfileRepository(UUID.randomUUID()), clock)

            val error =
                assertFailsWith<ProfileException> {
                    service.getPublicProfile(UUID.randomUUID(), UUID.randomUUID())
                }

            assertEquals("PROFILE_NOT_FOUND", error.code)
        }

    @Test
    fun getPublicProfileHidesRiskRestrictedTargetBeforeRecordingView() =
        runBlocking {
            val targetUserId = UUID.randomUUID()
            val viewerUserId = UUID.randomUUID()
            val activityRepository = InMemoryActivityRepository()
            val service =
                ProfileService(
                    repository = InMemoryProfileRepository(targetUserId),
                    clock = clock,
                    activityService = ActivityService(activityRepository),
                    riskPolicyService = restrictedRiskPolicy(),
                )

            val error =
                assertFailsWith<ProfileException> {
                    service.getPublicProfile(viewerUserId, targetUserId)
                }

            assertEquals("PROFILE_NOT_FOUND", error.code)
            assertEquals(emptyList(), activityRepository.profileViews)
        }

    @Test
    fun updateProfileStoresBioTagsAndSelfDescribe() =
        runBlocking {
            val userId = UUID.randomUUID()
            val service = ProfileService(InMemoryProfileRepository(userId, ageVerified = true), clock)

            val me =
                service.updateProfile(
                    userId,
                    UpdateProfileRequestDto(
                        displayName = "Alex",
                        bio = "  Friendly couple-adjacent human. ",
                        gender = "Self_Describe",
                        genderDetail = " Genderfluid ",
                        orientation = "Heteroflexible",
                        lifestyleTags = listOf("Soft_Swap", "enm", "enm"),
                        logisticsTags = listOf("hosts"),
                        intents = listOf("events", "chat_first"),
                    ),
                )

            assertEquals("Friendly couple-adjacent human.", me.profile.bio)
            assertEquals("self_describe", me.profile.gender)
            assertEquals("Genderfluid", me.profile.genderDetail)
            assertEquals("heteroflexible", me.profile.orientation)
            assertEquals(null, me.profile.orientationDetail)
            assertEquals(listOf("soft_swap", "enm"), me.profile.lifestyleTags)
            assertEquals(listOf("hosts"), me.profile.logisticsTags)
            assertEquals(listOf("events", "chat_first"), me.profile.intents)
        }

    @Test
    fun updateProfileRejectsUnknownGenderKeyAndTag() =
        runBlocking {
            val userId = UUID.randomUUID()
            val service = ProfileService(InMemoryProfileRepository(userId, ageVerified = true), clock)

            assertEquals(
                "INVALID_GENDER",
                assertFailsWith<ProfileException> {
                    service.updateProfile(userId, UpdateProfileRequestDto(gender = "couple"))
                }.code,
            )
            assertEquals(
                "INVALID_ORIENTATION",
                assertFailsWith<ProfileException> {
                    service.updateProfile(userId, UpdateProfileRequestDto(orientation = "open"))
                }.code,
            )
            assertEquals(
                "INVALID_PROFILE_TAG",
                assertFailsWith<ProfileException> {
                    service.updateProfile(userId, UpdateProfileRequestDto(lifestyleTags = listOf("polycule")))
                }.code,
            )
        }

    @Test
    fun updateProfileRequiresSelfDescribeLabelAndDropsStrayDetail() =
        runBlocking {
            val userId = UUID.randomUUID()
            val service = ProfileService(InMemoryProfileRepository(userId), clock)

            assertEquals(
                "INVALID_GENDER",
                assertFailsWith<ProfileException> {
                    service.updateProfile(userId, UpdateProfileRequestDto(gender = "self_describe", genderDetail = "  "))
                }.code,
            )

            // A structured key silently drops a stray detail so filters stay structured.
            val me = service.updateProfile(userId, UpdateProfileRequestDto(gender = "man", genderDetail = "ignored"))
            assertEquals("man", me.profile.gender)
            assertEquals(null, me.profile.genderDetail)
        }

    @Test
    fun neighborhoodRoundTripsThroughUpdateAndPublicProfileWithPartialSemantics() =
        runBlocking {
            val userId = UUID.randomUUID()
            val service = ProfileService(InMemoryProfileRepository(userId), clock)

            // Set it; it surfaces on the public profile (the peek meta source).
            service.updateProfile(userId, UpdateProfileRequestDto(displayName = "Alex", neighborhood = "  Mission  "))
            assertEquals("Mission", service.getPublicProfile(UUID.randomUUID(), userId).neighborhood)
            assertEquals("Mission", service.getMe(userId).profile.neighborhood)

            // Omitted (null) leaves it unchanged.
            service.updateProfile(userId, UpdateProfileRequestDto(displayName = "Alex"))
            assertEquals("Mission", service.getMe(userId).profile.neighborhood)

            // Explicit empty clears it.
            service.updateProfile(userId, UpdateProfileRequestDto(neighborhood = "  "))
            assertEquals(null, service.getMe(userId).profile.neighborhood)
        }

    @Test
    fun updateProfileRejectsOverlongNeighborhood() =
        runBlocking {
            val userId = UUID.randomUUID()
            val service = ProfileService(InMemoryProfileRepository(userId), clock)

            val error =
                assertFailsWith<ProfileException> {
                    service.updateProfile(userId, UpdateProfileRequestDto(neighborhood = "x".repeat(51)))
                }

            assertEquals("INVALID_NEIGHBORHOOD", error.code)
        }

    @Test
    fun updateProfileRejectsOverlongBio() =
        runBlocking {
            val userId = UUID.randomUUID()
            val service = ProfileService(InMemoryProfileRepository(userId), clock)

            val error =
                assertFailsWith<ProfileException> {
                    service.updateProfile(userId, UpdateProfileRequestDto(bio = "x".repeat(501)))
                }

            assertEquals("INVALID_BIO", error.code)
        }

    @Test
    fun lifestyleTagsRequireAgeVerification() =
        runBlocking {
            val userId = UUID.randomUUID()
            val unverified = ProfileService(InMemoryProfileRepository(userId), clock)

            val error =
                assertFailsWith<ProfileException> {
                    unverified.updateProfile(userId, UpdateProfileRequestDto(lifestyleTags = listOf("enm")))
                }
            assertEquals("AGE_VERIFICATION_REQUIRED", error.code)

            // Logistics/intents are not sensitivity-gated.
            val me = unverified.updateProfile(userId, UpdateProfileRequestDto(logisticsTags = listOf("hosts"), intents = listOf("events")))
            assertEquals(listOf("hosts"), me.profile.logisticsTags)
        }

    @Test
    fun bioScannerRejectionBlocksTheSaveBeforeStorage() =
        runBlocking {
            val userId = UUID.randomUUID()
            val repository = InMemoryProfileRepository(userId)
            val service =
                ProfileService(
                    repository = repository,
                    clock = clock,
                    bioScanner = { ProfileBioScanner.Decision.Reject },
                )

            val error =
                assertFailsWith<ProfileException> {
                    service.updateProfile(userId, UpdateProfileRequestDto(bio = "anything"))
                }

            assertEquals("BIO_REJECTED", error.code)
            assertEquals(0, repository.updateProfileCalls)
        }

    @Test
    fun profileMutationsAreRejectedWhenRateLimited() =
        runBlocking {
            val userId = UUID.randomUUID()
            val repository = InMemoryProfileRepository(userId)
            val service =
                ProfileService(
                    repository = repository,
                    clock = clock,
                    rateLimitService = denyingRateLimitService(),
                )

            // Both mutation endpoints share the PROFILE_UPDATE budget and reject before storage.
            assertEquals(
                "RATE_LIMITED",
                assertFailsWith<ProfileException> {
                    service.updateProfile(userId, UpdateProfileRequestDto(displayName = "Alex"))
                }.code,
            )
            assertEquals(
                "RATE_LIMITED",
                assertFailsWith<ProfileException> {
                    service.updateDiscoverySettings(userId, UpdateDiscoverySettingsRequestDto(gridVisible = false))
                }.code,
            )
            assertEquals(0, repository.updateProfileCalls)
        }

    @Test
    fun omittedBioAndTagsLeaveStoredValuesUnchangedWhileEmptyClears() =
        runBlocking {
            val userId = UUID.randomUUID()
            val service = ProfileService(InMemoryProfileRepository(userId, ageVerified = true), clock)
            service.updateProfile(
                userId,
                UpdateProfileRequestDto(bio = "Keep me", lifestyleTags = listOf("enm"), intents = listOf("events")),
            )

            // Omitted fields (null) leave the stored values alone.
            val unchanged = service.updateProfile(userId, UpdateProfileRequestDto(displayName = "Alex"))
            assertEquals("Keep me", unchanged.profile.bio)
            assertEquals(listOf("enm"), unchanged.profile.lifestyleTags)
            assertEquals(listOf("events"), unchanged.profile.intents)

            // Explicitly empty values clear them.
            val cleared = service.updateProfile(userId, UpdateProfileRequestDto(bio = " ", lifestyleTags = emptyList()))
            assertEquals(null, cleared.profile.bio)
            assertEquals(emptyList(), cleared.profile.lifestyleTags)
            assertEquals(listOf("events"), cleared.profile.intents)
        }
}

private class InMemoryActivityRepository : ActivityRepository {
    val profileViews = mutableListOf<Pair<UUID, UUID>>()

    override suspend fun recordProfileView(
        viewerUserId: UUID,
        targetUserId: UUID,
    ): Boolean {
        profileViews += viewerUserId to targetUserId
        return true
    }

    override suspend fun recordFlirt(
        senderUserId: UUID,
        targetUserId: UUID,
        source: String?,
    ): Boolean = true

    override suspend fun recordAlbumRequest(
        requesterUserId: UUID,
        targetUserId: UUID,
    ): Boolean = true

    override suspend fun recordSwipeInterest(
        actorUserId: UUID,
        targetUserId: UUID,
        eventType: String,
    ): Boolean = true

    override suspend fun recordMatch(
        recipientUserId: UUID,
        actorUserId: UUID,
        matchId: UUID,
    ): Boolean = true

    override suspend fun recordPartnerChatInvite(
        inviterUserId: UUID,
        inviteeUserId: UUID,
        conversationId: UUID,
    ): Boolean = true

    override suspend fun getAlbumRequestEvent(
        recipientUserId: UUID,
        eventId: UUID,
    ): AlbumRequestEventRecord? = null

    override suspend fun listActivity(query: ActivityFeedQuery): List<ActivityEventRecord> = emptyList()

    override suspend fun getActivitySummary(recipientUserId: UUID): ActivitySummaryRecord =
        ActivitySummaryRecord(
            profileViewCount = 0,
            flirtCount = 0,
            likeCount = 0,
            matchCount = 0,
            albumRequestCount = 0,
            partnerInviteCount = 0,
            latestBadgeOccurredAt = null,
        )

    override suspend fun markActivityEventsSeen(
        recipientUserId: UUID,
        eventIds: Set<UUID>,
    ): Int = 0

    override suspend fun dismissActivityEvent(
        recipientUserId: UUID,
        eventId: UUID,
    ): Boolean = false
}

private class InMemoryProfileRepository(
    userId: UUID,
    private val linkedPartnerUserId: UUID? = null,
    private val linkedPartnerDisplayName: String? = "Noa",
    private val linkedPartnerThumbnailMediaAssetId: UUID? = null,
    ageVerified: Boolean = false,
) : ProfileRepository {
    var updateProfileCalls = 0
        private set

    private var record =
        MeRecord(
            userId = userId,
            userStatus = "active",
            profile =
                ProfileRecord(
                    userId = userId,
                    displayName = null,
                    birthdate = null,
                    displayAge = null,
                    gender = null,
                    orientation = null,
                    relationshipContext = null,
                    discoveryStyle = "social",
                    profileCompletenessScore = 0,
                    moderationStatus = "pending",
                    visibilityStatus = "visible",
                    ageVerified = ageVerified,
                ),
            discoverySettings =
                DiscoverySettingsRecord(
                    gridVisible = true,
                    swipeVisible = true,
                    eventsVisible = true,
                    showDistance = true,
                    showInViewedLists = true,
                    incognito = false,
                    allowGridMessages = true,
                    gridMessagePolicy = "everyone",
                    allowFlirts = true,
                    allowIntroNotes = true,
                    allowAlbumRequests = true,
                    soloEnabled = true,
                    togetherEnabled = false,
                    socialEnabled = true,
                    visibleWhileTraveling = true,
                    sharePsyncSoloMatches = false,
                ),
        )

    override suspend fun getMe(userId: UUID): MeRecord? = record.takeIf { it.userId == userId }

    override suspend fun getPublicProfile(
        viewerUserId: UUID,
        targetUserId: UUID,
    ): PublicProfileRecord? =
        record
            .takeIf {
                it.userId == targetUserId &&
                    it.userStatus == "active" &&
                    it.profile.visibilityStatus == "visible" &&
                    it.discoverySettings.gridVisible &&
                    !it.discoverySettings.incognito
            }?.let {
                PublicProfileRecord(
                    userId = it.userId,
                    displayName = it.profile.displayName ?: "Pynapli User",
                    profilePhotoMediaAssetId = null,
                    linkedPartnerUserId = linkedPartnerUserId,
                    linkedPartnerDisplayName = linkedPartnerDisplayName,
                    linkedPartnerThumbnailMediaAssetId = linkedPartnerThumbnailMediaAssetId,
                    displayAge = it.profile.displayAge,
                    neighborhood = it.profile.neighborhood,
                    gender = it.profile.gender,
                    orientation = it.profile.orientation,
                    relationshipContext = it.profile.relationshipContext,
                    discoveryStyle = it.profile.discoveryStyle,
                    distanceMeters = 1_240.0.takeIf { _ -> it.discoverySettings.showDistance },
                    privateAlbumAccessStatus = "none",
                    allowGridMessages = it.discoverySettings.allowGridMessages,
                    allowFlirts = it.discoverySettings.allowFlirts,
                    allowAlbumRequests = it.discoverySettings.allowAlbumRequests,
                )
            }

    override suspend fun updateProfile(
        userId: UUID,
        update: ProfileUpdate,
    ): MeRecord? {
        if (record.userId != userId) return null
        updateProfileCalls += 1
        record =
            record.copy(
                profile =
                    record.profile.copy(
                        displayName = update.displayName,
                        birthdate = update.birthdate,
                        displayAge = update.displayAge,
                        gender = update.gender,
                        genderDetail = update.genderDetail,
                        orientation = update.orientation,
                        orientationDetail = update.orientationDetail,
                        relationshipContext = update.relationshipContext,
                        discoveryStyle = update.discoveryStyle ?: record.profile.discoveryStyle,
                        // Mirrors the JDBC CASE/COALESCE semantics for the late-added fields.
                        bio = if (update.bioProvided) update.bio else record.profile.bio,
                        neighborhood = if (update.neighborhoodProvided) update.neighborhood else record.profile.neighborhood,
                        lifestyleTags = update.lifestyleTags ?: record.profile.lifestyleTags,
                        logisticsTags = update.logisticsTags ?: record.profile.logisticsTags,
                        intents = update.intents ?: record.profile.intents,
                        profileCompletenessScore = update.profileCompletenessScore,
                    ),
            )
        return record
    }

    override suspend fun updateDiscoverySettings(
        userId: UUID,
        update: DiscoverySettingsUpdate,
    ): MeRecord? {
        if (record.userId != userId) return null
        record =
            record.copy(
                discoverySettings =
                    record.discoverySettings.copy(
                        gridVisible = update.gridVisible ?: record.discoverySettings.gridVisible,
                        swipeVisible = update.swipeVisible ?: record.discoverySettings.swipeVisible,
                        showDistance = update.showDistance ?: record.discoverySettings.showDistance,
                        showInViewedLists = update.showInViewedLists ?: record.discoverySettings.showInViewedLists,
                        allowGridMessages = update.allowGridMessages ?: record.discoverySettings.allowGridMessages,
                        gridMessagePolicy = update.gridMessagePolicy ?: record.discoverySettings.gridMessagePolicy,
                        allowFlirts = update.allowFlirts ?: record.discoverySettings.allowFlirts,
                        allowIntroNotes = update.allowIntroNotes ?: record.discoverySettings.allowIntroNotes,
                        allowAlbumRequests = update.allowAlbumRequests ?: record.discoverySettings.allowAlbumRequests,
                        partnerVisibility = update.partnerVisibility ?: record.discoverySettings.partnerVisibility,
                    ),
            )
        return record
    }
}

private class StaticMediaAssetUrlResolver : MediaAssetUrlResolver {
    override fun publicContentUrl(mediaAssetId: UUID): String = "https://media.example/$mediaAssetId"
}

private class StaticPresenceRepository(
    private val onlineIds: Set<UUID>,
) : PresenceRepository {
    override suspend fun heartbeat(userId: UUID): PresenceState = PresenceState(online = true, expiresInSeconds = 300)

    override suspend fun onlineUserIds(userIds: Collection<UUID>): Set<UUID> = userIds.filterTo(mutableSetOf()) { it in onlineIds }
}

// Always over the limit: increment reports a count far above any policy cap, so check().allowed is false.
private fun denyingRateLimitService(): RateLimitService =
    RateLimitService(
        object : RateLimitRepository {
            override suspend fun increment(
                key: String,
                windowSeconds: Long,
            ): RateLimitCounter = RateLimitCounter(count = Long.MAX_VALUE, ttlSeconds = windowSeconds)
        },
    )

private fun restrictedRiskPolicy(tier: String = "quarantine"): RiskPolicyService =
    RiskPolicyService { userId ->
        AccountRiskStateRecord(
            userId = userId,
            score = 70,
            tier = tier,
            lastEventAt = Instant.parse("2026-04-24T18:00:00Z"),
            lastReviewedAt = null,
            updatedAt = Instant.parse("2026-04-24T18:00:00Z"),
        )
    }
