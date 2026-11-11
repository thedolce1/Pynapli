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
    // Tapped Start once both approved. The deck is dealt when every participant has.
    val started: Boolean = false,
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
    // True when this action exhausted the deck for BOTH partners and the session was
    // auto-completed (matches resolved). The client should fetch the recap.
    val sessionCompleted: Boolean = false,
)

// PS-3: match resolution + recap (PSYNC_SPEC "Match Outcomes" / "Session Recap").
// Recap is recap, NOT a score: aggregate counts only. soloMatchCount is always the
// VIEWER's own solo passthrough matches (a partner's solo matches stay private).

@Serializable
data class PsyncMatchDto(
    val targetUserId: String,
    val displayName: String? = null,
    val kind: String, // 'together' | 'solo'
)

// PS-4b: realtime session channel + nudge. Convenience deltas only — they tell a partner
// that session state changed (partner started/finished, session activated/completed/paused/
// resumed, or a nudge). They NEVER carry swipe-by-swipe detail. HTTP fetch stays canonical.

@Serializable
data class PsyncRealtimeDeltaDto(
    val sessionId: String,
    // partner_started | session_active | partner_finished | session_completed | session_paused |
    // session_resumed | nudge | session_updated (setup changed before the deck started)
    val type: String,
    val occurredAt: String,
)

@Serializable
data class PsyncNudgeResponseDto(
    val sent: Boolean,
)

@Serializable
data class PsyncRecapDto(
    val sessionId: String,
    val status: String,
    val profilesReviewed: Int,
    val bothLikedCount: Int,
    val togetherMatchCount: Int,
    val soloMatchCount: Int, // the viewer's own solo passthrough matches
    // The partner's solo passthrough count — present ONLY if the partner opted to share
    // (discovery_settings.share_psync_solo_matches); null means private/not shared.
    val partnerSoloMatchCount: Int? = null,
    val startedAt: String? = null,
    val endedAt: String? = null,
    val filtersSummary: String,
    // Together matches are visible to both partners; listed for the recap/Activity card.
    val togetherMatches: List<PsyncMatchDto> = emptyList(),
)
