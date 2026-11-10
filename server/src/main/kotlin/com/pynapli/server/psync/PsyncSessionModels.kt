package com.pynapli.server.psync

import com.pynapli.shared.network.dto.SwipeCardDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import java.time.Instant
import java.util.UUID

/**
 * Server-internal domain model mirroring `psync_sessions`. Filters are the Swipe
 * [SwipeDeckRequestDto] persisted as JSONB. PS-1 only takes a session up to 'ready';
 * deckSessionId/startedAt are reserved for later phases and stay null here.
 */
data class PsyncSession(
    val id: UUID,
    val partnerLinkId: UUID,
    val initiatorUserId: UUID,
    val status: String,
    val filters: SwipeDeckRequestDto,
    val deckSize: Int,
    val deckSessionId: UUID?,
    val createdAt: Instant,
    val startedAt: Instant?,
    val expiresAt: Instant,
    val endedAt: Instant?,
    val participants: List<PsyncSessionParticipant>,
)

/**
 * Server-internal domain model mirroring `psync_session_participants`, plus the joined
 * display name used when projecting "my current session".
 */
data class PsyncSessionParticipant(
    val userId: UUID,
    val role: String,
    val consentStatus: String,
    val approved: Boolean,
    val displayName: String? = null,
    // PS-2: the both-tap-Start gate. The deck is generated + the session flips to
    // 'active' only when every participant has started.
    val started: Boolean = false,
)

/**
 * PS-2 shared deck card (mirrors `psync_session_cards`). One row per card; both partners
 * act on the same ordered list. The [snapshot] is the SwipeCardDto captured at generation
 * time (bounded, short-lived deck — staleness is acceptable). The [cardTokenHash] is the
 * hash of the HMAC card token used to validate submitted actions against this deck.
 */
data class PsyncSessionCard(
    val id: UUID,
    val sessionId: UUID,
    val position: Int,
    val targetUserId: UUID,
    val cardTokenHash: String,
    val snapshot: SwipeCardDto,
    val dealtAt: Instant,
)

/**
 * PS-2 private per-partner swipe (mirrors `psync_session_actions`). One decision per card
 * per partner; the actor's own decisions are never exposed to the other partner.
 */
data class PsyncSessionAction(
    val actorUserId: UUID,
    val targetUserId: UUID,
    val action: String,
    val createdAt: Instant,
)

/**
 * PS-3 resolved match outcome (mirrors `psync_session_matches`). [kind] is 'together'
 * (both partners + target together-mode consent; [ownerUserId] null, belongs to both) or
 * 'solo' (one partner's solo passthrough; [ownerUserId] is that partner). [displayName] is
 * joined for the recap/Activity card.
 */
data class PsyncSessionMatch(
    val targetUserId: UUID,
    val kind: String,
    val ownerUserId: UUID?,
    val displayName: String? = null,
)

/**
 * PS-4c: a session the expiry sweep just flipped to 'expired', with its participants, so the
 * service can surface a "Psync session expired" Activity card to each partner.
 */
data class PsyncExpiredSession(
    val sessionId: UUID,
    val participantUserIds: List<UUID>,
)

/**
 * PS-4: one canonical match created/kept by session resolution, paired with the partner it
 * belongs to. A together match yields two of these (one per partner ↔ target); a solo
 * passthrough yields one. The service replays these post-commit into the Activity +
 * notification + realtime match pipeline. Never carries swipe-by-swipe detail.
 */
data class PsyncResolvedMatch(
    val partnerUserId: UUID,
    val targetUserId: UUID,
    val matchId: UUID,
    val kind: String,
)

/**
 * A partner's Psync like that completion delivered to the target as a normal swipe like without a
 * match yet (the target has not liked that partner back). The service replays these post-commit
 * into the target's Likes You. [action] is `like` or `pineapple_like`. Passes are never delivered.
 */
data class PsyncDeliveredLike(
    val actorUserId: UUID,
    val targetUserId: UUID,
    val action: String,
)

/** A started session past its deadline, with the initiator to resolve it on behalf of. */
data class PsyncOverdueSession(
    val sessionId: UUID,
    val initiatorUserId: UUID,
)

/** What resolving a completed deck produced: canonical matches plus likes still awaiting an answer. */
data class PsyncResolution(
    val matches: List<PsyncResolvedMatch>,
    val deliveredLikes: List<PsyncDeliveredLike>,
)
