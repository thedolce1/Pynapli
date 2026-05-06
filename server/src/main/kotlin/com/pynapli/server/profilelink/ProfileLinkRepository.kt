package com.pynapli.server.profilelink

import java.time.Instant
import java.util.UUID

interface ProfileLinkRepository {
    suspend fun getSummary(viewerUserId: UUID): ProfileLinksSummaryRecord

    suspend fun isLinkableUser(userId: UUID): Boolean

    suspend fun hasActiveLink(userId: UUID): Boolean

    suspend fun getPendingInvitationForRecipient(
        recipientUserId: UUID,
        linkId: UUID
    ): PendingProfileLinkInvitationRecord?

    suspend fun findPendingBetween(userIdA: UUID, userIdB: UUID): PendingProfileLinkInvitationRecord?

    suspend fun findRecentUnlinkForEitherUser(
        userIdA: UUID,
        userIdB: UUID,
        since: Instant
    ): ProfileLinkCooldownRecord?

    suspend fun createInvitation(
        requesterUserId: UUID,
        targetUserId: UUID,
        linkIdFactory: () -> UUID = UUID::randomUUID
    ): ProfileLinkRecord

    suspend fun acceptInvitation(
        recipientUserId: UUID,
        linkId: UUID,
        reciprocalLinkIdFactory: () -> UUID = UUID::randomUUID
    ): ProfileLinkAcceptOutcome

    suspend fun declineInvitation(recipientUserId: UUID, linkId: UUID): Boolean

    suspend fun unlinkCurrent(userId: UUID): Boolean
}

data class ProfileLinksSummaryRecord(
    val current: ProfileLinkRecord?,
    val incomingInvites: List<ProfileLinkRecord>,
    val outgoingInvites: List<ProfileLinkRecord>
)

data class ProfileLinkRecord(
    val linkId: UUID,
    val otherUserId: UUID,
    val otherDisplayName: String?,
    val otherPhotoMediaAssetId: UUID?,
    val status: String,
    val direction: String,
    val createdAt: Instant,
    val acceptedAt: Instant?,
    val unlinkedAt: Instant?
)

data class PendingProfileLinkInvitationRecord(
    val linkId: UUID,
    val requesterUserId: UUID,
    val recipientUserId: UUID
)

data class ProfileLinkCooldownRecord(
    val userId: UUID,
    val linkedUserId: UUID,
    val unlinkedAt: Instant
)

enum class ProfileLinkAcceptOutcome {
    Accepted,
    NotFound,
    AlreadyLinked
}
