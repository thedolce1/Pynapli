package com.pynapli.server.profile

import com.pynapli.server.activity.AlbumRequestEventRecord
import com.pynapli.server.activity.ActivityEventRecord
import com.pynapli.server.activity.ActivityRepository
import com.pynapli.server.activity.ActivityService
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
    fun updateProfileStoresBasicOnboardingFields() = runBlocking {
        val userId = UUID.randomUUID()
        val service = ProfileService(InMemoryProfileRepository(userId), clock)

        val me = service.updateProfile(
            userId,
            UpdateProfileRequestDto(
                displayName = " Alex ",
                birthdate = "1990-01-15",
                gender = "Man",
                orientation = "Bi",
                relationshipContext = "Single",
                discoveryStyle = "solo"
            )
        )

        assertEquals("Alex", me.profile.displayName)
        assertEquals(36, me.profile.displayAge)
        assertEquals("man", me.profile.gender)
        assertEquals("solo", me.profile.discoveryStyle)
        assertEquals(100, me.profile.profileCompletenessScore)
    }

    @Test
    fun updateProfileRejectsUnderageBirthdate() = runBlocking {
        val userId = UUID.randomUUID()
        val service = ProfileService(InMemoryProfileRepository(userId), clock)

        val error = assertFailsWith<ProfileException> {
            service.updateProfile(userId, UpdateProfileRequestDto(birthdate = "2010-01-01"))
        }

        assertEquals("AGE_RESTRICTED", error.code)
    }

    @Test
    fun getMeReturnsDefaultProfileAndDiscoverySettings() = runBlocking {
        val userId = UUID.randomUUID()
        val service = ProfileService(InMemoryProfileRepository(userId), clock)

        val me = service.getMe(userId)

        assertNotNull(me.profile)
        assertEquals("social", me.profile.discoveryStyle)
        assertEquals(true, me.discoverySettings.gridVisible)
        assertEquals(true, me.discoverySettings.allowGridMessages)
    }

    @Test
    fun getPublicProfileReturnsOnlyPublicFields() = runBlocking {
        val userId = UUID.randomUUID()
        val service = ProfileService(InMemoryProfileRepository(userId), clock)

        service.updateProfile(
            userId,
            UpdateProfileRequestDto(
                displayName = "Maya",
                birthdate = "1991-02-11",
                gender = "Woman",
                orientation = "Open",
                relationshipContext = "Linked",
                discoveryStyle = "both"
            )
        )

        val profile = service.getPublicProfile(UUID.randomUUID(), userId)

        assertEquals(userId.toString(), profile.userId)
        assertEquals("Maya", profile.displayName)
        assertEquals(35, profile.displayAge)
        assertEquals("linked", profile.relationshipContext)
        assertEquals("both", profile.discoveryStyle)
        assertEquals("1.2 km", profile.distanceLabel)
        assertEquals(true, profile.allowGridMessages)
        assertEquals(true, profile.allowFlirts)
    }

    @Test
    fun getPublicProfileRecordsProfileViewActivity() = runBlocking {
        val userId = UUID.randomUUID()
        val viewerUserId = UUID.randomUUID()
        val activityRepository = InMemoryActivityRepository()
        val service = ProfileService(
            repository = InMemoryProfileRepository(userId),
            clock = clock,
            activityService = ActivityService(activityRepository)
        )

        service.getPublicProfile(viewerUserId, userId)

        assertEquals(listOf(viewerUserId to userId), activityRepository.profileViews)
    }

    @Test
    fun getPublicProfileHidesMissingOrUnavailableProfiles() = runBlocking {
        val service = ProfileService(InMemoryProfileRepository(UUID.randomUUID()), clock)

        val error = assertFailsWith<ProfileException> {
            service.getPublicProfile(UUID.randomUUID(), UUID.randomUUID())
        }

        assertEquals("PROFILE_NOT_FOUND", error.code)
    }
}

private class InMemoryActivityRepository : ActivityRepository {
    val profileViews = mutableListOf<Pair<UUID, UUID>>()

    override suspend fun recordProfileView(viewerUserId: UUID, targetUserId: UUID) {
        profileViews += viewerUserId to targetUserId
    }

    override suspend fun recordFlirt(senderUserId: UUID, targetUserId: UUID): Boolean = true

    override suspend fun recordAlbumRequest(requesterUserId: UUID, targetUserId: UUID): Boolean = true

    override suspend fun getAlbumRequestEvent(recipientUserId: UUID, eventId: UUID): AlbumRequestEventRecord? = null

    override suspend fun listActivity(recipientUserId: UUID, limit: Int): List<ActivityEventRecord> = emptyList()
}

private class InMemoryProfileRepository(userId: UUID) : ProfileRepository {
    private var record = MeRecord(
        userId = userId,
        userStatus = "active",
        profile = ProfileRecord(
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
            visibilityStatus = "visible"
        ),
        discoverySettings = DiscoverySettingsRecord(
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
            socialEnabled = true
        )
    )

    override suspend fun getMe(userId: UUID): MeRecord? =
        record.takeIf { it.userId == userId }

    override suspend fun getPublicProfile(viewerUserId: UUID, targetUserId: UUID): PublicProfileRecord? =
        record.takeIf {
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
                displayAge = it.profile.displayAge,
                gender = it.profile.gender,
                orientation = it.profile.orientation,
                relationshipContext = it.profile.relationshipContext,
                discoveryStyle = it.profile.discoveryStyle,
                distanceMeters = 1_240.0.takeIf { _ -> it.discoverySettings.showDistance },
                privateAlbumAccessStatus = "none",
                allowGridMessages = it.discoverySettings.allowGridMessages,
                allowFlirts = it.discoverySettings.allowFlirts,
                allowAlbumRequests = it.discoverySettings.allowAlbumRequests
            )
        }

    override suspend fun updateProfile(userId: UUID, update: ProfileUpdate): MeRecord? {
        if (record.userId != userId) return null
        record = record.copy(
            profile = record.profile.copy(
                displayName = update.displayName,
                birthdate = update.birthdate,
                displayAge = update.displayAge,
                gender = update.gender,
                orientation = update.orientation,
                relationshipContext = update.relationshipContext,
                discoveryStyle = update.discoveryStyle ?: record.profile.discoveryStyle,
                profileCompletenessScore = update.profileCompletenessScore
            )
        )
        return record
    }
}
