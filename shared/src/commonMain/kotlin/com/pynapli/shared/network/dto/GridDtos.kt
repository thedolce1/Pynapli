package com.pynapli.shared.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class GridQueryRequestDto(
    val radiusMeters: Int = 50_000,
    val limit: Int = 60,
    val cursor: String? = null,
    val minimumAge: Int? = null,
    val maximumAge: Int? = null,
    val relationshipContexts: List<String> = emptyList(),
    val linkedOnly: Boolean? = null,
    val onlineOnly: Boolean = false,
    val verifiedOnly: Boolean = false,
    val facePhotoVerifiedOnly: Boolean = false,
    val ageVerifiedOnly: Boolean = false,
    val discoveryStyles: List<String> = emptyList(),
    val lifestyleTags: List<String> = emptyList(),
    // Boundary items (DiscoveryTagTaxonomies.boundaryItems): hide profiles that said "not for me".
    val boundaryItems: List<String> = emptyList(),
    val logisticsTags: List<String> = emptyList(),
    val tonightAvailableOnly: Boolean = false,
    val activeTodayOnly: Boolean = false,
    val recentlyJoinedOnly: Boolean = false,
    val travelingHereOnly: Boolean = false,
    // People whose announced planned trip brings them to the viewer's area in the next 14 days.
    val visitingSoonOnly: Boolean = false,
    val withPhotosOnly: Boolean = false,
    val hasPrivateAlbumOnly: Boolean = false,
    val intents: List<String> = emptyList(),
    val genders: List<String> = emptyList(),
    val orientations: List<String> = emptyList(),
    // "Verified couples only": only people whose couple-verified badge shows for this viewer (both
    // linked partners selfie-verified, partner visible). Independent of linkedOnly. Old clients omit it.
    val verifiedCouplesOnly: Boolean = false,
    val freshOnly: Boolean = false,
)

@Serializable
data class GridQueryResponseDto(
    val items: List<GridCardDto>,
    val nextCursor: String? = null,
    val advancedFiltersApplied: Boolean = false,
    val resultLimitReached: Boolean = false,
    val resultLimitTier: String? = null,
)

@Serializable
data class GridCardDto(
    val userId: String,
    val displayName: String,
    val thumbnailUrl: String? = null,
    val online: Boolean,
    val distanceLabel: String? = null,
    val distanceMeters: Int? = null,
    val linkedPartner: LinkedPartnerMiniDto? = null,
    val rankToken: String,
    // SWIPE_IMPLEMENTATION_SPEC L348: target has an active travel mode row, so
    // they're virtually in the viewer's area rather than physically there.
    val visiting: Boolean = false,
    // PLAYING_TONIGHT_SPEC: this individual has a live Playing Tonight session.
    // A subtle per-tile indicator only — never a couple card.
    val tonightActive: Boolean = false,
    // "Visiting soon": the dates of an announced planned trip bringing them here (yyyy-MM-dd).
    val visitingFrom: String? = null,
    val visitingUntil: String? = null,
    // Photo-verified (TRUST_AND_SAFETY_SPEC "Photo Verification"): the verified badge.
    val verified: Boolean = false,
    // This person and the linked partner shown in [linkedPartner] are both photo-verified.
    // Computed server-side; false whenever the partner mini is hidden from this viewer.
    val coupleVerified: Boolean = false,
    val fresh: Boolean = false,
    val activeAgo: String? = null,
)

/**
 * Result of starring or un-starring someone (`PUT`/`DELETE /v1/favorites/{userId}`). Both calls are
 * idempotent. Favorites are private: the other person is never told.
 */
@Serializable
data class FavoriteStatusDto(
    val userId: String,
    val favorite: Boolean,
)

@Serializable
data class LinkedPartnerMiniDto(
    val userId: String,
    val displayName: String? = null,
    val thumbnailUrl: String? = null,
    val online: Boolean = false,
    // Partner preview only (public profile detail): the partner's own orientation, experience
    // level and swap/room tags. Always null/empty on Grid cards and the Tonight feed.
    val orientation: String? = null,
    val orientationDetail: String? = null,
    val experienceLevel: String? = null,
    val lifestyleTags: List<String> = emptyList(),
)

@Serializable
data class SavedGridFilterDto(
    val id: String,
    val name: String,
    val payload: GridQueryRequestDto,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class SavedGridFiltersListDto(
    val items: List<SavedGridFilterDto>,
)

@Serializable
data class CreateSavedGridFilterRequestDto(
    val name: String,
    val payload: GridQueryRequestDto,
)
