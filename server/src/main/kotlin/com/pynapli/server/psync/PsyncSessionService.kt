package com.pynapli.server.psync

import com.pynapli.shared.network.dto.CreatePsyncSessionRequestDto
import com.pynapli.shared.network.dto.CurrentPsyncSessionDto
import com.pynapli.shared.network.dto.PsyncActionResponseDto
import com.pynapli.shared.network.dto.PsyncDeckCardDto
import com.pynapli.shared.network.dto.PsyncDeckResponseDto
import com.pynapli.shared.network.dto.PsyncParticipantDto
import com.pynapli.shared.network.dto.PsyncSessionDto
import com.pynapli.shared.network.dto.SubmitPsyncActionRequestDto
import com.pynapli.shared.network.dto.SuggestPsyncFiltersRequestDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Psync session product authority (PSYNC_SPEC — PS-1 negotiation lifecycle only).
 *
 * "Psync is shared discovery without shared accounts." Both linked partners must consent,
 * and re-approve after any filter change, before a session is `ready`. This slice covers
 * create-invite -> accept / deny / suggest-filters (re-approval) -> both-approved = 'ready',
 * plus leave/cancel and invite expiry. The shared deck, start->active, swipes, matches and
 * recap are later phases. The repository handles storage; this service enforces every rule.
 *
 * Status machine (PS-1 transitions):
 *   create  -> 'invited' (initiator accepted+approved, partner pending)
 *   accept  -> 'invited'/'configuring' stays, then -> 'ready' once ALL approved
 *   suggest -> 'invited'/'configuring'/'ready' -> 'configuring' (resets approvals except suggester)
 *   deny    -> any non-terminal -> 'cancelled'
 *   leave   -> any non-terminal -> 'cancelled'
 *   expire  -> 'invited'/'configuring'/'ready'/'paused' past expires_at -> 'expired'
 */
class PsyncSessionService(
    private val repository: PsyncSessionRepository,
    private val deckGenerator: PsyncDeckGenerator? = null,
    private val blockRepository: com.pynapli.server.safety.BlockRepository? = null,
    private val hiddenProfileRepository: com.pynapli.server.safety.HiddenProfileRepository? = null,
    private val presenceService: com.pynapli.server.presence.PresenceService? = null,
    private val deckTokenCodec: com.pynapli.server.swipe.SwipeDeckTokenCodec =
        com.pynapli.server.swipe
            .SwipeDeckTokenCodec(),
    private val clock: Clock = Clock.systemUTC(),
    private val json: Json = Json,
) {
    suspend fun create(
        userId: UUID,
        request: CreatePsyncSessionRequestDto,
    ): PsyncSessionDto {
        if (request.deckSize !in MIN_DECK_SIZE..MAX_DECK_SIZE) {
            throw PsyncException("INVALID_PSYNC_DECK_SIZE")
        }

        val link =
            repository.findActiveLink(userId)
                ?: throw PsyncException("PSYNC_NO_LINKED_PARTNER")

        if (repository.hasNonTerminalSessionForLink(link.linkId)) {
            throw PsyncException("PSYNC_SESSION_ALREADY_ACTIVE")
        }

        val now = clock.instant()
        val sessionId = UUID.randomUUID()
        // Strip request-time fields (cursor) so the persisted filters re-apply as a fresh deck.
        val filters = request.filters.copy(cursor = null)

        val session =
            PsyncSession(
                id = sessionId,
                partnerLinkId = link.linkId,
                initiatorUserId = userId,
                status = STATUS_INVITED,
                filters = filters,
                deckSize = request.deckSize,
                deckSessionId = null,
                createdAt = now,
                startedAt = null,
                expiresAt = now.plus(INVITE_TTL),
                endedAt = null,
                participants = emptyList(),
            )

        val participants =
            listOf(
                PsyncSessionParticipant(
                    userId = userId,
                    role = ROLE_INITIATOR,
                    consentStatus = CONSENT_ACCEPTED,
                    approved = true,
                ),
                PsyncSessionParticipant(
                    userId = link.partnerUserId,
                    role = ROLE_PARTNER,
                    consentStatus = CONSENT_PENDING,
                    approved = false,
                ),
            )

        return repository.insertSession(session, participants).toDto()
    }

    suspend fun accept(
        sessionId: UUID,
        userId: UUID,
    ): PsyncSessionDto = repository.accept(sessionId, userId, clock.instant()).orThrow()

    suspend fun deny(
        sessionId: UUID,
        userId: UUID,
    ): PsyncSessionDto = repository.deny(sessionId, userId, clock.instant()).orThrow()

    suspend fun suggestFilters(
        sessionId: UUID,
        userId: UUID,
        request: SuggestPsyncFiltersRequestDto,
    ): PsyncSessionDto {
        val filtersJson =
            json.encodeToString(
                SwipeDeckRequestDto.serializer(),
                request.filters.copy(cursor = null),
            )
        return repository.suggestFilters(sessionId, userId, filtersJson, clock.instant()).orThrow()
    }

    suspend fun leave(
        sessionId: UUID,
        userId: UUID,
    ): PsyncSessionDto = repository.leave(sessionId, userId, clock.instant()).orThrow()

    /**
     * PS-2: both-tap-Start gate. Marks the caller started; once EVERY participant has
     * started, generates the shared bounded deck (ranked from the initiator with the agreed
     * filters, minus anyone blocked/hidden by EITHER partner + both partners themselves) and
     * flips the session to `active`. A single start leaves the session `ready`. Idempotent.
     */
    suspend fun start(
        sessionId: UUID,
        userId: UUID,
    ): PsyncSessionDto {
        val now = clock.instant()
        val outcome = repository.start(sessionId, userId, now)
        val started =
            when (outcome) {
                is PsyncSessionRepository.StartOutcome.Updated -> outcome
                PsyncSessionRepository.StartOutcome.SessionNotFound -> throw PsyncException("PSYNC_SESSION_NOT_FOUND")
                PsyncSessionRepository.StartOutcome.NotParticipant -> throw PsyncException("PSYNC_NOT_PARTICIPANT")
                PsyncSessionRepository.StartOutcome.InvalidState -> throw PsyncException("PSYNC_INVALID_STATE")
            }
        if (started.alreadyActive || !started.allStarted) {
            return started.session.toDto()
        }

        // All partners tapped Start -> generate the shared deck once and activate.
        val session = started.session
        val partnerId =
            session.participants.firstOrNull { it.userId != session.initiatorUserId }?.userId
                ?: throw PsyncException("PSYNC_INVALID_STATE")
        val generator = deckGenerator ?: throw PsyncException("PSYNC_DECK_EMPTY")

        val oversample = (session.deckSize * 2).coerceAtMost(MAX_RANK_LIMIT)
        val ranked = generator.rankCandidates(session.initiatorUserId, session.filters, oversample)

        // Cross-partner safety: drop anyone blocked/hidden by EITHER partner, plus both partners.
        val excluded =
            buildSet {
                add(session.initiatorUserId)
                add(partnerId)
                blockRepository?.let {
                    addAll(it.listBlockedRelatedUserIds(session.initiatorUserId))
                    addAll(it.listBlockedRelatedUserIds(partnerId))
                }
                hiddenProfileRepository?.let {
                    addAll(it.listHiddenProfileIds(session.initiatorUserId))
                    addAll(it.listHiddenProfileIds(partnerId))
                }
            }
        val deckCards =
            ranked
                .asSequence()
                .filter { runCatching { UUID.fromString(it.userId) }.getOrNull()?.let { id -> id !in excluded } ?: false }
                .distinctBy { it.userId }
                .take(session.deckSize)
                .toList()
        if (deckCards.isEmpty()) throw PsyncException("PSYNC_DECK_EMPTY")

        val deckSessionId = UUID.randomUUID()
        val cards =
            deckCards.mapIndexed { position, card ->
                val targetId = UUID.fromString(card.userId)
                val rawToken = issueCardToken(session, deckSessionId, targetId, position, now)
                PsyncSessionCard(
                    id = UUID.randomUUID(),
                    sessionId = sessionId,
                    position = position,
                    targetUserId = targetId,
                    cardTokenHash = deckTokenCodec.tokenHash(rawToken),
                    snapshot = card,
                    dealtAt = now,
                )
            }

        return when (val activated = repository.activateWithDeck(sessionId, deckSessionId, cards, now)) {
            is PsyncSessionRepository.MutateOutcome.Updated -> activated.session.toDto()
            else -> throw PsyncException("PSYNC_INVALID_STATE")
        }
    }

    /** The shared ordered deck for a participant; each card carries the viewer's OWN action only. */
    suspend fun getDeck(
        sessionId: UUID,
        userId: UUID,
    ): PsyncDeckResponseDto {
        val session = requireActiveParticipant(sessionId, userId, allowPaused = true)
        val cards = repository.listDeckCards(sessionId)
        val myActions = repository.listActionsForActor(sessionId, userId).associate { it.targetUserId to it.action }
        val online = onlineFor(cards.map { it.targetUserId })
        val items =
            cards.map { card ->
                val rawToken = issueCardToken(session, session.deckSessionId!!, card.targetUserId, card.position, session.startedAt!!)
                PsyncDeckCardDto(
                    card = card.snapshot.copy(online = card.targetUserId in online),
                    cardToken = rawToken,
                    viewerAction = myActions[card.targetUserId],
                )
            }
        return PsyncDeckResponseDto(sessionId = sessionId.toString(), deckSize = session.deckSize, cards = items)
    }

    /** Record the caller's PRIVATE decision on a deck card. Idempotent; never reveals the partner. */
    suspend fun submitAction(
        sessionId: UUID,
        userId: UUID,
        request: SubmitPsyncActionRequestDto,
    ): PsyncActionResponseDto {
        if (request.action !in VALID_ACTIONS) throw PsyncException("PSYNC_INVALID_ACTION")
        val session = requireActiveParticipant(sessionId, userId, allowPaused = false)
        val card =
            repository.findCardByTokenHash(sessionId, deckTokenCodec.tokenHash(request.cardToken))
                ?: throw PsyncException("PSYNC_INVALID_CARD")
        val outcome = repository.recordAction(sessionId, userId, card.targetUserId, request.action, clock.instant())
        return PsyncActionResponseDto(
            recorded = outcome.newlyRecorded,
            actedCount = outcome.actedCount,
            deckSize = session.deckSize,
        )
    }

    suspend fun pause(
        sessionId: UUID,
        userId: UUID,
    ): PsyncSessionDto = repository.transitionStatus(sessionId, userId, fromStatus = "active", toStatus = "paused").orThrow()

    suspend fun resume(
        sessionId: UUID,
        userId: UUID,
    ): PsyncSessionDto = repository.transitionStatus(sessionId, userId, fromStatus = "paused", toStatus = "active").orThrow()

    private suspend fun requireActiveParticipant(
        sessionId: UUID,
        userId: UUID,
        allowPaused: Boolean,
    ): PsyncSession {
        val session = repository.findById(sessionId, withDisplayNames = false) ?: throw PsyncException("PSYNC_SESSION_NOT_FOUND")
        if (session.participants.none { it.userId == userId }) throw PsyncException("PSYNC_SESSION_NOT_FOUND")
        val live = session.status == "active" || (allowPaused && session.status == "paused")
        if (!live) throw PsyncException("PSYNC_INVALID_STATE")
        return session
    }

    private fun issueCardToken(
        session: PsyncSession,
        deckSessionId: UUID,
        targetUserId: UUID,
        position: Int,
        generatedAt: java.time.Instant,
    ): String =
        deckTokenCodec.issueCardToken(
            deckSessionId = deckSessionId.toString(),
            viewerUserId = session.initiatorUserId,
            targetUserId = targetUserId,
            scopeHash = "psync:${session.id}",
            generatedAt = generatedAt,
            expiresAt = session.expiresAt,
            position = position,
        )

    private suspend fun onlineFor(userIds: List<UUID>): Set<UUID> = runCatching { presenceService?.onlineUserIds(userIds).orEmpty() }.getOrDefault(emptySet())

    /**
     * Fetch a session by id, scoped to its participants. A non-participant (or a missing
     * session) gets PSYNC_SESSION_NOT_FOUND so existence never leaks.
     */
    suspend fun get(
        sessionId: UUID,
        userId: UUID,
    ): PsyncSessionDto {
        // Reads expire stale sessions first so an elapsed session never reads as live.
        expireStale()
        val session = repository.findById(sessionId, withDisplayNames = true)
        if (session == null || session.participants.none { it.userId == userId }) {
            throw PsyncException("PSYNC_SESSION_NOT_FOUND")
        }
        return session.toDto()
    }

    suspend fun current(userId: UUID): CurrentPsyncSessionDto {
        // Sweep first so an expired session is never returned as current.
        expireStale()
        val session = repository.findCurrentForUser(userId, clock.instant()) ?: return CurrentPsyncSessionDto()
        return CurrentPsyncSessionDto(session = session.toDto())
    }

    /** Sweep non-terminal sessions past their expiry to 'expired'. Returns rows swept. */
    suspend fun expireStale(): Int = repository.expireStale(clock.instant())

    private fun PsyncSessionRepository.MutateOutcome.orThrow(): PsyncSessionDto =
        when (this) {
            is PsyncSessionRepository.MutateOutcome.Updated -> session.toDto()
            PsyncSessionRepository.MutateOutcome.SessionNotFound ->
                throw PsyncException("PSYNC_SESSION_NOT_FOUND")
            PsyncSessionRepository.MutateOutcome.NotParticipant ->
                throw PsyncException("PSYNC_NOT_PARTICIPANT")
            PsyncSessionRepository.MutateOutcome.InvalidState ->
                throw PsyncException("PSYNC_INVALID_STATE")
        }

    private fun PsyncSession.toDto(): PsyncSessionDto =
        PsyncSessionDto(
            id = id.toString(),
            partnerLinkId = partnerLinkId.toString(),
            initiatorUserId = initiatorUserId.toString(),
            status = status,
            filters = filters,
            deckSize = deckSize,
            createdAt = createdAt.toString(),
            startedAt = startedAt?.toString(),
            expiresAt = expiresAt.toString(),
            participants = participants.map { it.toDto() },
        )

    private fun PsyncSessionParticipant.toDto(): PsyncParticipantDto =
        PsyncParticipantDto(
            userId = userId.toString(),
            displayName = displayName,
            role = role,
            consentStatus = consentStatus,
            approved = approved,
        )

    private companion object {
        const val STATUS_INVITED = "invited"
        const val ROLE_INITIATOR = "initiator"
        const val ROLE_PARTNER = "partner"
        const val CONSENT_PENDING = "pending"
        const val CONSENT_ACCEPTED = "accepted"
        const val MIN_DECK_SIZE = 1
        const val MAX_DECK_SIZE = 50

        // Oversample ceiling for the ranker so cross-partner filtering still yields deckSize.
        const val MAX_RANK_LIMIT = 60
        val VALID_ACTIONS = setOf("like", "pass", "pineapple_like")

        // PSYNC_SPEC Edge Cases: "Partner ignores invite -> Invite expires." 24h window.
        val INVITE_TTL: Duration = Duration.ofHours(24)
    }
}

class PsyncException(
    val code: String,
) : RuntimeException(code)
