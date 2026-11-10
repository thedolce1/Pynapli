package com.pynapli.server.psync

import com.pynapli.server.activity.ActivityService
import com.pynapli.server.safety.RiskPolicyService
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.shared.network.dto.CreatePsyncSessionRequestDto
import com.pynapli.shared.network.dto.CurrentPsyncSessionDto
import com.pynapli.shared.network.dto.PsyncSessionDto
import com.pynapli.shared.network.dto.SuggestPsyncFiltersRequestDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import kotlinx.serialization.json.Json
import java.time.Clock
import java.util.UUID

internal class PsyncLifecycleActions(
    private val repository: PsyncSessionRepository,
    private val activityService: ActivityService?,
    private val riskPolicyService: RiskPolicyService?,
    private val subscriptionService: SubscriptionService?,
    private val events: PsyncSessionEvents,
    private val pineappleRefunds: PsyncPineappleRefunds,
    private val clock: Clock,
    private val json: Json,
) {
    suspend fun create(
        userId: UUID,
        request: CreatePsyncSessionRequestDto,
    ): PsyncSessionDto {
        // A Psync session's swipes resolve into real matches with third parties,
        // so starting one is an outbound social action (TRUST_AND_SAFETY_SPEC).
        if (riskPolicyService?.isSocialActionRestricted(userId) == true) {
            throw PsyncException("RISK_RESTRICTED")
        }
        requirePsyncDeckSize(request.deckSize)

        // PSYNC_SPEC Monetization: the deck size is capped by the initiator's tier (Free is small).
        subscriptionService?.let { subscriptions ->
            val policy = subscriptions.getPolicy(userId)
            if (request.deckSize > policy.psyncMaxDeckSize) {
                throw PsyncException("PSYNC_DECK_SIZE_NOT_ALLOWED")
            }
            requirePsyncFiltersAllowed(request.filters, policy)
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
                status = PSYNC_STATUS_INVITED,
                filters = filters,
                deckSize = request.deckSize,
                deckSessionId = null,
                createdAt = now,
                startedAt = null,
                expiresAt = now.plus(PSYNC_INVITE_TTL),
                endedAt = null,
                participants = emptyList(),
            )

        val participants =
            listOf(
                PsyncSessionParticipant(
                    userId = userId,
                    role = PSYNC_ROLE_INITIATOR,
                    consentStatus = PSYNC_CONSENT_ACCEPTED,
                    approved = true,
                ),
                PsyncSessionParticipant(
                    userId = link.partnerUserId,
                    role = PSYNC_ROLE_PARTNER,
                    consentStatus = PSYNC_CONSENT_PENDING,
                    approved = false,
                ),
            )

        val created = repository.insertSession(session, participants)
        // PS-4c: the invitee sees a Psync invite card in Activity.
        activityService?.recordPsyncSessionEvent(
            recipientUserId = link.partnerUserId,
            actorUserId = userId,
            eventType = "psync_invite",
            sessionId = created.id,
        )
        return created.toDto()
    }

    suspend fun accept(
        sessionId: UUID,
        userId: UUID,
    ): PsyncSessionDto {
        // Accepting makes the partner an acting swiper too; denying stays open.
        if (riskPolicyService?.isSocialActionRestricted(userId) == true) {
            throw PsyncException("RISK_RESTRICTED")
        }
        return repository.accept(sessionId, userId, clock.instant()).toDtoOrThrow()
    }

    suspend fun deny(
        sessionId: UUID,
        userId: UUID,
    ): PsyncSessionDto = repository.deny(sessionId, userId, clock.instant()).toDtoOrThrow().also { pineappleRefunds.sweep() }

    suspend fun suggestFilters(
        sessionId: UUID,
        userId: UUID,
        request: SuggestPsyncFiltersRequestDto,
    ): PsyncSessionDto {
        // The re-suggested filters are gated by the suggester's tier too (Free = basic filters).
        subscriptionService?.let { requirePsyncFiltersAllowed(request.filters, it.getPolicy(userId)) }
        val filtersJson =
            json.encodeToString(
                SwipeDeckRequestDto.serializer(),
                request.filters.copy(cursor = null),
            )
        return repository.suggestFilters(sessionId, userId, filtersJson, clock.instant()).toDtoOrThrow()
    }

    suspend fun leave(
        sessionId: UUID,
        userId: UUID,
    ): PsyncSessionDto = repository.leave(sessionId, userId, clock.instant()).toDtoOrThrow().also { pineappleRefunds.sweep() }

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
    suspend fun expireStale(): Int {
        val expired = repository.expireStale(clock.instant())
        events.recordExpired(expired)
        // Also covers sessions cancelled elsewhere (a partner unlinking), which have no hook here.
        pineappleRefunds.sweep()
        return expired.size
    }
}
