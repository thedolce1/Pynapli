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
