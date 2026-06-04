package com.pynapli.server.psync

import com.pynapli.shared.network.dto.CreatePsyncSessionRequestDto
import com.pynapli.shared.network.dto.CurrentPsyncSessionDto
import com.pynapli.shared.network.dto.PsyncActionResponseDto
import com.pynapli.shared.network.dto.PsyncDeckCardDto
import com.pynapli.shared.network.dto.PsyncDeckResponseDto
import com.pynapli.shared.network.dto.PsyncMatchDto
import com.pynapli.shared.network.dto.PsyncParticipantDto
import com.pynapli.shared.network.dto.PsyncRecapDto
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
    private val activityService: com.pynapli.server.activity.ActivityService? = null,
    private val notificationPublisher: com.pynapli.server.notifications.NotificationPublisher? = null,
    private val broadcaster: PsyncBroadcaster? = null,
    private val rateLimitService: com.pynapli.server.safety.RateLimitService? = null,
    private val subscriptionService: com.pynapli.server.subscriptions.SubscriptionService? = null,
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

        // PSYNC_SPEC Monetization: the deck size is capped by the initiator's tier (Free is small).
        subscriptionService?.let { subscriptions ->
            val policy = subscriptions.getPolicy(userId)
            if (request.deckSize > policy.psyncMaxDeckSize) {
                throw PsyncException("PSYNC_DECK_SIZE_NOT_ALLOWED")
            }
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
        if (started.alreadyActive) {
            return started.session.toDto()
        }
        if (!started.allStarted) {
            // Single tap: let the other partner know the caller is in.
            emitDelta(sessionId, PsyncBroadcaster.PARTNER_STARTED, otherParticipantIds(started.session, userId))
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
            is PsyncSessionRepository.MutateOutcome.Updated -> {
                // Both partners are in and the deck is live — tell both to load it.
                emitDelta(sessionId, PsyncBroadcaster.SESSION_ACTIVE, allParticipantIds(activated.session))
                recordPsyncActivityForBoth(activated.session, "psync_started")
                activated.session.toDto()
            }
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

        // Auto-complete when this action exhausts the deck for BOTH partners.
        val deckCount = repository.countDeckCards(sessionId)
        val completed =
            if (outcome.actedCount >= deckCount && bothPartnersFinished(session, userId, deckCount)) {
                val finish = repository.complete(sessionId, userId, clock.instant())
                if (finish is PsyncSessionRepository.CompleteOutcome.Completed &&
                    finish.session.status == STATUS_COMPLETED
                ) {
                    emitMatchActivity(finish.resolvedMatches)
                    emitDelta(sessionId, PsyncBroadcaster.SESSION_COMPLETED, allParticipantIds(session))
                    recordPsyncActivityForBoth(finish.session, "psync_recap")
                    true
                } else {
                    false
                }
            } else {
                false
            }

        // The caller just exhausted their own deck (but the partner has not) — nudge the UI.
        if (!completed && outcome.newlyRecorded && deckCount > 0 && outcome.actedCount >= deckCount) {
            emitDelta(sessionId, PsyncBroadcaster.PARTNER_FINISHED, otherParticipantIds(session, userId))
        }

        return PsyncActionResponseDto(
            recorded = outcome.newlyRecorded,
            actedCount = outcome.actedCount,
            deckSize = session.deckSize,
            sessionCompleted = completed,
        )
    }

    /** Fan out a convenience realtime delta to [recipients] (no-op without a broadcaster). */
    private suspend fun emitDelta(
        sessionId: UUID,
        type: String,
        recipients: List<UUID>,
    ) {
        val b = broadcaster ?: return
        val now = clock.instant()
        recipients.forEach { recipient ->
            b.emit(PsyncRealtimeDelta(recipientUserId = recipient, sessionId = sessionId, type = type, occurredAt = now))
        }
    }

    private fun allParticipantIds(session: PsyncSession): List<UUID> = session.participants.map { it.userId }

    private fun otherParticipantIds(
        session: PsyncSession,
        userId: UUID,
    ): List<UUID> = session.participants.map { it.userId }.filter { it != userId }

    private fun PsyncSessionDto.participantIds(): List<UUID> = participants.map { UUID.fromString(it.userId) }

    /** True once both participants have acted on every deck card (the auto-complete gate). */
    private suspend fun bothPartnersFinished(
        session: PsyncSession,
        actorUserId: UUID,
        deckCount: Int,
    ): Boolean {
        if (deckCount == 0) return false
        val partnerId = session.participants.firstOrNull { it.userId != actorUserId }?.userId ?: return false
        return repository.countActionsForActor(session.id, partnerId) >= deckCount
    }

    /**
     * Finish the session [userId] participates in: resolve matches, complete, and return the
     * recap. Either partner can finish early; auto-complete also routes through the repository
     * resolution. Idempotent — completing an already-completed session just returns its recap.
     */
    suspend fun complete(
        sessionId: UUID,
        userId: UUID,
    ): PsyncRecapDto {
        val outcome = repository.complete(sessionId, userId, clock.instant())
        val completed =
            when (outcome) {
                is PsyncSessionRepository.CompleteOutcome.Completed -> outcome
                PsyncSessionRepository.CompleteOutcome.SessionNotFound -> throw PsyncException("PSYNC_SESSION_NOT_FOUND")
                PsyncSessionRepository.CompleteOutcome.NotParticipant -> throw PsyncException("PSYNC_NOT_PARTICIPANT")
                PsyncSessionRepository.CompleteOutcome.InvalidState -> throw PsyncException("PSYNC_INVALID_STATE")
            }
        emitMatchActivity(completed.resolvedMatches)
        // Idempotent per (recipient, type, session) — safe on an idempotent re-complete.
        recordPsyncActivityForBoth(completed.session, "psync_recap")
        return buildRecap(completed.session, userId)
    }

    /** Record a Psync session card (started/recap/expired) for both partners, actor = the other. */
    private suspend fun recordPsyncActivityForBoth(
        session: PsyncSession,
        eventType: String,
    ) {
        val activity = activityService ?: return
        val ids = session.participants.map { it.userId }
        for (recipient in ids) {
            val other = ids.firstOrNull { it != recipient } ?: continue
            activity.recordPsyncSessionEvent(recipient, other, eventType, session.id)
        }
    }

    /**
     * Replay resolved matches into the existing match pipeline post-commit: each canonical match
     * lands in BOTH parties' Activity feed + realtime delta (via [ActivityService.recordMatch])
     * and enqueues a MATCH push to the target. PSYNC_SPEC: never streams individual swipes — only
     * the resolved match outcome surfaces. Empty on an idempotent re-complete (no double-emit).
     */
    private suspend fun emitMatchActivity(matches: List<PsyncResolvedMatch>) {
        val activity = activityService ?: return
        for (match in matches) {
            activity.recordMatch(match.partnerUserId, match.targetUserId, match.matchId)
            notificationPublisher?.enqueue(
                com.pynapli.server.notifications.NotificationEventDraft(
                    recipientUserId = match.targetUserId,
                    actorUserId = match.partnerUserId,
                    kind = com.pynapli.server.notifications.NotificationKind.MATCH,
                    subjectId = match.matchId,
                    idempotencyKey = "match:${match.matchId}:${match.targetUserId}",
                    payload =
                        mapOf(
                            "target" to "activity",
                            "activityTab" to "matches",
                            "activityEventType" to "match",
                            "matchId" to match.matchId.toString(),
                            "matchedUserId" to match.partnerUserId.toString(),
                        ),
                ),
            )
        }
    }

    /** The recap for a completed session the caller participated in (participant-scoped). */
    suspend fun getRecap(
        sessionId: UUID,
        userId: UUID,
    ): PsyncRecapDto {
        val session = repository.findById(sessionId) ?: throw PsyncException("PSYNC_SESSION_NOT_FOUND")
        if (session.participants.none { it.userId == userId }) throw PsyncException("PSYNC_SESSION_NOT_FOUND")
        if (session.status != STATUS_COMPLETED) throw PsyncException("PSYNC_INVALID_STATE")
        return buildRecap(session, userId)
    }

    /**
     * Recap is recap, NOT a score (PSYNC_SPEC): aggregate counts only. soloMatchCount is always
     * the VIEWER's OWN solo passthrough matches — a partner's solo matches stay private.
     */
    private suspend fun buildRecap(
        session: PsyncSession,
        viewerUserId: UUID,
    ): PsyncRecapDto {
        val matches = repository.listSessionMatches(session.id)
        val together = matches.filter { it.kind == "together" }
        val mySolo = matches.count { it.kind == "solo" && it.ownerUserId == viewerUserId }
        return PsyncRecapDto(
            sessionId = session.id.toString(),
            status = session.status,
            profilesReviewed = repository.countDeckCards(session.id),
            bothLikedCount = repository.countBothLiked(session.id),
            togetherMatchCount = together.size,
            soloMatchCount = mySolo,
            startedAt = session.startedAt?.toString(),
            endedAt = session.endedAt?.toString(),
            filtersSummary = filtersSummary(session.filters),
            togetherMatches =
                together.map {
                    PsyncMatchDto(
                        targetUserId = it.targetUserId.toString(),
                        displayName = it.displayName,
                        kind = it.kind,
                    )
                },
        )
    }

    /** A compact, human-readable one-line summary of the deck filters for the recap card. */
    private fun filtersSummary(filters: SwipeDeckRequestDto): String {
        val parts =
            buildList {
                add("${filters.radiusMeters / 1000}km")
                val lo = filters.minimumAge
                val hi = filters.maximumAge
                when {
                    lo != null && hi != null -> add("ages $lo-$hi")
                    lo != null -> add("ages $lo+")
                    hi != null -> add("ages up to $hi")
                }
                if (filters.verifiedOnly) add("verified only")
                if (filters.discoveryStyles.isNotEmpty()) add(filters.discoveryStyles.joinToString("/"))
                if (filters.intents.isNotEmpty()) add(filters.intents.joinToString("/"))
                val tagCount = filters.lifestyleTags.size + filters.logisticsTags.size
                if (tagCount > 0) add("$tagCount tags")
            }
        return parts.joinToString(" · ")
    }

    suspend fun pause(
        sessionId: UUID,
        userId: UUID,
    ): PsyncSessionDto {
        val dto = repository.transitionStatus(sessionId, userId, fromStatus = "active", toStatus = "paused").orThrow()
        emitDelta(sessionId, PsyncBroadcaster.SESSION_PAUSED, dto.participantIds())
        return dto
    }

    suspend fun resume(
        sessionId: UUID,
        userId: UUID,
    ): PsyncSessionDto {
        val dto = repository.transitionStatus(sessionId, userId, fromStatus = "paused", toStatus = "active").orThrow()
        emitDelta(sessionId, PsyncBroadcaster.SESSION_RESUMED, dto.participantIds())
        return dto
    }

    /**
     * PS-4b: nudge the other partner to keep swiping. Convenience-only realtime ping, rate
     * limited to a short cooldown (see [RateLimitedAction.PSYNC_NUDGE]); never reveals swipes.
     */
    suspend fun nudge(
        sessionId: UUID,
        userId: UUID,
    ): com.pynapli.shared.network.dto.PsyncNudgeResponseDto {
        val session = requireActiveParticipant(sessionId, userId, allowPaused = true)
        if (rateLimitService
                ?.check(userId, com.pynapli.server.safety.RateLimitedAction.PSYNC_NUDGE)
                ?.allowed == false
        ) {
            throw PsyncException("PSYNC_NUDGE_COOLDOWN")
        }
        emitDelta(sessionId, PsyncBroadcaster.NUDGE, otherParticipantIds(session, userId))
        return com.pynapli.shared.network.dto
            .PsyncNudgeResponseDto(sent = true)
    }

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
    suspend fun expireStale(): Int {
        val expired = repository.expireStale(clock.instant())
        val activity = activityService
        if (activity != null) {
            for (session in expired) {
                for (recipient in session.participantUserIds) {
                    val other = session.participantUserIds.firstOrNull { it != recipient } ?: continue
                    activity.recordPsyncSessionEvent(recipient, other, "psync_expired", session.sessionId)
                }
            }
        }
        return expired.size
    }

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
        const val STATUS_COMPLETED = "completed"
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
