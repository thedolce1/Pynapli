package com.pynapli.shared.network.dto

import kotlinx.serialization.Serializable

object SwipeAction {
    const val LIKE = "like"
    const val PASS = "pass"
    const val PINEAPPLE_LIKE = "pineapple_like"
    const val INTRO_NOTE = "intro_note"
}

@Serializable
data class SwipeDeckRequestDto(
    val radiusMeters: Int = 25_000,
    val limit: Int = 30,
    val minimumAge: Int? = null,
    val maximumAge: Int? = null,
    val relationshipContexts: List<String> = emptyList(),
    val linkedOnly: Boolean? = null
)

@Serializable
data class SwipeCardDto(
    val userId: String,
    val displayName: String,
    val displayAge: Int? = null,
    val distanceLabel: String? = null,
    val distanceMeters: Int? = null,
    val photoUrl: String? = null,
    val linkedPartner: LinkedPartnerMiniDto? = null,
    val rankToken: String
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
data class MatchesListResponseDto(
    val matches: List<MatchDto>,
    val nextCursor: String? = null
)
