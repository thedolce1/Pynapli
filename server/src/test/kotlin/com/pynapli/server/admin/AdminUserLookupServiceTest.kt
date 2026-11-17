package com.pynapli.server.admin

import com.pynapli.server.moderation.ModerationException
import com.pynapli.server.profile.DiscoverySettingsRecord
import com.pynapli.server.profile.DiscoverySettingsUpdate
import com.pynapli.server.profile.MeRecord
import com.pynapli.server.profile.ProfileRecord
import com.pynapli.server.profile.ProfileRepository
import com.pynapli.server.profile.ProfileUpdate
import com.pynapli.server.profile.PublicProfileRecord
import com.pynapli.shared.network.dto.ProfileLinkDto
import com.pynapli.shared.network.dto.ProfileLinksSummaryDto
import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AdminUserLookupServiceTest {
    private val userId = UUID.randomUUID()
    private val partnerId = UUID.randomUUID()

    @Test
    fun composesTheConsolidatedViewAndAuditsTheLookup() =
        runBlocking {
            val repository = RecordingLookupRepository()
            val service =
                AdminUserLookupService(
                    profileRepository = FakeProfileRepository(present = true),
                    repository = repository,
                    entitlementLookup = { entitlement(tier = "social", source = "play_store") },
                    partnerLinkLookup = { linkedSummary() },
                )

            val view = service.lookup(actorId = "ops-1", userId = userId, reason = "report follow-up")

            assertEquals(userId.toString(), view.userId)
            assertEquals("active", view.accountStatus)
            assertEquals("Alex", view.displayName)
            assertEquals("social", view.subscriptionTier)
            assertEquals(true, view.partnerLinked)
            assertEquals(partnerId.toString(), view.linkedPartnerUserId)
            assertEquals("watch", view.riskTier)
            assertEquals(1, view.recentRiskReasons.size)
            assertEquals(2, view.reportsAgainstCount)

            // The lookup was audited with the actor + reason (a real data reveal).
            assertEquals(Triple("ops-1", userId, "report follow-up"), repository.audited)
        }

    @Test
    fun unknownUserIsNotFoundAndIsNotAudited() =
        runBlocking {
            val repository = RecordingLookupRepository()
            val service =
                AdminUserLookupService(
                    profileRepository = FakeProfileRepository(present = false),
                    repository = repository,
                    entitlementLookup = { entitlement(tier = "free", source = "none") },
                    partnerLinkLookup = { ProfileLinksSummaryDto() },
                )

            val error =
                assertFailsWith<ModerationException> {
                    service.lookup(actorId = "ops-1", userId = userId, reason = "x")
                }
            assertEquals("USER_NOT_FOUND", error.code)
            assertTrue(repository.audited == null)
        }

    private fun entitlement(
        tier: String,
        source: String,
    ) = SubscriptionEntitlementDto(
        tier = tier,
        source = source,
        displayName = tier.replaceFirstChar { it.uppercase() },
        linkedPartnerIncluded = tier != "free",
    )

    private fun linkedSummary() =
        ProfileLinksSummaryDto(
            current =
                ProfileLinkDto(
                    linkId = UUID.randomUUID().toString(),
                    otherUserId = partnerId.toString(),
                    status = "active",
                    direction = "outgoing",
                    createdAt = "2026-06-01T00:00:00Z",
                ),
        )

    private inner class RecordingLookupRepository : AdminUserLookupRepository {
        var audited: Triple<String, UUID, String>? = null

        override suspend fun moderationFacts(userId: UUID): AdminUserModerationFacts =
            AdminUserModerationFacts(
                riskTier = "watch",
                riskScore = 35,
                lastReviewedAt = null,
                recentRiskReasons =
                    listOf(
                        AdminUserRiskReason("report", "harassment_report", 35, Instant.parse("2026-06-10T00:00:00Z")),
                    ),
                reportsAgainstCount = 2,
                reportsByCount = 0,
                blocksAgainstCount = 1,
                blocksByCount = 0,
                activeSessionCount = 1,
                activeDeviceCount = 1,
                recentModerationActions = emptyList(),
            )

        override suspend fun recordLookupAudit(
            actorId: String,
            userId: UUID,
            reason: String,
        ) {
            audited = Triple(actorId, userId, reason)
        }
    }

    private inner class FakeProfileRepository(
        private val present: Boolean,
    ) : ProfileRepository {
        override suspend fun getMe(userId: UUID): MeRecord? =
            if (!present) {
                null
            } else {
                MeRecord(
                    userId = userId,
                    userStatus = "active",
                    profile =
                        ProfileRecord(
                            userId = userId,
                            displayName = "Alex",
                            birthdate = null,
                            displayAge = 31,
                            gender = "man",
                            orientation = "bisexual",
                            relationshipContext = "single",
                            discoveryStyle = "social",
                            profileCompletenessScore = 80,
                            moderationStatus = "approved",
                            visibilityStatus = "visible",
                            ageVerified = true,
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
            }

        override suspend fun getPublicProfile(
            viewerUserId: UUID,
            targetUserId: UUID,
        ): PublicProfileRecord? = null

        override suspend fun updateProfile(
            userId: UUID,
            update: ProfileUpdate,
        ): MeRecord? = null

        override suspend fun updateDiscoverySettings(
            userId: UUID,
            update: DiscoverySettingsUpdate,
        ): MeRecord? = null
    }
}
