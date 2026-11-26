package com.pynapli.server.profile

import com.pynapli.server.activity.ActivityService
import com.pynapli.server.favorites.FavoriteService
import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.presence.LastActiveRepository
import com.pynapli.server.presence.PresenceService
import com.pynapli.server.profiling.profile
import com.pynapli.server.safety.BlockRepository
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RateLimitedAction
import com.pynapli.server.safety.RiskPolicyService
import com.pynapli.shared.network.dto.MeDto
import com.pynapli.shared.network.dto.PublicProfileDto
import com.pynapli.shared.network.dto.UpdateDiscoverySettingsRequestDto
import com.pynapli.shared.network.dto.UpdateProfileRequestDto
import com.pynapli.shared.presence.LastActiveBucket
import kotlinx.coroutines.CoroutineScope
import java.time.Clock
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

class ProfileService(
    private val repository: ProfileRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val mediaAssetUrlResolver: MediaAssetUrlResolver? = null,
    activityService: ActivityService? = null,
    private val blockRepository: BlockRepository? = null,
    private val riskPolicyService: RiskPolicyService? = null,
    private val presenceService: PresenceService? = null,
    private val rateLimitService: RateLimitService? = null,
    private val bioScanner: ProfileBioScanner = AcceptAllProfileBioScanner,
    profileViewRecordScope: CoroutineScope? = null,
    profileViewRecordConcurrency: Int = 4,
    private val lastActiveRepository: LastActiveRepository? = null,
    private val favoriteService: FavoriteService? = null,
    // "Hide from people you know": someone hiding from the viewer is PROFILE_NOT_FOUND (as a deleted
    // or hidden profile is), and never shows as the partner on someone else's profile.
    private val contactBlockVisibility: com.pynapli.server.contactblocks.ContactBlockVisibility? = null,
) {
    private val profileViewRecorder =
        ProfileViewActivityRecorder(
            activityService = activityService,
            scope = profileViewRecordScope,
            concurrency = profileViewRecordConcurrency,
        )

    suspend fun getMe(userId: UUID): MeDto = repository.getMe(userId)?.toDto() ?: throw ProfileException("PROFILE_NOT_FOUND")

    suspend fun getPublicProfile(
        viewerUserId: UUID,
        targetUserId: UUID,
    ): PublicProfileDto {
        if (profile("profile.blockCheck") { blockRepository?.isBlockedEitherWay(viewerUserId, targetUserId) } == true) {
            throw ProfileException("PROFILE_NOT_FOUND")
        }
        if (profile("profile.riskCheck") { riskPolicyService?.shouldHideFromDiscovery(targetUserId) } == true) {
            throw ProfileException("PROFILE_NOT_FOUND")
        }
        val hiddenFromViewer = profile("profile.contactBlocks") { contactBlockVisibility?.hiddenFrom(viewerUserId).orEmpty() }
        if (targetUserId in hiddenFromViewer) throw ProfileException("PROFILE_NOT_FOUND")
        val fetched =
            profile("profile.repository") {
                repository.getPublicProfile(viewerUserId, targetUserId)?.toDto(mediaAssetUrlResolver)
            }
                ?: throw ProfileException("PROFILE_NOT_FOUND")
        val profile =
            if (fetched.linkedPartner?.userId?.let { runCatching { UUID.fromString(it) }.getOrNull() } in hiddenFromViewer) {
                // Every couple signal goes with the partner: the couple badges would name the hidden link.
                fetched.copy(linkedPartner = null, coupleVerified = false, metInPersonAsCouple = false)
            } else {
                fetched
            }
        profile("profile.activityRecord") {
            profileViewRecorder.record(viewerUserId, targetUserId)
        }
        val online = profile("profile.presence") { isOnline(targetUserId) }
        val lastActive = profile("profile.lastActive") { lastActiveBucket(targetUserId, online) }
        val favorite =
            profile("profile.favorite") {
                runCatching { favoriteService?.isFavorite(viewerUserId, targetUserId) }.getOrNull() ?: false
            }
        return profile.copy(online = online, lastActive = lastActive, favorite = favorite)
    }

    /**
     * Coarse last seen (GRID_IMPLEMENTATION_SPEC "Presence States"): only the bucket key leaves the
     * server, never the timestamp, and nothing at all for someone browsing incognito. Fails safe to
     * no label.
     */
    private suspend fun lastActiveBucket(
        targetUserId: UUID,
        online: Boolean,
    ): String? {
        val repository = lastActiveRepository ?: return null
        val lookup = runCatching { repository.lookup(targetUserId) }.getOrNull() ?: return null
        if (lookup.hidden) return null
        val since =
            lookup.lastActiveAt?.let { at ->
                java.time.Duration
                    .between(at, clock.instant())
                    .toMillis()
                    .milliseconds
            }
        return LastActiveBucket.bucketFor(online = online, sinceLastActive = since)?.key
    }

    // Fail-safe presence lookup, mirroring GridService.onlineUserIdsFor: any presence/Redis
    // error degrades to "offline" rather than failing the profile fetch.
    private suspend fun isOnline(targetUserId: UUID): Boolean =
        runCatching {
            presenceService?.onlineUserIds(listOf(targetUserId))?.contains(targetUserId)
        }.getOrNull() ?: false

    suspend fun updateProfile(
        userId: UUID,
        request: UpdateProfileRequestDto,
    ): MeDto {
        requireProfileMutationRateLimit(userId)
        val current = repository.getMe(userId) ?: throw ProfileException("PROFILE_NOT_FOUND")
        val update = request.keepingUnsent(current.profile).toProfileUpdate(clock)

        // PROFILE_SETTINGS_SPEC: high-sensitivity lifestyle terms are age-gated,
        // mirroring the filter-side gate in SavedGridFilterService/deck requests.
        // Experience level is a lifestyle term too; boundaries are consent wording (low
        // sensitivity per LANGUAGE_AND_TAXONOMY_SPEC) and are not gated.
        if (!update.lifestyleTags.isNullOrEmpty() || update.experienceLevel != null) {
            if (!current.profile.ageVerified) throw ProfileException("AGE_VERIFICATION_REQUIRED")
        }

        if (update.bioProvided && update.bio != null && bioScanner.scan(update.bio) == ProfileBioScanner.Decision.Reject) {
            throw ProfileException("BIO_REJECTED")
        }

        return repository.updateProfile(userId, update)?.toDto() ?: throw ProfileException("PROFILE_NOT_FOUND")
    }

    suspend fun updateDiscoverySettings(
        userId: UUID,
        request: UpdateDiscoverySettingsRequestDto,
    ): MeDto {
        requireProfileMutationRateLimit(userId)
        val update = request.toDiscoverySettingsUpdate()
        return repository.updateDiscoverySettings(userId, update)?.toDto() ?: throw ProfileException("PROFILE_NOT_FOUND")
    }

    // D2 hardening: profile/settings writes run validation + the bio scanner, so cap their velocity.
    // updateProfile and updateDiscoverySettings share one PROFILE_UPDATE budget.
    private suspend fun requireProfileMutationRateLimit(userId: UUID) {
        if (rateLimitService?.check(userId, RateLimitedAction.PROFILE_UPDATE)?.allowed == false) {
            throw ProfileException("RATE_LIMITED")
        }
    }
}

class ProfileException(
    val code: String,
) : RuntimeException(code)
