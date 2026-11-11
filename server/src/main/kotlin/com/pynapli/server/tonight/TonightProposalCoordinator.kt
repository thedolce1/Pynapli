package com.pynapli.server.tonight

import com.pynapli.server.notifications.NotificationEventDraft
import com.pynapli.server.notifications.NotificationKind
import com.pynapli.server.notifications.NotificationPublisher
import com.pynapli.server.safety.RiskPolicyService
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.shared.network.dto.ProposeTonightSessionRequestDto
import com.pynapli.shared.network.dto.SubscriptionTier
import com.pynapli.shared.network.dto.TonightSessionDto
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal class TonightProposalCoordinator(
    private val repository: TonightSessionRepository,
    private val subscriptionService: SubscriptionService?,
    private val notificationPublisher: NotificationPublisher?,
    private val riskPolicyService: RiskPolicyService?,
    private val clock: Clock,
) {
    suspend fun propose(
        userId: UUID,
        request: ProposeTonightSessionRequestDto,
    ): TonightSessionDto {
        // Starting a session is an outbound social signal: restricted tiers cannot
        // (TRUST_AND_SAFETY_SPEC). Declining/ending stays unrestricted.
        if (isSocialActionRestricted(userId)) throw TonightSessionException("RISK_RESTRICTED")

        val proposal = request.toValidatedProposal(userId)
        val now = clock.instant()
        if (repository.hasActiveOrProposedSession(userId, now)) {
            throw TonightSessionException("TONIGHT_ALREADY_ACTIVE")
        }

        enforceWeeklyCap(userId, now)

        val sessionId = UUID.randomUUID()
        val expiresAt = now.plus(Duration.ofHours(proposal.durationHours.toLong()))
        val session = buildSession(userId, sessionId, proposal, now, expiresAt)
        val persisted = repository.insertSession(session.value)

        session.partnerToNotify?.let { partnerId ->
            notificationPublisher?.enqueue(
                NotificationEventDraft(
                    recipientUserId = partnerId,
                    actorUserId = userId,
                    kind = NotificationKind.TONIGHT_INVITE,
                    subjectId = sessionId,
                    idempotencyKey = "tonight_invite:$sessionId:$partnerId",
                    payload = mapOf("target" to "tonight", "sessionId" to sessionId.toString()),
                ),
            )
        }

        return persisted.toDto()
    }

    suspend fun isSocialActionRestricted(userId: UUID): Boolean = riskPolicyService?.isSocialActionRestricted(userId) == true

    private suspend fun ProposeTonightSessionRequestDto.toValidatedProposal(userId: UUID): ValidatedTonightProposal {
        val mode = mode.trim().lowercase()
        if (mode !in TONIGHT_VALID_MODES) throw TonightSessionException("INVALID_TONIGHT_MODE")

        val hostMode = hostMode.trim().lowercase()
        if (hostMode !in TONIGHT_VALID_HOST_MODES) throw TonightSessionException("INVALID_TONIGHT_HOST_MODE")

        val intent = intent.trim().lowercase()
        if (intent !in TONIGHT_VALID_INTENTS) throw TonightSessionException("INVALID_TONIGHT_INTENT")
        val allowedIntentsForMode = if (mode == TONIGHT_MODE_SOLO) TONIGHT_SOLO_INTENTS else TONIGHT_TOGETHER_INTENTS
        if (intent !in allowedIntentsForMode) throw TonightSessionException("INVALID_TONIGHT_INTENT")

        val note = note?.trim()?.takeIf { it.isNotEmpty() }
        if (note != null && note.length > TONIGHT_MAX_NOTE_LENGTH) {
            throw TonightSessionException("TONIGHT_NOTE_TOO_LONG")
        }

        val durationHours = durationHours ?: TONIGHT_DEFAULT_DURATION_HOURS
        if (durationHours !in TONIGHT_MIN_DURATION_HOURS..TONIGHT_MAX_DURATION_HOURS) {
            throw TonightSessionException("INVALID_TONIGHT_DURATION")
        }

        val photoAssetId =
            photoMediaAssetId?.let { raw ->
                val parsed =
                    runCatching { UUID.fromString(raw.trim()) }
                        .getOrElse { throw TonightSessionException("TONIGHT_INVALID_PHOTO") }
                if (!repository.isPhotoOwnedBy(userId, parsed)) {
                    throw TonightSessionException("TONIGHT_INVALID_PHOTO")
                }
                parsed
            }

        return ValidatedTonightProposal(
            mode = mode,
            intent = intent,
            hostMode = hostMode,
            note = note,
            durationHours = durationHours,
            photoAssetId = photoAssetId,
            mapVisible = mapVisible,
        )
    }

    private suspend fun buildSession(
        userId: UUID,
        sessionId: UUID,
        proposal: ValidatedTonightProposal,
        now: Instant,
        expiresAt: Instant,
    ): BuiltTonightSession =
        when (proposal.mode) {
            TONIGHT_MODE_SOLO ->
                BuiltTonightSession(
                    value =
                        TonightSession(
                            id = sessionId,
                            createdByUserId = userId,
                            mode = proposal.mode,
                            intent = proposal.intent,
                            hostMode = proposal.hostMode,
                            note = proposal.note,
                            photoMediaAssetId = proposal.photoAssetId,
                            mapVisible = proposal.mapVisible,
                            status = TONIGHT_STATUS_ACTIVE,
                            createdAt = now,
                            startedAt = now,
                            expiresAt = expiresAt,
                            endedAt = null,
                            participants =
                                listOf(
                                    TonightSessionParticipant(
                                        userId = userId,
                                        role = TONIGHT_ROLE_OWNER,
                                        approvalStatus = TONIGHT_APPROVAL_APPROVED,
                                        approvedAt = now,
                                    ),
                                ),
                        ),
                    partnerToNotify = null,
                )

            else -> {
                val partnerId =
                    repository.findActiveLinkedPartner(userId)
                        ?: throw TonightSessionException("TONIGHT_NO_LINKED_PARTNER")
                BuiltTonightSession(
                    value =
                        TonightSession(
                            id = sessionId,
                            createdByUserId = userId,
                            mode = proposal.mode,
                            intent = proposal.intent,
                            hostMode = proposal.hostMode,
                            note = proposal.note,
                            photoMediaAssetId = proposal.photoAssetId,
                            mapVisible = proposal.mapVisible,
                            status = TONIGHT_STATUS_PROPOSED,
                            createdAt = now,
                            startedAt = null,
                            expiresAt = expiresAt,
                            endedAt = null,
                            participants =
                                listOf(
                                    TonightSessionParticipant(
                                        userId = userId,
                                        role = TONIGHT_ROLE_OWNER,
                                        approvalStatus = TONIGHT_APPROVAL_APPROVED,
                                        approvedAt = now,
                                    ),
                                    TonightSessionParticipant(
                                        userId = partnerId,
                                        role = TONIGHT_ROLE_PARTNER,
                                        approvalStatus = TONIGHT_APPROVAL_PENDING,
                                        approvedAt = null,
                                    ),
                                ),
                        ),
                    partnerToNotify = partnerId,
                )
            }
        }

    private suspend fun enforceWeeklyCap(
        userId: UUID,
        now: Instant,
    ) {
        val tier = subscriptionService?.getPolicy(userId)?.tier ?: SubscriptionTier.SELECT
        if (tier != SubscriptionTier.FREE) return
        val createdInWindow = repository.countSessionsCreatedSince(userId, now.minus(TONIGHT_WEEKLY_WINDOW))
        if (createdInWindow >= TONIGHT_FREE_WEEKLY_LIMIT) {
            throw TonightSessionException("TONIGHT_WEEKLY_LIMIT_REACHED")
        }
    }
}

private data class BuiltTonightSession(
    val value: TonightSession,
    val partnerToNotify: UUID?,
)

private data class ValidatedTonightProposal(
    val mode: String,
    val intent: String,
    val hostMode: String,
    val note: String?,
    val durationHours: Int,
    val photoAssetId: UUID?,
    val mapVisible: Boolean,
)
