package com.pynapli.server.tonight

import com.pynapli.server.notifications.NotificationPublisher
import com.pynapli.server.presence.PresenceService
import com.pynapli.server.safety.RiskPolicyService
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.shared.network.dto.CurrentTonightSessionDto
import com.pynapli.shared.network.dto.ProposeTonightSessionRequestDto
import com.pynapli.shared.network.dto.TonightSessionDto
import java.time.Clock
import java.util.UUID

/**
 * Playing Tonight session product authority (PLAYING_TONIGHT_SPEC). Solo sessions go live
 * immediately; linked-together sessions require BOTH partners to approve before they go
 * live. Either partner can end one; declining/ending/block-report are never paywalled.
 * The repository handles storage; this service enforces every rule.
 */
class TonightSessionService(
    private val repository: TonightSessionRepository,
    subscriptionService: SubscriptionService?,
    notificationPublisher: NotificationPublisher? = null,
    presenceService: PresenceService? = null,
    riskPolicyService: RiskPolicyService? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val proposalCoordinator =
        TonightProposalCoordinator(
            repository = repository,
            subscriptionService = subscriptionService,
            notificationPublisher = notificationPublisher,
            riskPolicyService = riskPolicyService,
            clock = clock,
        )
    private val reminderSender = TonightReminderSender(repository, notificationPublisher, clock)
    private val presenceReconciler = TonightPresenceReconciler(repository, presenceService, clock)

    suspend fun propose(
        userId: UUID,
        request: ProposeTonightSessionRequestDto,
    ): TonightSessionDto = proposalCoordinator.propose(userId, request)

    suspend fun approve(
        sessionId: UUID,
        userId: UUID,
    ): TonightSessionDto {
        // Approving makes the approver publicly live too — same outbound-social
        // restriction as proposing. Declining stays unrestricted.
        if (proposalCoordinator.isSocialActionRestricted(userId)) {
            throw TonightSessionException("RISK_RESTRICTED")
        }
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
    suspend fun sendExpiryReminders(): Int = reminderSender.sendExpiryReminders()

    /**
     * Presence-driven pause/end for linked-together sessions (PLAYING_TONIGHT_SPEC Edge Cases:
     * "Partner goes offline: pause linked-together session briefly, then end if not restored").
     *
     * Applies ONLY to mode='together', status='active', not-yet-expired sessions. Solo sessions are
     * never paused/ended by presence.
     */
    suspend fun reconcilePresence(): TonightReconcileSummary = presenceReconciler.reconcilePresence()
}

class TonightSessionException(
    val code: String,
) : RuntimeException(code)

/** Counts from one [TonightSessionService.reconcilePresence] pass. */
data class TonightReconcileSummary(
    val paused: Int = 0,
    val resumed: Int = 0,
    val ended: Int = 0,
) {
    val changed: Boolean get() = paused > 0 || resumed > 0 || ended > 0
}
