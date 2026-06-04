package com.pynapli.server.psync

import com.pynapli.shared.network.dto.CreatePsyncSessionRequestDto
import com.pynapli.shared.network.dto.CurrentPsyncSessionDto
import com.pynapli.shared.network.dto.PsyncParticipantDto
import com.pynapli.shared.network.dto.PsyncSessionDto
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

        // PSYNC_SPEC Edge Cases: "Partner ignores invite -> Invite expires." 24h window.
        val INVITE_TTL: Duration = Duration.ofHours(24)
    }
}

class PsyncException(
    val code: String,
) : RuntimeException(code)
