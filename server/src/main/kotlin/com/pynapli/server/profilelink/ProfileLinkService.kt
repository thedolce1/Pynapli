package com.pynapli.server.profilelink

import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.notifications.NotificationEventDraft
import com.pynapli.server.notifications.NotificationKind
import com.pynapli.server.notifications.NotificationPublisher
import com.pynapli.server.safety.BlockRepository
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
    private val clock: Clock = Clock.systemUTC(),
    private val linkActivationCooldown: Duration = LINK_ACTIVATION_COOLDOWN,
) {
    suspend fun getSummary(viewerUserId: UUID): ProfileLinksSummaryDto = repository.getSummary(viewerUserId).toDto()

    suspend fun createInvitation(
        requesterUserId: UUID,
        request: CreateProfileLinkInvitationRequestDto,
    ): ProfileLinksSummaryDto {
        val targetUserId = request.targetUserId.toUuidOrThrow()
        validateCanRequestLink(requesterUserId, targetUserId)

        val invitation = repository.createInvitation(requesterUserId, targetUserId)
        notificationPublisher?.enqueue(
            NotificationEventDraft(
                recipientUserId = targetUserId,
                actorUserId = requesterUserId,
                kind = NotificationKind.PROFILE_LINK_INVITE,
                subjectId = invitation.linkId,
                idempotencyKey = "profile_link_invite:${invitation.linkId}:$targetUserId",
                payload = mapOf("linkId" to invitation.linkId.toString()),
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
            repository.getPendingInvitationForRecipient(viewerUserId, linkId)
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
                payload = mapOf("linkId" to linkId.toString()),
            ),
        )

        return getSummary(viewerUserId)
    }

    suspend fun declineInvitation(
        viewerUserId: UUID,
        linkIdValue: String,
    ): ProfileLinksSummaryDto {
        val linkId = linkIdValue.toUuidOrThrow()
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
