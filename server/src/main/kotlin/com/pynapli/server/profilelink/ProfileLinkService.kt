package com.pynapli.server.profilelink

import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.notifications.NotificationEventDraft
import com.pynapli.server.notifications.NotificationKind
import com.pynapli.server.notifications.NotificationPublisher
import com.pynapli.server.safety.BlockRepository
import com.pynapli.server.safety.RiskPolicyService
import com.pynapli.shared.network.dto.CreateProfileLinkInvitationRequestDto
import com.pynapli.shared.network.dto.ProfileLinkDto
import com.pynapli.shared.network.dto.ProfileLinksSummaryDto
import java.time.Clock
import java.time.Duration
import java.util.UUID

class ProfileLinkService(
    private val repository: ProfileLinkRepository,
    private val mediaAssetUrlResolver: MediaAssetUrlResolver? = null,
    private val blockRepository: BlockRepository? = null,
    private val notificationPublisher: NotificationPublisher? = null,
    private val riskPolicyService: RiskPolicyService? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val linkActivationCooldown: Duration = LINK_ACTIVATION_COOLDOWN,
    // "Hide from people you know": an invite from someone hiding from the viewer is never shown to the
    // viewer and can't be answered by them; the sender sees it pending, as if unanswered.
    private val contactBlockVisibility: com.pynapli.server.contactblocks.ContactBlockVisibility? = null,
) {
    /** The member's own view of their links. */
    suspend fun getSummary(viewerUserId: UUID): ProfileLinksSummaryDto {
        val summary = repository.getSummary(viewerUserId)
        // Invites from someone hiding from the viewer, and any they sent before removing the address.
        val rules = contactBlockVisibility?.rulesFor(viewerUserId) ?: return summary.toDto()
        if (rules.isEmpty) return summary.toDto()
        return summary.copy(incomingInvites = summary.incomingInvites.filterNot { rules.hidesAct(it.otherUserId, it.createdAt) }).toDto()
    }

    /** Support and moderation tooling: everything, unfiltered (contact blocks never hide from staff). */
    suspend fun getSummaryForAdmin(userId: UUID): ProfileLinksSummaryDto = repository.getSummary(userId).toDto()

    suspend fun createInvitation(
        requesterUserId: UUID,
        request: CreateProfileLinkInvitationRequestDto,
    ): ProfileLinksSummaryDto {
        // Inviting a partner is an outbound social action (TRUST_AND_SAFETY_SPEC);
        // accepting/declining an inbound invite stays unrestricted.
        if (riskPolicyService?.isSocialActionRestricted(requesterUserId) == true) {
            throw ProfileLinkException("RISK_RESTRICTED")
        }
        val targetUserId = request.targetUserId.toUuidOrThrow()
        validateCanRequestLink(requesterUserId, targetUserId)

        val invitation = repository.createInvitation(requesterUserId, targetUserId)
        // An invite the target sees: if they hide from the requester (a bind that raced it), the pair is
        // exempt from now on.
        contactBlockVisibility?.noteActToward(requesterUserId, targetUserId)
        notificationPublisher?.enqueue(
            NotificationEventDraft(
                recipientUserId = targetUserId,
                actorUserId = requesterUserId,
                kind = NotificationKind.PROFILE_LINK_INVITE,
                subjectId = invitation.linkId,
                idempotencyKey = "profile_link_invite:${invitation.linkId}:$targetUserId",
                // Opens the linked-partner panel, where the invite is accepted or declined.
                payload = mapOf("target" to "partner", "linkId" to invitation.linkId.toString()),
            ),
        )
        return getSummary(requesterUserId)
    }

    suspend fun acceptInvitation(
        viewerUserId: UUID,
        linkIdValue: String,
    ): ProfileLinksSummaryDto {
        val linkId = linkIdValue.toUuidOrThrow()
        val invitation =
            repository
                .getPendingInvitationForRecipient(viewerUserId, linkId)
                ?.takeUnless { contactBlockVisibility?.isHiddenFrom(viewerUserId, it.requesterUserId) == true }
                ?: throw ProfileLinkException("PROFILE_LINK_INVITE_NOT_FOUND")

        validatePairCanLink(invitation.requesterUserId, invitation.recipientUserId)
        validateLinkActivationCooldown(invitation.requesterUserId, invitation.recipientUserId)

        when (repository.acceptInvitation(viewerUserId, linkId)) {
            ProfileLinkAcceptOutcome.Accepted -> Unit
            ProfileLinkAcceptOutcome.NotFound -> throw ProfileLinkException("PROFILE_LINK_INVITE_NOT_FOUND")
            ProfileLinkAcceptOutcome.AlreadyLinked -> throw ProfileLinkException("PROFILE_LINK_ALREADY_LINKED")
        }
        notificationPublisher?.enqueue(
            NotificationEventDraft(
                recipientUserId = invitation.requesterUserId,
                actorUserId = viewerUserId,
                kind = NotificationKind.PROFILE_LINK_ACCEPTED,
                subjectId = linkId,
                idempotencyKey = "profile_link_accepted:$linkId:${invitation.requesterUserId}",
                payload = mapOf("target" to "partner", "linkId" to linkId.toString()),
            ),
        )

        return getSummary(viewerUserId)
    }

    suspend fun declineInvitation(
        viewerUserId: UUID,
        linkIdValue: String,
    ): ProfileLinksSummaryDto {
        val linkId = linkIdValue.toUuidOrThrow()
        // A hidden sender's invite can't be declined either: the sender would see the answer.
        if (contactBlockVisibility != null) {
            val invitation = repository.getPendingInvitationForRecipient(viewerUserId, linkId)
            if (invitation != null && contactBlockVisibility.isHiddenFrom(viewerUserId, invitation.requesterUserId)) {
                throw ProfileLinkException("PROFILE_LINK_INVITE_NOT_FOUND")
            }
        }
        if (!repository.declineInvitation(viewerUserId, linkId)) {
            throw ProfileLinkException("PROFILE_LINK_INVITE_NOT_FOUND")
        }
        return getSummary(viewerUserId)
    }

    suspend fun unlinkCurrent(viewerUserId: UUID): ProfileLinksSummaryDto {
        if (!repository.unlinkCurrent(viewerUserId)) {
            throw ProfileLinkException("PROFILE_LINK_NOT_FOUND")
        }
        return getSummary(viewerUserId)
    }

    private suspend fun validateCanRequestLink(
        requesterUserId: UUID,
        targetUserId: UUID,
    ) {
        if (requesterUserId == targetUserId) throw ProfileLinkException("PROFILE_LINK_SELF_FORBIDDEN")
        if (!repository.isLinkableUser(targetUserId)) throw ProfileLinkException("PROFILE_LINK_TARGET_NOT_FOUND")
        // Someone hiding from the requester: the same answer as a missing account.
        if (contactBlockVisibility?.isHiddenFrom(requesterUserId, targetUserId) == true) {
            throw ProfileLinkException("PROFILE_LINK_TARGET_NOT_FOUND")
        }
        validatePairCanLink(requesterUserId, targetUserId)
        validateLinkActivationCooldown(requesterUserId, targetUserId)
        repository.findPendingBetween(requesterUserId, targetUserId)?.let {
            throw ProfileLinkException("PROFILE_LINK_INVITE_EXISTS")
        }
    }

    private suspend fun validatePairCanLink(
        userIdA: UUID,
        userIdB: UUID,
    ) {
        if (blockRepository?.isBlockedEitherWay(userIdA, userIdB) == true) {
            throw ProfileLinkException("PROFILE_LINK_BLOCKED")
        }
        if (repository.hasActiveLink(userIdA) || repository.hasActiveLink(userIdB)) {
            throw ProfileLinkException("PROFILE_LINK_ALREADY_LINKED")
        }
    }

    private suspend fun validateLinkActivationCooldown(
        userIdA: UUID,
        userIdB: UUID,
    ) {
        val since = clock.instant().minus(linkActivationCooldown)
        if (repository.findRecentUnlinkForEitherUser(userIdA, userIdB, since) != null) {
            throw ProfileLinkException("PROFILE_LINK_COOLDOWN")
        }
    }

    private fun ProfileLinksSummaryRecord.toDto(): ProfileLinksSummaryDto =
        ProfileLinksSummaryDto(
            current = current?.toDto(),
            incomingInvites = incomingInvites.map { it.toDto() },
            outgoingInvites = outgoingInvites.map { it.toDto() },
        )

    private fun ProfileLinkRecord.toDto(): ProfileLinkDto =
        ProfileLinkDto(
            linkId = linkId.toString(),
            otherUserId = otherUserId.toString(),
            otherDisplayName = otherDisplayName,
            otherPhotoUrl = otherPhotoMediaAssetId?.let { mediaAssetUrlResolver?.publicContentUrl(it) },
            status = status,
            direction = direction,
            createdAt = createdAt.toString(),
            acceptedAt = acceptedAt?.toString(),
            unlinkedAt = unlinkedAt?.toString(),
        )

    private fun String.toUuidOrThrow(): UUID =
        runCatching { UUID.fromString(this) }.getOrElse {
            throw ProfileLinkException("INVALID_PROFILE_LINK_TARGET")
        }
}

class ProfileLinkException(
    val code: String,
) : RuntimeException(code)

private val LINK_ACTIVATION_COOLDOWN: Duration = Duration.ofDays(7)
