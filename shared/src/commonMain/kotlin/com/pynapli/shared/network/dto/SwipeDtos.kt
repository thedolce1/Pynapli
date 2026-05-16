package com.pynapli.shared.network.dto

import kotlinx.serialization.Serializable

object SwipeAction {
    const val LIKE = "like"
    const val PASS = "pass"
    const val PINEAPPLE_LIKE = "pineapple_like"
    const val INTRO_NOTE = "intro_note"
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

    val ALL: Set<String> = setOf(AGE, DISTANCE, VERIFIED, DISCOVERY_STYLES, INTENTS, LINKED_ONLY)
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
    val logisticsTags: List<String> = emptyList(),
    val dealbreakers: Set<String> = emptySet(),
    // FILTERS_SPEC L344-352 "Verified preferred (ranking weight)" — soft signal that
    // boosts verified targets in the deck ranking without filtering out unverified ones.
    // Distinct from verifiedOnly (hard filter).
    val verifiedPreferred: Boolean = false
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
    val verified: Boolean = false
)

@Serializable
data class SwipeDeckResponseDto(
    val deckSessionId: String = "",
    val generatedAt: String? = null,
    val items: List<SwipeCardDto>,
    val nextCursor: String? = null,
    val adSupported: Boolean = false,
    val advancedFiltersApplied: Boolean = false
)

@Serializable
data class SwipeActionRequestDto(
    val actionId: String = "",
    val deckSessionId: String = "",
    val cardToken: String = "",
    val targetUserId: String,
    val action: String,
    val noteText: String? = null
)

@Serializable
data class MatchDto(
    val matchId: String,
    val matchedUserId: String,
    val matchedDisplayName: String? = null,
    val matchedPhotoUrl: String? = null,
    val createdAt: String
)

@Serializable
data class SwipeActionResponseDto(
    val recorded: Boolean,
    val match: MatchDto? = null
)

@Serializable
data class SwipeRewindResponseDto(
    val rewound: Boolean,
    val deckSessionId: String = "",
    val card: SwipeCardDto? = null
)

@Serializable
data class MatchesListResponseDto(
    val matches: List<MatchDto>,
    val nextCursor: String? = null
)
