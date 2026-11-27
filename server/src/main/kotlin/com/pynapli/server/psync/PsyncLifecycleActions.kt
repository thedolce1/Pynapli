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
    // Resolves started sessions past their deadline; returns how many.
    private val resolveOverdueStarted: suspend () -> Int,
    private val travelLocationRepository: com.pynapli.server.location.TravelLocationRepository?,
    private val clock: Clock,
    private val json: Json,
    // Lifestyle tags in Psync filters need an age-verified suggester (null in tests: allowed).
    private val viewerTrustRepository: com.pynapli.server.grid.GridViewerTrustRepository? = null,
) {
    private suspend fun ageVerified(userId: UUID): Boolean = viewerTrustRepository?.isAgeVerified(userId) ?: true

    /** Lifestyle filters shape what both partners see, so the other partner must be age-verified too. */
    private suspend fun requirePartnerAgeVerifiedForLifestyle(
        filters: com.pynapli.shared.network.dto.SwipeDeckRequestDto,
        partnerUserId: UUID,
    ) {
        if (filters.lifestyleTags.any { it.isNotBlank() } && !ageVerified(partnerUserId)) {
            throw PsyncException("PSYNC_PARTNER_AGE_VERIFICATION_REQUIRED")
        }
    }

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
        requirePsyncFiltersAllowed(request.filters) { ageVerified(userId) }

        // The deck is dealt around the initiator, at their Travel mode destination if one is on.
        val location = travelLocationRepository.psyncLocationFor(userId)

        // PSYNC_SPEC Monetization: the deck size is capped by the initiator's tier (Free is small).
        subscriptionService?.let { subscriptions ->
            val policy = subscriptions.getPolicy(userId)
            if (request.deckSize > policy.psyncMaxDeckSize) {
                throw PsyncException("PSYNC_DECK_SIZE_NOT_ALLOWED")
            }
            requirePsyncTravelAllowed(location, policy)
            // "Limited Psync sessions": cap how many the initiator may start per rolling 24 hours.
            if (policy.psyncDailySessionLimit != Int.MAX_VALUE) {
                val startedToday = repository.countStartedSessionsSince(userId, clock.instant().minus(PSYNC_SESSION_LIMIT_WINDOW))
                if (startedToday >= policy.psyncDailySessionLimit) {
                    throw PsyncException("PSYNC_SESSION_LIMIT_REACHED")
                }
            }
        }

        val link =
            repository.findActiveLink(userId)
                ?: throw PsyncException("PSYNC_NO_LINKED_PARTNER")

        if (repository.hasNonTerminalSessionForLink(link.linkId)) {
            throw PsyncException("PSYNC_SESSION_ALREADY_ACTIVE")
        }
        // Both partners swipe the deck, so lifestyle filters need both of them age-verified.
        requirePartnerAgeVerifiedForLifestyle(request.filters, link.partnerUserId)

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
                travelLocationId = location.travelLocationId,
                locationLabel = location.label,
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

        // The partner may have created one in the moments since the check above.
        val created =
            repository.insertSession(session, participants)
                ?: throw PsyncException("PSYNC_SESSION_ALREADY_ACTIVE")
        // PS-4c: the invitee sees a Psync invite card in Activity.
        activityService?.recordPsyncSessionEvent(
            recipientUserId = link.partnerUserId,
            actorUserId = userId,
            eventType = "psync_invite",
            sessionId = created.id,
        )
        events.notifyInvite(sessionId = created.id, inviterUserId = userId, inviteeUserId = link.partnerUserId)
        // If the partner already has Psync open, the invite appears without a manual refresh.
        events.emitDelta(created.id, PsyncBroadcaster.SESSION_UPDATED, listOf(link.partnerUserId))
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
        return repository.accept(sessionId, userId, clock.instant()).toDtoOrThrow().also { tellPartner(it, userId) }
    }

    suspend fun deny(
        sessionId: UUID,
        userId: UUID,
    ): PsyncSessionDto =
        repository.deny(sessionId, userId, clock.instant()).toDtoOrThrow().also {
            pineappleRefunds.sweep()
            tellPartner(it, userId)
        }

    suspend fun suggestFilters(
        sessionId: UUID,
        userId: UUID,
        request: SuggestPsyncFiltersRequestDto,
    ): PsyncSessionDto {
        // The re-suggested filters are validated and age-gated for the suggester, like the first set.
        requirePsyncFiltersAllowed(request.filters) { ageVerified(userId) }
        repository
            .findById(sessionId)
            ?.participants
            ?.filter { it.userId != userId }
            ?.forEach { other -> requirePartnerAgeVerifiedForLifestyle(request.filters, other.userId) }
        val filtersJson =
            json.encodeToString(
                SwipeDeckRequestDto.serializer(),
                request.filters.copy(cursor = null),
            )
        return repository.suggestFilters(sessionId, userId, filtersJson, clock.instant()).toDtoOrThrow().also { tellPartner(it, userId) }
    }

    suspend fun leave(
        sessionId: UUID,
        userId: UUID,
    ): PsyncSessionDto =
        repository.leave(sessionId, userId, clock.instant()).toDtoOrThrow().also {
            pineappleRefunds.sweep()
            tellPartner(it, userId)
        }

    /**
     * Tell the other partner the setup moved (accepted, declined, filters suggested, cancelled) so
     * a screen waiting on them updates at once. Without this they stay on "Waiting for ..." until
     * they reopen Psync.
     */
    private suspend fun tellPartner(
        session: PsyncSessionDto,
        actorUserId: UUID,
    ) = events.emitDelta(
        UUID.fromString(session.id),
        PsyncBroadcaster.SESSION_UPDATED,
        session.participantIds().filter { it != actorUserId },
    )

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

    /**
     * Close out every session past its deadline: a started one is resolved (decisions made so far
     * count), one that never started becomes 'expired'. Returns how many sessions were closed.
     */
    suspend fun expireStale(): Int {
        val resolved = resolveOverdueStarted()
        val expired = repository.expireStale(clock.instant())
        events.recordExpired(expired)
        expired.forEach { events.emitDelta(it.sessionId, PsyncBroadcaster.SESSION_UPDATED, it.participantUserIds) }
        // Also covers sessions cancelled elsewhere (a partner unlinking), which have no hook here.
        pineappleRefunds.sweep()
        return resolved + expired.size
    }
}
