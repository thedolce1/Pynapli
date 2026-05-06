package com.pynapli.server.profilelink

import com.pynapli.server.notifications.NotificationEventDraft
import com.pynapli.server.notifications.NotificationKind
import com.pynapli.server.notifications.NotificationOutboxRecord
import com.pynapli.server.notifications.NotificationPublisher
import com.pynapli.server.safety.BlockRecord
import com.pynapli.server.safety.BlockRepository
import com.pynapli.server.safety.BlockSummaryRecord
import com.pynapli.shared.network.dto.CreateProfileLinkInvitationRequestDto
import com.pynapli.shared.network.dto.ProfileLinkDirection
import com.pynapli.shared.network.dto.ProfileLinkStatus
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProfileLinkServiceTest {
    @Test
    fun createInvitationRejectsSelfTarget() = runBlocking {
        val user = uuid(1)
        val service = ProfileLinkService(InMemoryProfileLinkRepository(linkableUsers = setOf(user)))

        val error = assertFailsWith<ProfileLinkException> {
            service.createInvitation(user, CreateProfileLinkInvitationRequestDto(targetUserId = user.toString()))
        }

        assertEquals("PROFILE_LINK_SELF_FORBIDDEN", error.code)
    }

    @Test
    fun createInvitationRejectsBlockedPair() = runBlocking {
        val requester = uuid(10)
        val target = uuid(11)
        val service = ProfileLinkService(
            repository = InMemoryProfileLinkRepository(linkableUsers = setOf(requester, target)),
            blockRepository = StaticBlockRepository(blockedPairs = setOf(requester to target))
        )

        val error = assertFailsWith<ProfileLinkException> {
            service.createInvitation(requester, CreateProfileLinkInvitationRequestDto(targetUserId = target.toString()))
        }

        assertEquals("PROFILE_LINK_BLOCKED", error.code)
    }

    @Test
    fun createInvitationReturnsOutgoingAndRecipientCanSeeIncoming() = runBlocking {
        val requester = uuid(20)
        val target = uuid(21)
        val notifications = RecordingNotificationPublisher()
        val service = ProfileLinkService(
            repository = InMemoryProfileLinkRepository(linkableUsers = setOf(requester, target)),
            notificationPublisher = notifications
        )

        val requesterSummary = service.createInvitation(
            requester,
            CreateProfileLinkInvitationRequestDto(targetUserId = target.toString())
        )

        val outgoing = requesterSummary.outgoingInvites.single()
        assertEquals(target.toString(), outgoing.otherUserId)
        assertEquals(ProfileLinkDirection.OUTGOING, outgoing.direction)
        assertEquals(ProfileLinkStatus.PENDING, outgoing.status)

        val targetSummary = service.getSummary(target)
        val incoming = targetSummary.incomingInvites.single()
        assertEquals(requester.toString(), incoming.otherUserId)
        assertEquals(outgoing.linkId, incoming.linkId)
        assertEquals(ProfileLinkDirection.INCOMING, incoming.direction)
        assertEquals(NotificationKind.PROFILE_LINK_INVITE, notifications.events.single().kind)
        assertEquals(target, notifications.events.single().recipientUserId)
    }

    @Test
    fun acceptInvitationCreatesCurrentLinkForBothUsers() = runBlocking {
        val requester = uuid(30)
        val recipient = uuid(31)
        val notifications = RecordingNotificationPublisher()
        val service = ProfileLinkService(
            repository = InMemoryProfileLinkRepository(linkableUsers = setOf(requester, recipient)),
            notificationPublisher = notifications
        )
        service.createInvitation(requester, CreateProfileLinkInvitationRequestDto(targetUserId = recipient.toString()))
        val linkId = service.getSummary(recipient).incomingInvites.single().linkId

        val recipientSummary = service.acceptInvitation(recipient, linkId)

        val recipientCurrent = assertNotNull(recipientSummary.current)
        assertEquals(requester.toString(), recipientCurrent.otherUserId)
        assertEquals(ProfileLinkStatus.ACTIVE, recipientCurrent.status)
        assertEquals(ProfileLinkDirection.CURRENT, recipientCurrent.direction)
        assertEquals(emptyList(), recipientSummary.incomingInvites)
        assertEquals(emptyList(), recipientSummary.outgoingInvites)

        val requesterCurrent = assertNotNull(service.getSummary(requester).current)
        assertEquals(recipient.toString(), requesterCurrent.otherUserId)
        assertEquals(NotificationKind.PROFILE_LINK_ACCEPTED, notifications.events.last().kind)
        assertEquals(requester, notifications.events.last().recipientUserId)
    }

    @Test
    fun declineInvitationClearsPendingForBothUsers() = runBlocking {
        val requester = uuid(40)
        val recipient = uuid(41)
        val service = ProfileLinkService(InMemoryProfileLinkRepository(linkableUsers = setOf(requester, recipient)))
        service.createInvitation(requester, CreateProfileLinkInvitationRequestDto(targetUserId = recipient.toString()))
        val linkId = service.getSummary(recipient).incomingInvites.single().linkId

        val recipientSummary = service.declineInvitation(recipient, linkId)

        assertEquals(emptyList(), recipientSummary.incomingInvites)
        assertEquals(emptyList(), service.getSummary(requester).outgoingInvites)
    }

    @Test
    fun unlinkCurrentClearsBothDirections() = runBlocking {
        val requester = uuid(50)
        val recipient = uuid(51)
        val service = ProfileLinkService(InMemoryProfileLinkRepository(linkableUsers = setOf(requester, recipient)))
        service.createInvitation(requester, CreateProfileLinkInvitationRequestDto(targetUserId = recipient.toString()))
        val linkId = service.getSummary(recipient).incomingInvites.single().linkId
        service.acceptInvitation(recipient, linkId)

        val requesterSummary = service.unlinkCurrent(requester)

        assertNull(requesterSummary.current)
        assertNull(service.getSummary(recipient).current)
    }

    @Test
    fun createInvitationRejectsRecentLinkCyclingAfterUnlink() = runBlocking {
        val userA = uuid(52)
        val userB = uuid(53)
        val userC = uuid(54)
        val repository = InMemoryProfileLinkRepository(linkableUsers = setOf(userA, userB, userC))
        val service = ProfileLinkService(
            repository = repository,
            clock = Clock.fixed(Instant.parse("2026-04-29T12:00:10Z"), ZoneOffset.UTC),
            linkActivationCooldown = Duration.ofDays(7)
        )
        service.createInvitation(userA, CreateProfileLinkInvitationRequestDto(targetUserId = userB.toString()))
        val linkId = service.getSummary(userB).incomingInvites.single().linkId
        service.acceptInvitation(userB, linkId)
        service.unlinkCurrent(userA)

        val error = assertFailsWith<ProfileLinkException> {
            service.createInvitation(userA, CreateProfileLinkInvitationRequestDto(targetUserId = userC.toString()))
        }

        assertEquals("PROFILE_LINK_COOLDOWN", error.code)
    }

    @Test
    fun acceptInvitationRejectsRecentLinkCyclingAfterUnlink() = runBlocking {
        val userA = uuid(55)
        val userB = uuid(56)
        val userC = uuid(57)
        val repository = InMemoryProfileLinkRepository(linkableUsers = setOf(userA, userB, userC))
        val service = ProfileLinkService(
            repository = repository,
            clock = Clock.fixed(Instant.parse("2026-04-29T12:00:10Z"), ZoneOffset.UTC),
            linkActivationCooldown = Duration.ofDays(7)
        )
        service.createInvitation(userA, CreateProfileLinkInvitationRequestDto(targetUserId = userB.toString()))
        val activeInviteId = service.getSummary(userB).incomingInvites.single().linkId
        service.acceptInvitation(userB, activeInviteId)
        service.unlinkCurrent(userA)
        val oldInviteId = repository.seedPendingInvitation(userC, userA)

        val error = assertFailsWith<ProfileLinkException> {
            service.acceptInvitation(userA, oldInviteId)
        }

        assertEquals("PROFILE_LINK_COOLDOWN", error.code)
    }

    @Test
    fun createInvitationAllowsLinkingAfterCooldownWindow() = runBlocking {
        val userA = uuid(58)
        val userB = uuid(59)
        val userC = uuid(60)
        val repository = InMemoryProfileLinkRepository(linkableUsers = setOf(userA, userB, userC))
        val service = ProfileLinkService(
            repository = repository,
            clock = Clock.fixed(Instant.parse("2026-05-07T12:00:00Z"), ZoneOffset.UTC),
            linkActivationCooldown = Duration.ofDays(7)
        )
        service.createInvitation(userA, CreateProfileLinkInvitationRequestDto(targetUserId = userB.toString()))
        val linkId = service.getSummary(userB).incomingInvites.single().linkId
        service.acceptInvitation(userB, linkId)
        service.unlinkCurrent(userA)

        val summary = service.createInvitation(userA, CreateProfileLinkInvitationRequestDto(targetUserId = userC.toString()))

        assertEquals(userC.toString(), summary.outgoingInvites.single().otherUserId)
    }

    @Test
    fun createInvitationRejectsDuplicatePendingInvite() = runBlocking {
        val requester = uuid(61)
        val target = uuid(62)
        val service = ProfileLinkService(InMemoryProfileLinkRepository(linkableUsers = setOf(requester, target)))
        service.createInvitation(requester, CreateProfileLinkInvitationRequestDto(targetUserId = target.toString()))

        val error = assertFailsWith<ProfileLinkException> {
            service.createInvitation(target, CreateProfileLinkInvitationRequestDto(targetUserId = requester.toString()))
        }

        assertEquals("PROFILE_LINK_INVITE_EXISTS", error.code)
    }

    @Test
    fun createInvitationRejectsAlreadyLinkedUsers() = runBlocking {
        val userA = uuid(70)
        val userB = uuid(71)
        val userC = uuid(72)
        val repository = InMemoryProfileLinkRepository(linkableUsers = setOf(userA, userB, userC))
        val service = ProfileLinkService(repository)
        service.createInvitation(userA, CreateProfileLinkInvitationRequestDto(targetUserId = userB.toString()))
        val linkId = service.getSummary(userB).incomingInvites.single().linkId
        service.acceptInvitation(userB, linkId)

        val error = assertFailsWith<ProfileLinkException> {
            service.createInvitation(userA, CreateProfileLinkInvitationRequestDto(targetUserId = userC.toString()))
        }

        assertEquals("PROFILE_LINK_ALREADY_LINKED", error.code)
    }
}

private class InMemoryProfileLinkRepository(
    private val linkableUsers: Set<UUID>
) : ProfileLinkRepository {
    private val rows = mutableListOf<LinkRow>()
    private val baseTime = Instant.parse("2026-04-29T12:00:00Z")
    private var sequence = 0L

    override suspend fun getSummary(viewerUserId: UUID): ProfileLinksSummaryRecord =
        ProfileLinksSummaryRecord(
            current = rows
                .filter { it.userId == viewerUserId && it.status == ProfileLinkStatus.ACTIVE && it.unlinkedAt == null }
                .maxByOrNull { it.createdAt }
                ?.toRecord(ProfileLinkDirection.CURRENT, viewerUserId),
            incomingInvites = rows
                .filter { it.linkedUserId == viewerUserId && it.status == ProfileLinkStatus.PENDING && it.unlinkedAt == null }
                .sortedByDescending { it.createdAt }
                .map { it.toRecord(ProfileLinkDirection.INCOMING, viewerUserId) },
            outgoingInvites = rows
                .filter { it.userId == viewerUserId && it.status == ProfileLinkStatus.PENDING && it.unlinkedAt == null }
                .sortedByDescending { it.createdAt }
                .map { it.toRecord(ProfileLinkDirection.OUTGOING, viewerUserId) }
        )

    override suspend fun isLinkableUser(userId: UUID): Boolean = userId in linkableUsers

    override suspend fun hasActiveLink(userId: UUID): Boolean =
        rows.any {
            it.status == ProfileLinkStatus.ACTIVE &&
                it.unlinkedAt == null &&
                (it.userId == userId || it.linkedUserId == userId)
        }

    override suspend fun getPendingInvitationForRecipient(
        recipientUserId: UUID,
        linkId: UUID
    ): PendingProfileLinkInvitationRecord? =
        rows.firstOrNull {
            it.linkId == linkId &&
                it.linkedUserId == recipientUserId &&
                it.status == ProfileLinkStatus.PENDING &&
                it.unlinkedAt == null
        }?.toPendingRecord()

    override suspend fun findPendingBetween(userIdA: UUID, userIdB: UUID): PendingProfileLinkInvitationRecord? =
        rows.firstOrNull {
            it.status == ProfileLinkStatus.PENDING &&
                it.unlinkedAt == null &&
                ((it.userId == userIdA && it.linkedUserId == userIdB) ||
                    (it.userId == userIdB && it.linkedUserId == userIdA))
        }?.toPendingRecord()

    override suspend fun findRecentUnlinkForEitherUser(
        userIdA: UUID,
        userIdB: UUID,
        since: Instant
    ): ProfileLinkCooldownRecord? =
        rows.filter {
            it.status == ProfileLinkStatus.UNLINKED &&
                it.unlinkedAt != null &&
                !it.unlinkedAt!!.isBefore(since) &&
                (it.userId in setOf(userIdA, userIdB) || it.linkedUserId in setOf(userIdA, userIdB))
        }.maxByOrNull { checkNotNull(it.unlinkedAt) }?.let {
            ProfileLinkCooldownRecord(
                userId = it.userId,
                linkedUserId = it.linkedUserId,
                unlinkedAt = checkNotNull(it.unlinkedAt)
            )
        }

    override suspend fun createInvitation(
        requesterUserId: UUID,
        targetUserId: UUID,
        linkIdFactory: () -> UUID
    ): ProfileLinkRecord {
        val existing = rows.firstOrNull { it.userId == requesterUserId && it.linkedUserId == targetUserId }
        val row = if (existing != null && existing.status in setOf(ProfileLinkStatus.DECLINED, ProfileLinkStatus.UNLINKED)) {
            existing.status = ProfileLinkStatus.PENDING
            existing.acceptedAt = null
            existing.unlinkedAt = null
            existing
        } else {
            LinkRow(
                linkId = linkIdFactory(),
                userId = requesterUserId,
                linkedUserId = targetUserId,
                status = ProfileLinkStatus.PENDING,
                createdAt = nextInstant()
            ).also { rows.add(it) }
        }
        return row.toRecord(ProfileLinkDirection.OUTGOING, requesterUserId)
    }

    fun seedPendingInvitation(requesterUserId: UUID, targetUserId: UUID): String =
        LinkRow(
            linkId = UUID.randomUUID(),
            userId = requesterUserId,
            linkedUserId = targetUserId,
            status = ProfileLinkStatus.PENDING,
            createdAt = nextInstant()
        ).also { rows.add(it) }.linkId.toString()

    override suspend fun acceptInvitation(
        recipientUserId: UUID,
        linkId: UUID,
        reciprocalLinkIdFactory: () -> UUID
    ): ProfileLinkAcceptOutcome {
        val row = rows.firstOrNull {
            it.linkId == linkId &&
                it.linkedUserId == recipientUserId &&
                it.status == ProfileLinkStatus.PENDING &&
                it.unlinkedAt == null
        } ?: return ProfileLinkAcceptOutcome.NotFound

        if (hasActiveLink(row.userId) || hasActiveLink(row.linkedUserId)) {
            return ProfileLinkAcceptOutcome.AlreadyLinked
        }

        val acceptedAt = nextInstant()
        row.status = ProfileLinkStatus.ACTIVE
        row.acceptedAt = acceptedAt
        rows.add(
            LinkRow(
                linkId = reciprocalLinkIdFactory(),
                userId = row.linkedUserId,
                linkedUserId = row.userId,
                status = ProfileLinkStatus.ACTIVE,
                createdAt = row.createdAt,
                acceptedAt = acceptedAt
            )
        )
        rows.filter {
            it.status == ProfileLinkStatus.PENDING &&
                it.unlinkedAt == null &&
                (it.userId in setOf(row.userId, row.linkedUserId) ||
                    it.linkedUserId in setOf(row.userId, row.linkedUserId))
        }.forEach { it.status = ProfileLinkStatus.DECLINED }

        return ProfileLinkAcceptOutcome.Accepted
    }

    override suspend fun declineInvitation(recipientUserId: UUID, linkId: UUID): Boolean {
        val row = rows.firstOrNull {
            it.linkId == linkId &&
                it.linkedUserId == recipientUserId &&
                it.status == ProfileLinkStatus.PENDING &&
                it.unlinkedAt == null
        } ?: return false
        row.status = ProfileLinkStatus.DECLINED
        return true
    }

    override suspend fun unlinkCurrent(userId: UUID): Boolean {
        val current = rows.firstOrNull {
            it.userId == userId &&
                it.status == ProfileLinkStatus.ACTIVE &&
                it.unlinkedAt == null
        } ?: return false
        val unlinkedAt = nextInstant()
        rows.filter {
            it.status == ProfileLinkStatus.ACTIVE &&
                it.unlinkedAt == null &&
                ((it.userId == current.userId && it.linkedUserId == current.linkedUserId) ||
                    (it.userId == current.linkedUserId && it.linkedUserId == current.userId))
        }.forEach {
            it.status = ProfileLinkStatus.UNLINKED
            it.unlinkedAt = unlinkedAt
        }
        return true
    }

    private fun LinkRow.toRecord(direction: String, viewerUserId: UUID): ProfileLinkRecord {
        val otherUserId = when (direction) {
            ProfileLinkDirection.INCOMING -> userId
            else -> linkedUserId
        }
        require(viewerUserId == userId || viewerUserId == linkedUserId)
        return ProfileLinkRecord(
            linkId = linkId,
            otherUserId = otherUserId,
            otherDisplayName = null,
            otherPhotoMediaAssetId = null,
            status = status,
            direction = direction,
            createdAt = createdAt,
            acceptedAt = acceptedAt,
            unlinkedAt = unlinkedAt
        )
    }

    private fun LinkRow.toPendingRecord(): PendingProfileLinkInvitationRecord =
        PendingProfileLinkInvitationRecord(
            linkId = linkId,
            requesterUserId = userId,
            recipientUserId = linkedUserId
        )

    private fun nextInstant(): Instant = baseTime.plusMillis(sequence++)
}

private data class LinkRow(
    val linkId: UUID,
    val userId: UUID,
    val linkedUserId: UUID,
    var status: String,
    val createdAt: Instant,
    var acceptedAt: Instant? = null,
    var unlinkedAt: Instant? = null
)

private class StaticBlockRepository(
    private val blockedPairs: Set<Pair<UUID, UUID>>
) : BlockRepository {
    override suspend fun block(
        blockerUserId: UUID,
        blockedUserId: UUID,
        reason: String?,
        blockIdFactory: () -> UUID
    ): BlockRecord = throw UnsupportedOperationException("not used")

    override suspend fun unblock(blockerUserId: UUID, blockedUserId: UUID): Boolean = false

    override suspend fun listBlocks(blockerUserId: UUID): List<BlockSummaryRecord> = emptyList()

    override suspend fun isBlockedEitherWay(userIdA: UUID, userIdB: UUID): Boolean =
        blockedPairs.contains(userIdA to userIdB) || blockedPairs.contains(userIdB to userIdA)

    override suspend fun listBlockedRelatedUserIds(userId: UUID): Set<UUID> = emptySet()
}

private fun uuid(value: Int): UUID =
    UUID.fromString("00000000-0000-0000-0000-${value.toString().padStart(12, '0')}")

private class RecordingNotificationPublisher : NotificationPublisher {
    val events = mutableListOf<NotificationEventDraft>()

    override suspend fun enqueue(notification: NotificationEventDraft): NotificationOutboxRecord? {
        events += notification
        return null
    }
}
