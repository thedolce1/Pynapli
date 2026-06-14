package com.pynapli.server.profilelink

import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcProfileLinkRepository(
    dataSource: DataSource,
) : ProfileLinkRepository {
    private val readActions = JdbcProfileLinkReadActions(dataSource)
    private val mutationActions = JdbcProfileLinkMutationActions(dataSource)

    override suspend fun getSummary(viewerUserId: UUID): ProfileLinksSummaryRecord = readActions.getSummary(viewerUserId)

    override suspend fun isLinkableUser(userId: UUID): Boolean = readActions.isLinkableUser(userId)

    override suspend fun hasActiveLink(userId: UUID): Boolean = readActions.hasActiveLink(userId)

    override suspend fun getPendingInvitationForRecipient(
        recipientUserId: UUID,
        linkId: UUID,
    ): PendingProfileLinkInvitationRecord? =
        readActions.getPendingInvitationForRecipient(
            recipientUserId = recipientUserId,
            linkId = linkId,
        )

    override suspend fun findPendingBetween(
        userIdA: UUID,
        userIdB: UUID,
    ): PendingProfileLinkInvitationRecord? =
        readActions.findPendingBetween(
            userIdA = userIdA,
            userIdB = userIdB,
        )

    override suspend fun findRecentUnlinkForEitherUser(
        userIdA: UUID,
        userIdB: UUID,
        since: Instant,
    ): ProfileLinkCooldownRecord? =
        readActions.findRecentUnlinkForEitherUser(
            userIdA = userIdA,
            userIdB = userIdB,
            since = since,
        )

    override suspend fun createInvitation(
        requesterUserId: UUID,
        targetUserId: UUID,
        linkIdFactory: () -> UUID,
    ): ProfileLinkRecord =
        mutationActions.createInvitation(
            requesterUserId = requesterUserId,
            targetUserId = targetUserId,
            linkIdFactory = linkIdFactory,
        )

    override suspend fun acceptInvitation(
        recipientUserId: UUID,
        linkId: UUID,
        reciprocalLinkIdFactory: () -> UUID,
    ): ProfileLinkAcceptOutcome =
        mutationActions.acceptInvitation(
            recipientUserId = recipientUserId,
            linkId = linkId,
            reciprocalLinkIdFactory = reciprocalLinkIdFactory,
        )

    override suspend fun declineInvitation(
        recipientUserId: UUID,
        linkId: UUID,
    ): Boolean =
        mutationActions.declineInvitation(
            recipientUserId = recipientUserId,
            linkId = linkId,
        )

    override suspend fun unlinkCurrent(userId: UUID): Boolean = mutationActions.unlinkCurrent(userId)
}
