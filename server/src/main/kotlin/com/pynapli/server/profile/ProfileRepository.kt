package com.pynapli.server.profile

import java.time.LocalDate
import java.util.UUID

interface ProfileRepository {
    suspend fun getMe(userId: UUID): MeRecord?

    suspend fun getPublicProfile(
        viewerUserId: UUID,
        targetUserId: UUID,
    ): PublicProfileRecord?

    suspend fun updateProfile(
        userId: UUID,
        update: ProfileUpdate,
    ): MeRecord?

    suspend fun updateDiscoverySettings(
        userId: UUID,
        update: DiscoverySettingsUpdate,
    ): MeRecord?
}

data class MeRecord(
    val userId: UUID,
    val userStatus: String,
    val profile: ProfileRecord,
    val discoverySettings: DiscoverySettingsRecord,
)

data class ProfileRecord(
    val userId: UUID,
    val displayName: String?,
    val birthdate: LocalDate?,
    val displayAge: Int?,
    val bio: String? = null,
    val neighborhood: String? = null,
    val gender: String?,
    val genderDetail: String? = null,
    val orientation: String?,
    val orientationDetail: String? = null,
    val relationshipContext: String?,
    val discoveryStyle: String,
    val lifestyleTags: List<String> = emptyList(),
    val experienceLevel: String? = null,
    val boundaries: List<String> = emptyList(),
    val logisticsTags: List<String> = emptyList(),
    val intents: List<String> = emptyList(),
    val profileCompletenessScore: Int,
    val moderationStatus: String,
    val visibilityStatus: String,
    val ageVerified: Boolean = false,
)

data class DiscoverySettingsRecord(
    val gridVisible: Boolean,
    val swipeVisible: Boolean,
    val eventsVisible: Boolean,
    val showDistance: Boolean,
    val showInViewedLists: Boolean,
    val incognito: Boolean,
    val allowGridMessages: Boolean,
    val gridMessagePolicy: String,
    val allowFlirts: Boolean,
    val allowIntroNotes: Boolean,
    val allowAlbumRequests: Boolean,
    val soloEnabled: Boolean,
    val togetherEnabled: Boolean,
    val socialEnabled: Boolean,
    val visibleWhileTraveling: Boolean,
    val sharePsyncSoloMatches: Boolean,
    val partnerVisibility: String = "public",
    val showMetInPersonBadge: Boolean = false,
)

data class PublicProfileRecord(
    val userId: UUID,
    val displayName: String,
    val profilePhotoMediaAssetId: UUID?,
    val profilePhotoMediaAssetIds: List<UUID> = emptyList(),
    val verified: Boolean = false,
    val linkedPartnerUserId: UUID? = null,
    val linkedPartnerDisplayName: String? = null,
    val linkedPartnerThumbnailMediaAssetId: UUID? = null,
    // The visible linked partner's own declared orientation and lifestyle (partner preview).
    val linkedPartnerOrientation: String? = null,
    val linkedPartnerOrientationDetail: String? = null,
    val linkedPartnerExperienceLevel: String? = null,
    val linkedPartnerLifestyleTags: List<String> = emptyList(),
    // Both this profile and its visible linked partner are photo-verified.
    val coupleVerified: Boolean = false,
    // "Met in person" badges as the viewer may see them (batch-computed, opt-in gated in SQL).
    val metInPerson: Boolean = false,
    val metInPersonAsCouple: Boolean = false,
    val displayAge: Int?,
    val bio: String? = null,
    val neighborhood: String? = null,
    val gender: String?,
    val genderDetail: String? = null,
    val orientation: String?,
    val orientationDetail: String? = null,
    val relationshipContext: String?,
    val discoveryStyle: String,
    val lifestyleTags: List<String> = emptyList(),
    val experienceLevel: String? = null,
    val boundaries: List<String> = emptyList(),
    val logisticsTags: List<String> = emptyList(),
    val intents: List<String> = emptyList(),
    val distanceMeters: Double?,
    val privateAlbumAccessStatus: String,
    val allowGridMessages: Boolean,
    val allowFlirts: Boolean,
    val allowAlbumRequests: Boolean,
    // The owner's Grid message policy is verified_only (and Grid messages are on).
    val gridMessageVerifiedOnly: Boolean = false,
)

data class ProfileUpdate(
    val displayName: String?,
    val birthdate: LocalDate?,
    val gender: String?,
    val genderDetail: String? = null,
    val orientation: String?,
    val orientationDetail: String? = null,
    val relationshipContext: String?,
    val discoveryStyle: String?,
    val displayAge: Int?,
    val profileCompletenessScore: Int,
    // Partial-update semantics for the late-added fields: when the *Provided flag
    // (or a non-null list) is absent the stored value is left unchanged; a provided
    // null / empty list clears the column to NULL ("opted out" for tag filters).
    val bioProvided: Boolean = false,
    val bio: String? = null,
    val neighborhoodProvided: Boolean = false,
    val neighborhood: String? = null,
    val lifestyleTags: List<String>? = null,
    // Experience level: when experienceLevelProvided is false the stored level is unchanged; a
    // provided null clears it.
    val experienceLevelProvided: Boolean = false,
    val experienceLevel: String? = null,
    val boundaries: List<String>? = null,
    val logisticsTags: List<String>? = null,
    val intents: List<String>? = null,
)

data class DiscoverySettingsUpdate(
    val gridVisible: Boolean?,
    val swipeVisible: Boolean?,
    val showDistance: Boolean?,
    val showInViewedLists: Boolean?,
    val allowGridMessages: Boolean?,
    val gridMessagePolicy: String?,
    val allowFlirts: Boolean?,
    val allowIntroNotes: Boolean?,
    val allowAlbumRequests: Boolean?,
    val visibleWhileTraveling: Boolean?,
    val sharePsyncSoloMatches: Boolean?,
    val partnerVisibility: String? = null,
    val showMetInPersonBadge: Boolean? = null,
)
