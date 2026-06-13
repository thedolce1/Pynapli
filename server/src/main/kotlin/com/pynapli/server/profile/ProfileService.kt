package com.pynapli.server.profile

import com.pynapli.server.activity.ActivityService
import com.pynapli.server.discovery.DiscoveryTagTaxonomies
import com.pynapli.server.distance.toPublicDistanceLabel
import com.pynapli.server.distance.toPublicDistanceMeters
import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.profiling.profile
import com.pynapli.server.safety.BlockRepository
import com.pynapli.server.safety.RiskPolicyService
import com.pynapli.shared.network.dto.AuthUserDto
import com.pynapli.shared.network.dto.DiscoverySettingsDto
import com.pynapli.shared.network.dto.LinkedPartnerMiniDto
import com.pynapli.shared.network.dto.MeDto
import com.pynapli.shared.network.dto.ProfileDto
import com.pynapli.shared.network.dto.PublicProfileDto
import com.pynapli.shared.network.dto.UpdateDiscoverySettingsRequestDto
import com.pynapli.shared.network.dto.UpdateProfileRequestDto
import com.pynapli.shared.profile.ProfileTaxonomies
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.LocalDate
import java.time.Period
import java.util.UUID

class ProfileService(
    private val repository: ProfileRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val mediaAssetUrlResolver: MediaAssetUrlResolver? = null,
    private val activityService: ActivityService? = null,
    private val blockRepository: BlockRepository? = null,
    private val riskPolicyService: RiskPolicyService? = null,
    private val bioScanner: ProfileBioScanner = AcceptAllProfileBioScanner,
    private val profileViewRecordScope: CoroutineScope? = null,
    profileViewRecordConcurrency: Int = 4,
) {
    private val profileViewRecordSemaphore =
        profileViewRecordScope
            ?.let { Semaphore(profileViewRecordConcurrency.coerceAtLeast(1)) }

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
        val profile =
            profile("profile.repository") {
                repository.getPublicProfile(viewerUserId, targetUserId)?.toDto()
            }
                ?: throw ProfileException("PROFILE_NOT_FOUND")
        profile("profile.activityRecord") {
            recordProfileViewActivity(viewerUserId, targetUserId)
        }
        return profile
    }

    private suspend fun recordProfileViewActivity(
        viewerUserId: UUID,
        targetUserId: UUID,
    ) {
        val service = activityService ?: return
        val scope = profileViewRecordScope
        if (scope == null) {
            recordProfileViewActivityInline(service, viewerUserId, targetUserId)
            return
        }

        scope.launch {
            val semaphore = profileViewRecordSemaphore
            if (semaphore == null) {
                recordProfileViewActivityInline(service, viewerUserId, targetUserId)
            } else {
                semaphore.withPermit {
                    recordProfileViewActivityInline(service, viewerUserId, targetUserId)
                }
            }
        }
    }

    private suspend fun recordProfileViewActivityInline(
        service: ActivityService,
        viewerUserId: UUID,
        targetUserId: UUID,
    ) {
        try {
            service.recordProfileView(viewerUserId, targetUserId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logger.warn(
                "Failed to record profile-view activity for viewer {} target {}",
                viewerUserId,
                targetUserId,
                error,
            )
        }
    }

    suspend fun updateProfile(
        userId: UUID,
        request: UpdateProfileRequestDto,
    ): MeDto {
        val update = request.toProfileUpdate()

        // PROFILE_SETTINGS_SPEC: high-sensitivity lifestyle terms are age-gated,
        // mirroring the filter-side gate in SavedGridFilterService/deck requests.
        if (!update.lifestyleTags.isNullOrEmpty()) {
            val current = repository.getMe(userId) ?: throw ProfileException("PROFILE_NOT_FOUND")
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
        val update = request.toDiscoverySettingsUpdate()
        return repository.updateDiscoverySettings(userId, update)?.toDto() ?: throw ProfileException("PROFILE_NOT_FOUND")
    }

    private fun UpdateDiscoverySettingsRequestDto.toDiscoverySettingsUpdate(): DiscoverySettingsUpdate {
        val normalizedGridMessagePolicy = gridMessagePolicy?.trim()?.lowercase()
        if (normalizedGridMessagePolicy != null && normalizedGridMessagePolicy !in GRID_MESSAGE_POLICIES) {
            throw ProfileException("INVALID_GRID_MESSAGE_POLICY")
        }

        val normalizedPartnerVisibility = partnerVisibility?.trim()?.lowercase()
        if (normalizedPartnerVisibility != null && normalizedPartnerVisibility !in PARTNER_VISIBILITIES) {
            throw ProfileException("INVALID_PARTNER_VISIBILITY")
        }

        return DiscoverySettingsUpdate(
            gridVisible = gridVisible,
            swipeVisible = swipeVisible,
            showDistance = showDistance,
            showInViewedLists = showInViewedLists,
            allowGridMessages = allowGridMessages,
            gridMessagePolicy = normalizedGridMessagePolicy,
            allowFlirts = allowFlirts,
            allowIntroNotes = allowIntroNotes,
            allowAlbumRequests = allowAlbumRequests,
            visibleWhileTraveling = visibleWhileTraveling,
            sharePsyncSoloMatches = sharePsyncSoloMatches,
            partnerVisibility = normalizedPartnerVisibility,
        )
    }

    private fun UpdateProfileRequestDto.toProfileUpdate(): ProfileUpdate {
        val trimmedName = displayName?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmedName != null && trimmedName.length !in 2..32) {
            throw ProfileException("INVALID_DISPLAY_NAME")
        }

        val parsedBirthdate =
            birthdate?.let {
                runCatching { LocalDate.parse(it) }.getOrElse { throw ProfileException("INVALID_BIRTHDATE") }
            }
        val displayAge =
            parsedBirthdate?.let { birthdate ->
                val age = Period.between(birthdate, LocalDate.now(clock)).years
                if (age < 18) throw ProfileException("AGE_RESTRICTED")
                if (age > 120) throw ProfileException("INVALID_BIRTHDATE")
                age
            }

        val normalizedDiscoveryStyle = discoveryStyle?.trim()?.lowercase()
        if (normalizedDiscoveryStyle != null && normalizedDiscoveryStyle !in DISCOVERY_STYLES) {
            throw ProfileException("INVALID_DISCOVERY_STYLE")
        }

        val normalizedGender = gender.cleanOptional(40)
        if (normalizedGender != null && normalizedGender !in ProfileTaxonomies.genderKeys) {
            throw ProfileException("INVALID_GENDER")
        }
        val cleanGenderDetail = selfDescribeDetail(normalizedGender, genderDetail, "INVALID_GENDER")

        val normalizedOrientation = orientation.cleanOptional(40)
        if (normalizedOrientation != null && normalizedOrientation !in ProfileTaxonomies.orientationKeys) {
            throw ProfileException("INVALID_ORIENTATION")
        }
        val cleanOrientationDetail = selfDescribeDetail(normalizedOrientation, orientationDetail, "INVALID_ORIENTATION")

        val bioProvided = bio != null
        val cleanBio = bio?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanBio != null && cleanBio.length > ProfileTaxonomies.BIO_MAX_LENGTH) {
            throw ProfileException("INVALID_BIO")
        }

        return ProfileUpdate(
            displayName = trimmedName,
            birthdate = parsedBirthdate,
            gender = normalizedGender,
            genderDetail = cleanGenderDetail,
            orientation = normalizedOrientation,
            orientationDetail = cleanOrientationDetail,
            relationshipContext = relationshipContext.cleanOptional(40),
            discoveryStyle = normalizedDiscoveryStyle,
            displayAge = displayAge,
            profileCompletenessScore =
                completeness(
                    displayName = trimmedName,
                    birthdate = parsedBirthdate,
                    gender = gender,
                    orientation = orientation,
                    relationshipContext = relationshipContext,
                    discoveryStyle = normalizedDiscoveryStyle,
                ),
            bioProvided = bioProvided,
            bio = cleanBio,
            lifestyleTags = lifestyleTags.cleanTags(DiscoveryTagTaxonomies.lifestyleTags),
            logisticsTags = logisticsTags.cleanTags(DiscoveryTagTaxonomies.logisticsTags),
            intents = intents.cleanTags(DiscoveryTagTaxonomies.intents),
        )
    }

    /**
     * The free-text label is only meaningful with the self_describe key (where it is
     * required); structured keys drop any stray detail so filters stay structured.
     */
    private fun selfDescribeDetail(
        normalizedKey: String?,
        detail: String?,
        errorCode: String,
    ): String? {
        if (normalizedKey != ProfileTaxonomies.SELF_DESCRIBE) return null
        val cleaned = detail?.trim()?.takeIf { it.isNotEmpty() } ?: throw ProfileException(errorCode)
        if (cleaned.length > ProfileTaxonomies.SELF_DESCRIBE_MAX_LENGTH) throw ProfileException(errorCode)
        return cleaned
    }

    private fun List<String>?.cleanTags(allowed: Set<String>): List<String>? {
        if (this == null) return null
        val cleaned =
            map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .distinct()
        cleaned.forEach { value ->
            if (value !in allowed) throw ProfileException("INVALID_PROFILE_TAG")
        }
        return cleaned
    }

    private fun MeRecord.toDto(): MeDto =
        MeDto(
            user = AuthUserDto(userId = userId.toString(), status = userStatus),
            profile = profile.toDto(),
            discoverySettings = discoverySettings.toDto(),
        )

    private fun ProfileRecord.toDto(): ProfileDto =
        ProfileDto(
            userId = userId.toString(),
            displayName = displayName,
            birthdate = birthdate?.toString(),
            displayAge = displayAge,
            bio = bio,
            gender = gender,
            genderDetail = genderDetail,
            orientation = orientation,
            orientationDetail = orientationDetail,
            relationshipContext = relationshipContext,
            discoveryStyle = discoveryStyle,
            lifestyleTags = lifestyleTags,
            logisticsTags = logisticsTags,
            intents = intents,
            profileCompletenessScore = profileCompletenessScore,
            moderationStatus = moderationStatus,
            visibilityStatus = visibilityStatus,
            ageVerified = ageVerified,
        )

    private fun PublicProfileRecord.toDto(): PublicProfileDto =
        PublicProfileDto(
            userId = userId.toString(),
            displayName = displayName,
            displayAge = displayAge,
            bio = bio,
            gender = gender,
            genderDetail = genderDetail,
            orientation = orientation,
            orientationDetail = orientationDetail,
            relationshipContext = relationshipContext,
            discoveryStyle = discoveryStyle,
            lifestyleTags = lifestyleTags,
            logisticsTags = logisticsTags,
            intents = intents,
            distanceLabel = distanceMeters?.toPublicDistanceLabel(),
            distanceMeters = distanceMeters?.toPublicDistanceMeters(),
            profilePhotoUrl = profilePhotoMediaAssetId?.let { mediaAssetUrlResolver?.publicContentUrl(it) },
            privateAlbumAccessStatus = privateAlbumAccessStatus,
            linkedPartner =
                linkedPartnerUserId?.let { partnerUserId ->
                    LinkedPartnerMiniDto(
                        userId = partnerUserId.toString(),
                        displayName = linkedPartnerDisplayName,
                        thumbnailUrl =
                            linkedPartnerThumbnailMediaAssetId
                                ?.let { mediaAssetUrlResolver?.publicContentUrl(it) },
                    )
                },
            allowGridMessages = allowGridMessages,
            allowFlirts = allowFlirts,
            allowAlbumRequests = allowAlbumRequests,
        )

    private fun DiscoverySettingsRecord.toDto(): DiscoverySettingsDto =
        DiscoverySettingsDto(
            gridVisible = gridVisible,
            swipeVisible = swipeVisible,
            eventsVisible = eventsVisible,
            showDistance = showDistance,
            showInViewedLists = showInViewedLists,
            incognito = incognito,
            allowGridMessages = allowGridMessages,
            gridMessagePolicy = gridMessagePolicy,
            allowFlirts = allowFlirts,
            allowIntroNotes = allowIntroNotes,
            allowAlbumRequests = allowAlbumRequests,
            soloEnabled = soloEnabled,
            togetherEnabled = togetherEnabled,
            socialEnabled = socialEnabled,
            visibleWhileTraveling = visibleWhileTraveling,
            sharePsyncSoloMatches = sharePsyncSoloMatches,
            partnerVisibility = partnerVisibility,
        )

    private fun String?.cleanOptional(maxLength: Int): String? {
        val value = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (value.length > maxLength) throw ProfileException("INVALID_PROFILE_FIELD")
        return value.lowercase()
    }

    private fun completeness(
        displayName: String?,
        birthdate: LocalDate?,
        gender: String?,
        orientation: String?,
        relationshipContext: String?,
        discoveryStyle: String?,
    ): Int {
        var score = 0
        if (displayName != null) score += 20
        if (birthdate != null) score += 25
        if (!gender.isNullOrBlank()) score += 15
        if (!orientation.isNullOrBlank()) score += 15
        if (!relationshipContext.isNullOrBlank()) score += 10
        if (!discoveryStyle.isNullOrBlank()) score += 15
        return score.coerceIn(0, 100)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ProfileService::class.java)
        private val DISCOVERY_STYLES = setOf("solo", "together", "both", "social", "ask_in_chat")
        private val GRID_MESSAGE_POLICIES = setOf("everyone", "matches_only", "no_one")
        private val PARTNER_VISIBILITIES = setOf("public", "matches_only", "verified_only", "hidden")
    }
}

class ProfileException(
    val code: String,
) : RuntimeException(code)
