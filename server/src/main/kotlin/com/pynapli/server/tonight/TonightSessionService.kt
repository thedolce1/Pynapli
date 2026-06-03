package com.pynapli.server.tonight

import com.pynapli.server.notifications.NotificationEventDraft
import com.pynapli.server.notifications.NotificationKind
import com.pynapli.server.notifications.NotificationPublisher
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.shared.network.dto.CurrentTonightSessionDto
import com.pynapli.shared.network.dto.ProposeTonightSessionRequestDto
import com.pynapli.shared.network.dto.SubscriptionTier
import com.pynapli.shared.network.dto.TonightSessionDto
import com.pynapli.shared.network.dto.TonightSessionParticipantDto
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Playing Tonight session product authority (PLAYING_TONIGHT_SPEC). Solo sessions go live
 * immediately; linked-together sessions require BOTH partners to approve before they go
 * live. Either partner can end one; declining/ending/block-report are never paywalled.
 * The repository handles storage; this service enforces every rule.
 */
class TonightSessionService(
    private val repository: TonightSessionRepository,
    private val subscriptionService: SubscriptionService?,
    private val notificationPublisher: NotificationPublisher? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun propose(
        userId: UUID,
        request: ProposeTonightSessionRequestDto,
    ): TonightSessionDto {
        val mode = request.mode.trim().lowercase()
        if (mode !in VALID_MODES) throw TonightSessionException("INVALID_TONIGHT_MODE")

        val hostMode = request.hostMode.trim().lowercase()
        if (hostMode !in VALID_HOST_MODES) throw TonightSessionException("INVALID_TONIGHT_HOST_MODE")

        val intent = request.intent.trim().lowercase()
        if (intent !in VALID_INTENTS) throw TonightSessionException("INVALID_TONIGHT_INTENT")
        val allowedIntentsForMode = if (mode == MODE_SOLO) SOLO_INTENTS else TOGETHER_INTENTS
        if (intent !in allowedIntentsForMode) throw TonightSessionException("INVALID_TONIGHT_INTENT")

        val note = request.note?.trim()?.takeIf { it.isNotEmpty() }
        if (note != null && note.length > MAX_NOTE_LENGTH) throw TonightSessionException("TONIGHT_NOTE_TOO_LONG")

        val durationHours = request.durationHours ?: DEFAULT_DURATION_HOURS
        if (durationHours !in MIN_DURATION_HOURS..MAX_DURATION_HOURS) {
            throw TonightSessionException("INVALID_TONIGHT_DURATION")
        }

        val photoAssetId =
            request.photoMediaAssetId?.let { raw ->
                val parsed =
                    runCatching { UUID.fromString(raw.trim()) }
                        .getOrElse { throw TonightSessionException("TONIGHT_INVALID_PHOTO") }
                if (!repository.isPhotoOwnedBy(userId, parsed)) {
                    throw TonightSessionException("TONIGHT_INVALID_PHOTO")
                }
                parsed
            }

        val now = clock.instant()
        if (repository.hasActiveOrProposedSession(userId, now)) {
            throw TonightSessionException("TONIGHT_ALREADY_ACTIVE")
        }

        enforceWeeklyCap(userId, now)

        val sessionId = UUID.randomUUID()
        val expiresAt = now.plus(Duration.ofHours(durationHours.toLong()))

        // Captured for the together path so we can notify the partner after persistence.
        var togetherPartnerId: UUID? = null

        val session =
            when (mode) {
                MODE_SOLO -> {
                    TonightSession(
                        id = sessionId,
                        createdByUserId = userId,
                        mode = mode,
                        intent = intent,
                        hostMode = hostMode,
                        note = note,
                        photoMediaAssetId = photoAssetId,
                        mapVisible = request.mapVisible,
                        status = "active",
                        createdAt = now,
                        startedAt = now,
                        expiresAt = expiresAt,
                        endedAt = null,
                        participants =
                            listOf(
                                TonightSessionParticipant(
                                    userId = userId,
                                    role = ROLE_OWNER,
                                    approvalStatus = "approved",
                                    approvedAt = now,
                                ),
                            ),
                    )
                }

                else -> {
                    val partnerId =
                        repository.findActiveLinkedPartner(userId)
                            ?: throw TonightSessionException("TONIGHT_NO_LINKED_PARTNER")
                    togetherPartnerId = partnerId
                    TonightSession(
                        id = sessionId,
                        createdByUserId = userId,
                        mode = mode,
                        intent = intent,
                        hostMode = hostMode,
                        note = note,
                        photoMediaAssetId = photoAssetId,
                        mapVisible = request.mapVisible,
                        status = "proposed",
                        createdAt = now,
                        startedAt = null,
                        expiresAt = expiresAt,
                        endedAt = null,
                        participants =
                            listOf(
                                TonightSessionParticipant(
                                    userId = userId,
                                    role = ROLE_OWNER,
                                    approvalStatus = "approved",
                                    approvedAt = now,
                                ),
                                TonightSessionParticipant(
                                    userId = partnerId,
                                    role = ROLE_PARTNER,
                                    approvalStatus = "pending",
                                    approvedAt = null,
                                ),
                            ),
                    )
                }
            }

        val persisted = repository.insertSession(session)

        // Best-effort: a notification failure must never break propose. Solo sessions notify
        // nobody (no partner to invite).
        togetherPartnerId?.let { partnerId ->
            notificationPublisher?.enqueue(
                NotificationEventDraft(
                    recipientUserId = partnerId,
                    actorUserId = userId,
                    kind = NotificationKind.TONIGHT_INVITE,
                    subjectId = sessionId,
                    idempotencyKey = "tonight_invite:$sessionId:$partnerId",
                    payload = mapOf("sessionId" to sessionId.toString()),
                ),
            )
        }

        return persisted.toDto()
    }

    suspend fun approve(
        sessionId: UUID,
        userId: UUID,
    ): TonightSessionDto {
        val now = clock.instant()
        return when (val outcome = repository.approveParticipant(sessionId, userId, now)) {
            is TonightSessionRepository.ApproveOutcome.Updated -> outcome.session.toDto()
            TonightSessionRepository.ApproveOutcome.SessionNotFound ->
                throw TonightSessionException("TONIGHT_SESSION_NOT_FOUND")
            TonightSessionRepository.ApproveOutcome.NotParticipant ->
                throw TonightSessionException("TONIGHT_NOT_PARTICIPANT")
            TonightSessionRepository.ApproveOutcome.NotPending ->
                throw TonightSessionException("TONIGHT_NOT_PENDING")
        }
    }

    suspend fun decline(
        sessionId: UUID,
        userId: UUID,
    ): TonightSessionDto {
        val now = clock.instant()
        return when (val outcome = repository.declineParticipant(sessionId, userId, now)) {
            is TonightSessionRepository.MutateOutcome.Updated -> outcome.session.toDto()
            TonightSessionRepository.MutateOutcome.SessionNotFound ->
                throw TonightSessionException("TONIGHT_SESSION_NOT_FOUND")
            TonightSessionRepository.MutateOutcome.NotParticipant ->
                throw TonightSessionException("TONIGHT_NOT_PARTICIPANT")
            TonightSessionRepository.MutateOutcome.NotPending ->
                throw TonightSessionException("TONIGHT_NOT_PENDING")
        }
    }

    suspend fun end(
        sessionId: UUID,
        userId: UUID,
    ): TonightSessionDto {
        val now = clock.instant()
        return when (val outcome = repository.endSession(sessionId, userId, now)) {
            is TonightSessionRepository.MutateOutcome.Updated -> outcome.session.toDto()
            // Not-a-participant and wrong-status both surface as not-found: an ender should
            // never learn a session exists if they cannot act on it.
            TonightSessionRepository.MutateOutcome.SessionNotFound,
            TonightSessionRepository.MutateOutcome.NotParticipant,
            TonightSessionRepository.MutateOutcome.NotPending,
            -> throw TonightSessionException("TONIGHT_SESSION_NOT_FOUND")
        }
    }

    suspend fun current(userId: UUID): CurrentTonightSessionDto {
        val now = clock.instant()
        val session = repository.findCurrentForUser(userId, now) ?: return CurrentTonightSessionDto(session = null)
        return CurrentTonightSessionDto(session = session.toDto())
    }

    /** For a future expiry-sweep worker. Returns rows swept to 'expired'. */
    suspend fun expireStale(): Int = repository.expireStale(clock.instant())

    /**
     * Atomically claim active sessions expiring within the 15-minute lead window (each at most
     * once — the repository stamps reminder_sent_at) and enqueue a TONIGHT_REMINDER to every
     * participant. Notifications are best-effort. Returns the number of sessions reminded.
     */
    suspend fun sendExpiryReminders(): Int {
        val targets = repository.claimSessionsDueForReminder(clock.instant(), REMINDER_LEAD_WINDOW)
        targets.forEach { target ->
            target.participantUserIds.forEach { participant ->
                notificationPublisher?.enqueue(
                    NotificationEventDraft(
                        recipientUserId = participant,
                        actorUserId = null,
                        kind = NotificationKind.TONIGHT_REMINDER,
                        subjectId = target.sessionId,
                        idempotencyKey = "tonight_reminder:${target.sessionId}:$participant",
                        payload = mapOf("sessionId" to target.sessionId.toString()),
                    ),
                )
            }
        }
        return targets.size
    }

    private suspend fun enforceWeeklyCap(
        userId: UUID,
        now: java.time.Instant,
    ) {
        val tier = subscriptionService?.getPolicy(userId)?.tier ?: SubscriptionTier.SELECT
        if (tier != SubscriptionTier.FREE) return
        val createdInWindow = repository.countSessionsCreatedSince(userId, now.minus(WEEKLY_WINDOW))
        if (createdInWindow >= FREE_WEEKLY_LIMIT) {
            throw TonightSessionException("TONIGHT_WEEKLY_LIMIT_REACHED")
        }
    }

    private fun TonightSession.toDto(): TonightSessionDto =
        TonightSessionDto(
            id = id.toString(),
            mode = mode,
            intent = intent,
            hostMode = hostMode,
            note = note,
            photoMediaAssetId = photoMediaAssetId?.toString(),
            mapVisible = mapVisible,
            status = status,
            createdAt = createdAt.toString(),
            startedAt = startedAt?.toString(),
            expiresAt = expiresAt.toString(),
            participants = participants.map { it.toDto() },
        )

    private fun TonightSessionParticipant.toDto(): TonightSessionParticipantDto =
        TonightSessionParticipantDto(
            userId = userId.toString(),
            displayName = displayName,
            role = role,
            approvalStatus = approvalStatus,
        )

    private companion object {
        const val MODE_SOLO = "solo"
        const val MODE_TOGETHER = "together"
        const val ROLE_OWNER = "owner"
        const val ROLE_PARTNER = "partner"
        const val MAX_NOTE_LENGTH = 100
        const val DEFAULT_DURATION_HOURS = 2
        const val MIN_DURATION_HOURS = 1
        const val MAX_DURATION_HOURS = 24
        const val FREE_WEEKLY_LIMIT = 3
        val WEEKLY_WINDOW: Duration = Duration.ofDays(7)

        // PLAYING_TONIGHT_SPEC: "Recommended reminder: 15 minutes before expiration."
        val REMINDER_LEAD_WINDOW: Duration = Duration.ofMinutes(15)
        val VALID_MODES = setOf(MODE_SOLO, MODE_TOGETHER)
        val VALID_HOST_MODES = setOf("hosting", "traveling", "public_meet_first")
        val VALID_INTENTS = setOf("solo", "together", "social", "event")
        val SOLO_INTENTS = setOf("solo", "social", "event")
        val TOGETHER_INTENTS = setOf("together", "social", "event")
    }
}

class TonightSessionException(
    val code: String,
) : RuntimeException(code)
