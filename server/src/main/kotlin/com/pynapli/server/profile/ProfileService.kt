package com.pynapli.server.profile

import com.pynapli.server.activity.ActivityService
import com.pynapli.server.distance.toPublicDistanceLabel
import com.pynapli.server.distance.toPublicDistanceMeters
import com.pynapli.server.media.MediaAssetUrlResolver
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
    private val riskPolicyService: RiskPolicyService? = null
) {
    suspend fun getMe(userId: UUID): MeDto =
        repository.getMe(userId)?.toDto() ?: throw ProfileException("PROFILE_NOT_FOUND")

    suspend fun getPublicProfile(viewerUserId: UUID, targetUserId: UUID): PublicProfileDto {
        if (blockRepository?.isBlockedEitherWay(viewerUserId, targetUserId) == true) {
            throw ProfileException("PROFILE_NOT_FOUND")
        }
        if (riskPolicyService?.shouldHideFromDiscovery(targetUserId) == true) {
            throw ProfileException("PROFILE_NOT_FOUND")
        }
        val profile = repository.getPublicProfile(viewerUserId, targetUserId)?.toDto()
            ?: throw ProfileException("PROFILE_NOT_FOUND")
        runCatching { activityService?.recordProfileView(viewerUserId, targetUserId) }
        return profile
    }

    suspend fun updateProfile(userId: UUID, request: UpdateProfileRequestDto): MeDto {
        val update = request.toProfileUpdate()
        return repository.updateProfile(userId, update)?.toDto() ?: throw ProfileException("PROFILE_NOT_FOUND")
    }

    suspend fun updateDiscoverySettings(userId: UUID, request: UpdateDiscoverySettingsRequestDto): MeDto {
        val update = request.toDiscoverySettingsUpdate()
        return repository.updateDiscoverySettings(userId, update)?.toDto() ?: throw ProfileException("PROFILE_NOT_FOUND")
    }

    private fun UpdateDiscoverySettingsRequestDto.toDiscoverySettingsUpdate(): DiscoverySettingsUpdate {
        val normalizedGridMessagePolicy = gridMessagePolicy?.trim()?.lowercase()
        if (normalizedGridMessagePolicy != null && normalizedGridMessagePolicy !in GRID_MESSAGE_POLICIES) {
            throw ProfileException("INVALID_GRID_MESSAGE_POLICY")
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
            allowAlbumRequests = allowAlbumRequests
        )
    }

    private fun UpdateProfileRequestDto.toProfileUpdate(): ProfileUpdate {
        val trimmedName = displayName?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmedName != null && trimmedName.length !in 2..32) {
            throw ProfileException("INVALID_DISPLAY_NAME")
        }

        val parsedBirthdate = birthdate?.let {
            runCatching { LocalDate.parse(it) }.getOrElse { throw ProfileException("INVALID_BIRTHDATE") }
        }
        val displayAge = parsedBirthdate?.let { birthdate ->
            val age = Period.between(birthdate, LocalDate.now(clock)).years
            if (age < 18) throw ProfileException("AGE_RESTRICTED")
            if (age > 120) throw ProfileException("INVALID_BIRTHDATE")
            age
        }

        val normalizedDiscoveryStyle = discoveryStyle?.trim()?.lowercase()
        if (normalizedDiscoveryStyle != null && normalizedDiscoveryStyle !in DISCOVERY_STYLES) {
            throw ProfileException("INVALID_DISCOVERY_STYLE")
        }

        return ProfileUpdate(
            displayName = trimmedName,
            birthdate = parsedBirthdate,
            gender = gender.cleanOptional(40),
            orientation = orientation.cleanOptional(40),
            relationshipContext = relationshipContext.cleanOptional(40),
            discoveryStyle = normalizedDiscoveryStyle,
            displayAge = displayAge,
            profileCompletenessScore = completeness(
                displayName = trimmedName,
                birthdate = parsedBirthdate,
                gender = gender,
                orientation = orientation,
                relationshipContext = relationshipContext,
                discoveryStyle = normalizedDiscoveryStyle
            )
        )
    }

    private fun MeRecord.toDto(): MeDto = MeDto(
        user = AuthUserDto(userId = userId.toString(), status = userStatus),
        profile = profile.toDto(),
        discoverySettings = discoverySettings.toDto()
    )

    private fun ProfileRecord.toDto(): ProfileDto = ProfileDto(
        userId = userId.toString(),
        displayName = displayName,
        birthdate = birthdate?.toString(),
        displayAge = displayAge,
        gender = gender,
        orientation = orientation,
        relationshipContext = relationshipContext,
        discoveryStyle = discoveryStyle,
        profileCompletenessScore = profileCompletenessScore,
        moderationStatus = moderationStatus,
        visibilityStatus = visibilityStatus,
        ageVerified = ageVerified
    )

    private fun PublicProfileRecord.toDto(): PublicProfileDto = PublicProfileDto(
        userId = userId.toString(),
        displayName = displayName,
        displayAge = displayAge,
        gender = gender,
        orientation = orientation,
        relationshipContext = relationshipContext,
        discoveryStyle = discoveryStyle,
        distanceLabel = distanceMeters?.toPublicDistanceLabel(),
        distanceMeters = distanceMeters?.toPublicDistanceMeters(),
        profilePhotoUrl = profilePhotoMediaAssetId?.let { mediaAssetUrlResolver?.publicContentUrl(it) },
        privateAlbumAccessStatus = privateAlbumAccessStatus,
        linkedPartner = linkedPartnerUserId?.let { partnerUserId ->
            LinkedPartnerMiniDto(
                userId = partnerUserId.toString(),
                displayName = linkedPartnerDisplayName,
                thumbnailUrl = linkedPartnerThumbnailMediaAssetId
                    ?.let { mediaAssetUrlResolver?.publicContentUrl(it) }
            )
        },
        allowGridMessages = allowGridMessages,
        allowFlirts = allowFlirts,
        allowAlbumRequests = allowAlbumRequests
    )

    private fun DiscoverySettingsRecord.toDto(): DiscoverySettingsDto = DiscoverySettingsDto(
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
        socialEnabled = socialEnabled
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
        discoveryStyle: String?
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
        private val DISCOVERY_STYLES = setOf("solo", "together", "both", "social", "ask_in_chat")
        private val GRID_MESSAGE_POLICIES = setOf("everyone", "matches_only", "no_one")
    }
}

class ProfileException(val code: String) : RuntimeException(code)
