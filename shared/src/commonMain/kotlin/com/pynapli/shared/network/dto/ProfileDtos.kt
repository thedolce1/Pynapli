package com.pynapli.shared.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class MeDto(
    val user: AuthUserDto,
    val profile: ProfileDto,
    val discoverySettings: DiscoverySettingsDto,
)

@Serializable
data class ProfileDto(
    val userId: String,
    val displayName: String? = null,
    val birthdate: String? = null,
    val displayAge: Int? = null,
    val bio: String? = null,
    // Structured taxonomy key (ProfileTaxonomies); the *Detail field carries the
    // free-text label only when the key is "self_describe".
    val gender: String? = null,
    val genderDetail: String? = null,
    val orientation: String? = null,
    val orientationDetail: String? = null,
    val relationshipContext: String? = null,
    val discoveryStyle: String,
    val lifestyleTags: List<String> = emptyList(),
    val logisticsTags: List<String> = emptyList(),
    val intents: List<String> = emptyList(),
    val profileCompletenessScore: Int,
    val moderationStatus: String,
    val visibilityStatus: String,
    val ageVerified: Boolean = false,
)

@Serializable
data class PublicProfileDto(
    val userId: String,
    val displayName: String,
    val displayAge: Int? = null,
    val bio: String? = null,
    val gender: String? = null,
    val genderDetail: String? = null,
    val orientation: String? = null,
    val orientationDetail: String? = null,
    val relationshipContext: String? = null,
    val discoveryStyle: String,
    val lifestyleTags: List<String> = emptyList(),
    val logisticsTags: List<String> = emptyList(),
    val intents: List<String> = emptyList(),
    val distanceLabel: String? = null,
    val distanceMeters: Int? = null,
    val profilePhotoUrl: String? = null,
    val photoUrls: List<String> = emptyList(),
    val verified: Boolean = false,
    // Live presence at fetch time, hydrated server-side from PresenceService so the peek
    // shows an accurate online dot from any entry point (Grid/Swipe/Activity/Hub) rather
    // than depending on a card the caller may not have. Convenience snapshot — WebSocket
    // presence deltas remain the live channel for surfaces that subscribe.
    val online: Boolean = false,
    val privateAlbumAccessStatus: String = "none",
    val linkedPartner: LinkedPartnerMiniDto? = null,
    val allowGridMessages: Boolean,
    val allowFlirts: Boolean,
    val allowAlbumRequests: Boolean,
)

@Serializable
data class DiscoverySettingsDto(
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
    // PROFILE_SETTINGS_SPEC L221-245: when false, the viewer is hidden from
    // every discovery surface while they have an active travel_locations row.
    // Defaults true so existing clients see the documented passport behavior.
    val visibleWhileTraveling: Boolean = true,
    // PSYNC_SPEC: solo passthrough matches are private by default; opt in to let a
    // linked partner see them in the shared Psync recap. Defaults false (private).
    val sharePsyncSoloMatches: Boolean = false,
    // PROFILE_SETTINGS_SPEC partner visibility: public | matches_only | verified_only | hidden.
    // Controls who can see this profile's linked-partner mini. Defaults public.
    val partnerVisibility: String = "public",
)

@Serializable
data class UpdateProfileRequestDto(
    val displayName: String? = null,
    val birthdate: String? = null,
    val gender: String? = null,
    val genderDetail: String? = null,
    val orientation: String? = null,
    val orientationDetail: String? = null,
    val relationshipContext: String? = null,
    val discoveryStyle: String? = null,
    // Partial semantics for the fields below (unlike the overwrite fields above,
    // which predate them): null/omitted leaves the stored value unchanged; an
    // empty string / empty list clears it.
    val bio: String? = null,
    val lifestyleTags: List<String>? = null,
    val logisticsTags: List<String>? = null,
    val intents: List<String>? = null,
)

@Serializable
data class UpdateDiscoverySettingsRequestDto(
    val gridVisible: Boolean? = null,
    val swipeVisible: Boolean? = null,
    val showDistance: Boolean? = null,
    val showInViewedLists: Boolean? = null,
    val allowGridMessages: Boolean? = null,
    val gridMessagePolicy: String? = null,
    val allowFlirts: Boolean? = null,
    val allowIntroNotes: Boolean? = null,
    val allowAlbumRequests: Boolean? = null,
    val visibleWhileTraveling: Boolean? = null,
    val sharePsyncSoloMatches: Boolean? = null,
    val partnerVisibility: String? = null,
)
