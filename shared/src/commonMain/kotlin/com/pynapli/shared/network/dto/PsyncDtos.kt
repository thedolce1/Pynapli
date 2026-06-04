package com.pynapli.shared.network.dto

import kotlinx.serialization.Serializable

// PSYNC_SPEC: Psync synchronized-swipe session DTOs (PS-1 negotiation lifecycle only).
// Filters are the Swipe SwipeDeckRequestDto so the Psync deck reuses Swipe filtering;
// request-time fields (cursor) are stripped server-side before persistence.

@Serializable
data class CreatePsyncSessionRequestDto(
    val filters: SwipeDeckRequestDto = SwipeDeckRequestDto(),
    val deckSize: Int = 25,
)

@Serializable
data class SuggestPsyncFiltersRequestDto(
    val filters: SwipeDeckRequestDto,
)

@Serializable
data class PsyncParticipantDto(
    val userId: String,
    val displayName: String? = null,
    val role: String, // 'initiator' | 'partner'
    val consentStatus: String, // 'pending' | 'accepted' | 'denied'
    val approved: Boolean,
)

@Serializable
data class PsyncSessionDto(
    val id: String,
    val partnerLinkId: String,
    val initiatorUserId: String,
    val status: String, // invited | configuring | ready | active | paused | completed | expired | cancelled
    val filters: SwipeDeckRequestDto,
    val deckSize: Int,
    val createdAt: String,
    val startedAt: String? = null,
    val expiresAt: String,
    val participants: List<PsyncParticipantDto>,
)

@Serializable
data class CurrentPsyncSessionDto(
    val session: PsyncSessionDto? = null,
)

// PS-2: shared bounded deck + private per-partner swipes.
// The deck is shared (same cards/order for both partners); viewerAction reflects ONLY
// the calling partner's own recorded action — a partner's swipes are never exposed.

@Serializable
data class PsyncDeckResponseDto(
    val sessionId: String,
    val deckSize: Int,
    val cards: List<PsyncDeckCardDto>,
)

@Serializable
data class PsyncDeckCardDto(
    val card: SwipeCardDto,
    val cardToken: String,
    // The CALLING viewer's own recorded action for this card, or null if not yet acted.
    // Never the partner's action.
    val viewerAction: String? = null,
)

@Serializable
data class SubmitPsyncActionRequestDto(
    val cardToken: String,
    val action: String, // 'like' | 'pass' | 'pineapple_like'
)

@Serializable
data class PsyncActionResponseDto(
    val recorded: Boolean,
    val actedCount: Int,
    val deckSize: Int,
)
