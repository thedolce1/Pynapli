package com.pynapli.shared.network.dto

import kotlinx.serialization.Serializable

object SwipeAction {
    const val LIKE = "like"
    const val PASS = "pass"
    const val PINEAPPLE_LIKE = "pineapple_like"
}

/**
 * The optional note a Pineapple Like can carry (`SwipeActionRequestDto.noteText`). Intro Notes were
 * a separate product until 2026-11-17; they are now this note. Trimmed; blank means no note.
 */
object PineappleLikeNote {
    const val MAX_LENGTH = 140

    /** The note as the server stores it: trimmed, or null when there is nothing to send. */
    fun normalize(text: String?): String? = text?.trim()?.takeIf { it.isNotEmpty() }
}

// FILTERS_SPEC L356-365: dealbreaker-eligible filter keys for the Swipe deck.
// A filter key in SwipeDeckRequestDto.dealbreakers marks that filter as a hard
// gate. Non-dealbreaker filters stay "soft" and may be relaxed by the server
// when the recommendation pool runs low (FILTERS_SPEC L373, L403-409).
object SwipeDealbreakerKey {
    const val AGE = "age"
    const val DISTANCE = "distance"
    const val VERIFIED = "verified"
    const val DISCOVERY_STYLES = "discoveryStyles"
    const val INTENTS = "intents"
    const val LINKED_ONLY = "linkedOnly"

    // Swinger dealbreakers: keep the swap / room / experience group of the lifestyle filter, or
    // the boundary filter, through deck relaxation.
    const val SWAP = "swap"
    const val ROOM = "room"
    const val EXPERIENCE = "experience"
    const val BOUNDARIES = "boundaries"

    val ALL: Set<String> =
        setOf(AGE, DISTANCE, VERIFIED, DISCOVERY_STYLES, INTENTS, LINKED_ONLY, SWAP, ROOM, EXPERIENCE, BOUNDARIES)
}

@Serializable
data class SwipeDeckRequestDto(
    val radiusMeters: Int = 25_000,
    val limit: Int = 30,
    val cursor: String? = null,
    val minimumAge: Int? = null,
    val maximumAge: Int? = null,
    val relationshipContexts: List<String> = emptyList(),
    val linkedOnly: Boolean? = null,
    val verifiedOnly: Boolean = false,
    val facePhotoVerifiedOnly: Boolean = false,
    val ageVerifiedOnly: Boolean = false,
    val discoveryStyles: List<String> = emptyList(),
    val intents: List<String> = emptyList(),
    val lifestyleTags: List<String> = emptyList(),
    // Boundary items (DiscoveryTagTaxonomies.boundaryItems): hide profiles that said "not for me".
    val boundaryItems: List<String> = emptyList(),
    val logisticsTags: List<String> = emptyList(),
    val genders: List<String> = emptyList(),
    val orientations: List<String> = emptyList(),
    val withPhotosOnly: Boolean = false,
    val hasPrivateAlbumOnly: Boolean = false,
    val dealbreakers: Set<String> = emptySet(),
    // FILTERS_SPEC L344-352 "Verified preferred (ranking weight)" — soft signal that
    // boosts verified targets in the deck ranking without filtering out unverified ones.
    // Distinct from verifiedOnly (hard filter).
    val verifiedPreferred: Boolean = false,
    // "Verified couples only": only people whose couple-verified badge shows for this viewer (both
    // linked partners selfie-verified, partner visible). Independent of linkedOnly; relaxes with the
    // "verified" dealbreaker like verifiedOnly. Old clients omit it.
    val verifiedCouplesOnly: Boolean = false,
)

@Serializable
data class SwipeCardDto(
    val userId: String,
    val displayName: String,
    val displayAge: Int? = null,
    val distanceLabel: String? = null,
    val distanceMeters: Int? = null,
    val photoUrl: String? = null,
    val photoUrls: List<String> = emptyList(),
    val linkedPartner: LinkedPartnerMiniDto? = null,
    val rankToken: String,
    val online: Boolean = false,
    val shortBio: String? = null,
    val tags: List<String> = emptyList(),
    val verified: Boolean = false,
    // Both this person and their visible linked partner are photo-verified (mirrors the Grid).
    val coupleVerified: Boolean = false,
    // SWIPE_IMPLEMENTATION_SPEC L348: target has an active travel mode row, so
    // they're virtually in the viewer's area rather than physically there.
    val visiting: Boolean = false,
)

@Serializable
data class SwipeDeckResponseDto(
    val deckSessionId: String = "",
    val generatedAt: String? = null,
    val items: List<SwipeCardDto>,
    val nextCursor: String? = null,
    // False on Free: the Linked / Age chips show as locked. Defaults open so a response
    // without it never locks a paid user out.
    val advancedFiltersAllowed: Boolean = true,
    val advancedFiltersApplied: Boolean = false,
)

@Serializable
data class SwipeActionRequestDto(
    val actionId: String = "",
    val deckSessionId: String = "",
    val cardToken: String = "",
    val targetUserId: String,
    val action: String,
    // Only on a Pineapple Like: an optional note of up to PineappleLikeNote.MAX_LENGTH characters.
    val noteText: String? = null,
)

@Serializable
data class MatchDto(
    val matchId: String,
    val matchedUserId: String,
    val matchedDisplayName: String? = null,
    val matchedPhotoUrl: String? = null,
    val createdAt: String,
)

@Serializable
data class SwipeActionResponseDto(
    val recorded: Boolean,
    val match: MatchDto? = null,
)

/** Like back someone who liked you, from Activity, without needing them in your Swipe deck. */
@Serializable
data class LikeBackRequestDto(
    val targetUserId: String,
)

@Serializable
data class SwipeRewindResponseDto(
    val rewound: Boolean,
    val deckSessionId: String = "",
    val card: SwipeCardDto? = null,
)

// FILTERS_SPEC L463-477: Saved filter presets, Swipe equivalent of `SavedGridFilter*`.
// Payload is the verbatim `SwipeDeckRequestDto` minus request-time fields (cursor)
// so the preset re-applies as a single fresh-deck request.
@Serializable
data class SavedSwipeFilterDto(
    val id: String,
    val name: String,
    val payload: SwipeDeckRequestDto,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class SavedSwipeFiltersListDto(
    val items: List<SavedSwipeFilterDto>,
)

@Serializable
data class CreateSavedSwipeFilterRequestDto(
    val name: String,
    val payload: SwipeDeckRequestDto,
)
