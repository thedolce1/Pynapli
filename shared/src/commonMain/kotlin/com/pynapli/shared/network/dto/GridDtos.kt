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
    // Superseded by [freshOnly] (plan G4): the server treats true here exactly as freshOnly = true, so
    // old clients and saved filters keep working. New clients send freshOnly.
    val recentlyJoinedOnly: Boolean = false,
    // "Fresh" quick chip: only cards whose [GridCardDto.fresh] is true (server rule: account under 72 h
    // old or a new approved photo in the last 24 h, and active in the last 24 h). Free.
    val freshOnly: Boolean = false,
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
    // Partner filters (FILTERS_SPEC "Partner"): matched against the visibly linked partner's OWN declared
    // profile (gender key, age, orientation key, photo verification), only through the partner gate
    // behind linkedOnly, so a partner hidden from this viewer never matches. Any of them implies Linked.
    // Same taxonomies and age bounds as genders / orientations / minimumAge. Free. Old clients omit them.
    val partnerGenders: List<String> = emptyList(),
    val partnerMinimumAge: Int? = null,
    val partnerMaximumAge: Int? = null,
    val partnerOrientations: List<String> = emptyList(),
    val partnerVerifiedOnly: Boolean = false,
    // "No single men": drop people whose own gender is "man" or "trans_man" with no linked partner shown
    // to this viewer (gender identity, founder decision 2026-11-27).
    val excludeSingleMen: Boolean = false,
    // "Haven't chatted" (Grindr's "Haven't chatted today"): drop people the viewer exchanged a direct
    // message with in the last 24 hours (either direction; an inbound message counts only once it is
    // visible to the viewer). Grid only, per viewer, never from a shared cache. Old clients omit it.
    val haventChattedOnly: Boolean = false,
    // Lifestyle "All of" (G2-4, Grid only): every one of [lifestyleTags] must match (at most 3, and one
    // experience level), instead of any one per group. Old clients omit it.
    val lifestyleMatchAll: Boolean = false,
    // Section 3 opt-in: this client renders locked cards (GridCardDto.locked) as paywall tiles, never
    // as profiles. Without it the server keeps the old behaviour: the grid stops at the tier depth with
    // resultLimitReached. With it the grid keeps paging past the depth with locked cards up to the next
    // tier's depth. The server alone decides which cards are locked.
    val acceptLockedCards: Boolean = false,
)

@Serializable
data class GridQueryResponseDto(
    val items: List<GridCardDto>,
    val nextCursor: String? = null,
    val advancedFiltersApplied: Boolean = false,
    val resultLimitReached: Boolean = false,
    val resultLimitTier: String? = null,
    // Plan G10: the first page was sparse at the requested radius, so this page (and every page that
    // continues its cursor) comes from the 100 km step. Drives "Showing people farther away".
    val widened: Boolean = false,
    // Section 3 (only for requests with acceptLockedCards): the 0-based position in the whole result
    // where locked cards start (the viewer's tier depth). Set on the page holding the first locked
    // card, on every later page, and on the page whose next page starts locked. Null otherwise.
    val lockedFromIndex: Int? = null,
    // The plan that unlocks the locked cards ("social" or "select"), set whenever lockedFromIndex is.
    val unlockTier: String? = null,
    // G2-2: the page size the server used for this viewer's plan (Free 60, Social 90, Select 120).
    // The app asks for the most and the server clamps. 0 from an older server.
    val pageSize: Int = 0,
)

@Serializable
data class GridCardDto(
    val userId: String,
    val displayName: String,
    val thumbnailUrl: String? = null,
    // G2-2: the stable key that names [thumbnailUrl]'s bytes ("<assetId>:v1-thumb-480" for the
    // server-made thumbnail, "<assetId>:orig" for the original). Clients cache by it, so a re-signed
    // URL is a cache hit; the URL still guards access. Null on locked cards (no identity, Section 3).
    val thumbnailCacheKey: String? = null,
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
    // "New" marker (plan G4): server-computed Fresh rule, the same one behind the freshOnly filter.
    val fresh: Boolean = false,
    // Coarse activity key (plan G11), server-computed, never a timestamp: one of "online", "15m",
    // "1h", "3h", "today", "week" (each covers the window up to its bound after the previous one), or
    // null when older than 7 days, never seen, or the member hides their activity (incognito).
    val activeAgo: String? = null,
    // Section 3 paywall tile: true when this card lies past the viewer's tier depth. A locked card
    // carries no identity: [userId] is an opaque per-viewer token starting "locked_" that no route
    // accepts as a user id, [rankToken] is the same token, [displayName] is "", and linkedPartner,
    // activeAgo, visitingFrom and visitingUntil are null. It keeps only what the tile shows: thumbnail,
    // online dot, distance (as the member allows) and the badges. Tapping it opens the paywall.
    val locked: Boolean = false,
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
    // G2-2: stable cache key for [thumbnailUrl] (same meaning as GridCardDto.thumbnailCacheKey).
    val thumbnailCacheKey: String? = null,
    val online: Boolean = false,
    // Partner preview only (public profile detail): the partner's own orientation, experience
    // level and swap/room tags. Always null/empty on Grid cards and the Tonight feed.
    val orientation: String? = null,
    val orientationDetail: String? = null,
    val experienceLevel: String? = null,
    val lifestyleTags: List<String> = emptyList(),
    // The partner's own declared age and gender key (plan G2-3), for the tile's partner mini and the
    // partner preview. Filled only where this mini is shown at all (the same partner gate), on Grid
    // cards and the public profile; null on Swipe cards, Psync and the Tonight feed. A self-describe
    // gender comes back as "self_describe" and shows no mark.
    val age: Int? = null,
    val gender: String? = null,
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

/**
 * `POST /v1/grid/count` (plan G2-4): how many people the Grid would show for these filters, for the
 * filter sheet's "Show N people" button. The request is the Grid request itself. [count] never exceeds
 * what the Grid would return under the same filters and visibility rules; [atLeast] means more match
 * than were counted (show "N+"). Capped at 500 and at the plan's Grid depth.
 */
@Serializable
data class GridCountResponseDto(
    val count: Int,
    val atLeast: Boolean = false,
)
