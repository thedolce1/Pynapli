package com.pynapli.server.messaging

import com.pynapli.server.notifications.NotificationEventDraft
import com.pynapli.server.notifications.NotificationKind
import com.pynapli.server.notifications.NotificationOutboxRecord
import com.pynapli.server.notifications.NotificationPublisher
import com.pynapli.server.safety.MessageReviewRiskSignalPublisher
import com.pynapli.server.safety.RateLimitCounter
import com.pynapli.server.safety.RateLimitRepository
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.AccountRiskStateRecord
import com.pynapli.server.safety.RiskPolicyService
import com.pynapli.shared.network.dto.CreateDirectConversationRequestDto
import com.pynapli.shared.network.dto.ConversationParticipantStatus
import com.pynapli.shared.network.dto.SendMessageRequestDto
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MessagingServiceTest {
    @Test
    fun createDirectConversationReturnsConversationShell() = runBlocking {
        val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val targetId = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val service = MessagingService(InMemoryMessagingRepository(setOf(targetId)))

        val detail = service.createDirectConversation(
            viewerId,
            CreateDirectConversationRequestDto(targetUserId = targetId.toString(), origin = "grid")
        )

        assertEquals("direct", detail.conversation.kind)
        assertEquals("grid", detail.conversation.origin)
        assertEquals(2, detail.conversation.participants.size)
        assertEquals(emptyList(), detail.messages)
    }

    @Test
    fun createDirectConversationRejectsSelfTarget() = runBlocking {
        val userId = UUID.randomUUID()
        val service = MessagingService(InMemoryMessagingRepository(setOf(userId)))

        val error = assertFailsWith<MessagingException> {
            service.createDirectConversation(
                userId,
                CreateDirectConversationRequestDto(targetUserId = userId.toString())
            )
        }

        assertEquals("INVALID_CONVERSATION_TARGET", error.code)
    }

    @Test
    fun createDirectConversationRespectsRecipientMessagingSetting() = runBlocking {
        val viewerId = UUID.randomUUID()
        val targetId = UUID.randomUUID()
        val service = MessagingService(InMemoryMessagingRepository(allowedTargets = emptySet()))

        val error = assertFailsWith<MessagingException> {
            service.createDirectConversation(
                viewerId,
                CreateDirectConversationRequestDto(targetUserId = targetId.toString())
            )
        }

        assertEquals("MESSAGING_NOT_ALLOWED", error.code)
    }

    @Test
    fun createDirectConversationRejectsRiskRestrictedViewer() = runBlocking {
        val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000011")
        val targetId = UUID.fromString("00000000-0000-0000-0000-000000000012")
        val service = MessagingService(
            repository = InMemoryMessagingRepository(setOf(targetId)),
            riskPolicyService = restrictedRiskPolicy()
        )

        val error = assertFailsWith<MessagingException> {
            service.createDirectConversation(
                viewerId,
                CreateDirectConversationRequestDto(targetUserId = targetId.toString(), origin = "grid")
            )
        }

        assertEquals("RISK_RESTRICTED", error.code)
    }

    @Test
    fun sendMessageStoresTrimmedBody() = runBlocking {
        val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val targetId = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val repository = InMemoryMessagingRepository(setOf(targetId))
        val notifications = RecordingNotificationPublisher()
        val service = MessagingService(repository, notificationPublisher = notifications)
        val detail = service.createDirectConversation(
            viewerId,
            CreateDirectConversationRequestDto(targetUserId = targetId.toString())
        )

        val message = service.sendMessage(
            viewerId,
            detail.conversation.conversationId,
            SendMessageRequestDto(body = "  Hey there  ")
        )

        assertEquals("Hey there", message.body)
        val refreshed = service.getConversationDetail(viewerId, detail.conversation.conversationId)
        assertEquals(1, refreshed.messages.size)
        assertEquals("Hey there", refreshed.messages.first().body)
        assertEquals(NotificationKind.MESSAGE, notifications.events.single().kind)
        assertEquals(targetId, notifications.events.single().recipientUserId)
    }

    @Test
    fun listConversationsReturnsThreadsWithLastMessage() = runBlocking {
        val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val targetId = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val repository = InMemoryMessagingRepository(setOf(targetId))
        val service = MessagingService(repository)
        val detail = service.createDirectConversation(
            viewerId,
            CreateDirectConversationRequestDto(targetUserId = targetId.toString())
        )
        service.sendMessage(viewerId, detail.conversation.conversationId, SendMessageRequestDto("Hey there"))

        val response = service.listConversations(viewerId)

        assertEquals(1, response.items.size)
        assertEquals(detail.conversation.conversationId, response.items.first().conversationId)
        assertEquals("Hey there", response.items.first().lastMessage?.body)
    }

    @Test
    fun markConversationReadClearsUnreadCount() = runBlocking {
        val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val targetId = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val repository = InMemoryMessagingRepository(setOf(targetId))
        val service = MessagingService(repository)
        val detail = service.createDirectConversation(
            viewerId,
            CreateDirectConversationRequestDto(targetUserId = targetId.toString())
        )
        repository.sendMessage(
            userId = targetId,
            conversationId = UUID.fromString(detail.conversation.conversationId),
            body = "Incoming",
            moderationStatus = MessageModerationStatus.VISIBLE,
            bodyFingerprint = null,
            safetyReason = null,
            messageIdFactory = UUID::randomUUID
        )

        assertEquals(1, service.listConversations(viewerId).items.first().unreadCount)

        val readConversation = service.markConversationRead(viewerId, detail.conversation.conversationId)

        assertEquals(0, readConversation.unreadCount)
        assertEquals(0, service.listConversations(viewerId).items.first().unreadCount)
    }

    @Test
    fun senderMessageDeliveryStatusMovesFromSentToRead() = runBlocking {
        val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val targetId = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val repository = InMemoryMessagingRepository(setOf(targetId))
        val service = MessagingService(repository)
        val detail = service.createDirectConversation(
            viewerId,
            CreateDirectConversationRequestDto(targetUserId = targetId.toString())
        )
        service.sendMessage(viewerId, detail.conversation.conversationId, SendMessageRequestDto("Hey there"))

        val beforeRead = service.getConversationDetail(viewerId, detail.conversation.conversationId)

        assertEquals("sent", beforeRead.messages.first().deliveryStatus)

        service.markConversationRead(targetId, detail.conversation.conversationId)
        val afterRead = service.getConversationDetail(viewerId, detail.conversation.conversationId)

        assertEquals("read", afterRead.messages.first().deliveryStatus)
    }

    @Test
    fun getConversationDetailPaginatesOlderMessages() = runBlocking {
        val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val targetId = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val repository = InMemoryMessagingRepository(setOf(targetId))
        val service = MessagingService(repository)
        val detail = service.createDirectConversation(
            viewerId,
            CreateDirectConversationRequestDto(targetUserId = targetId.toString())
        )
        service.sendMessage(viewerId, detail.conversation.conversationId, SendMessageRequestDto("First"))
        service.sendMessage(viewerId, detail.conversation.conversationId, SendMessageRequestDto("Second"))
        service.sendMessage(viewerId, detail.conversation.conversationId, SendMessageRequestDto("Third"))

        val firstPage = service.getConversationDetail(
            userId = viewerId,
            conversationIdText = detail.conversation.conversationId,
            limitText = "2"
        )
        val secondPage = service.getConversationDetail(
            userId = viewerId,
            conversationIdText = detail.conversation.conversationId,
            beforeCursorText = firstPage.nextCursor,
            limitText = "2"
        )

        assertEquals(listOf("Second", "Third"), firstPage.messages.map { it.body })
        assertEquals(listOf("First"), secondPage.messages.map { it.body })
        assertEquals(null, secondPage.nextCursor)
    }

    @Test
    fun linkedPartnerInviteRequiresAcceptBeforeSending() = runBlocking {
        val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val targetId = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val partnerId = UUID.fromString("00000000-0000-0000-0000-000000000003")
        val repository = InMemoryMessagingRepository(
            allowedTargets = setOf(targetId),
            linkedPartnerByUser = mapOf(viewerId to partnerId)
        )
        val notifications = RecordingNotificationPublisher()
        val service = MessagingService(repository, notificationPublisher = notifications)
        val detail = service.createDirectConversation(
            viewerId,
            CreateDirectConversationRequestDto(targetUserId = targetId.toString())
        )

        val invited = service.inviteLinkedPartnerToConversation(viewerId, detail.conversation.conversationId)
        val invitedPartner = invited.participants.first { it.userId == partnerId.toString() }

        assertEquals("group", invited.kind)
        assertEquals(ConversationParticipantStatus.INVITED, invitedPartner.status)
        assertEquals(NotificationKind.PARTNER_CHAT_INVITE, notifications.events.single().kind)
        assertEquals(partnerId, notifications.events.single().recipientUserId)
        val sendBeforeAcceptError = assertFailsWith<MessagingException> {
            service.sendMessage(partnerId, detail.conversation.conversationId, SendMessageRequestDto("Joining"))
        }
        assertEquals("CONVERSATION_NOT_FOUND", sendBeforeAcceptError.code)

        val accepted = service.acceptPartnerInvite(partnerId, detail.conversation.conversationId)
        val acceptedPartner = accepted.conversation.participants.first { it.userId == partnerId.toString() }
        val sent = service.sendMessage(partnerId, detail.conversation.conversationId, SendMessageRequestDto("Joining"))

        assertEquals(ConversationParticipantStatus.ACTIVE, acceptedPartner.status)
        assertEquals("Joining", sent.body)
    }

    @Test
    fun decliningPartnerInviteRemovesInviteeThread() = runBlocking {
        val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val targetId = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val partnerId = UUID.fromString("00000000-0000-0000-0000-000000000003")
        val repository = InMemoryMessagingRepository(
            allowedTargets = setOf(targetId),
            linkedPartnerByUser = mapOf(viewerId to partnerId)
        )
        val service = MessagingService(repository)
        val detail = service.createDirectConversation(
            viewerId,
            CreateDirectConversationRequestDto(targetUserId = targetId.toString())
        )
        service.inviteLinkedPartnerToConversation(viewerId, detail.conversation.conversationId)

        service.declinePartnerInvite(partnerId, detail.conversation.conversationId)

        assertEquals(emptyList(), service.listConversations(partnerId).items)
    }

    @Test
    fun sendMessageRejectsEmptyBody() = runBlocking {
        val service = MessagingService(InMemoryMessagingRepository(emptySet()))

        val error = assertFailsWith<MessagingException> {
            service.sendMessage(
                UUID.randomUUID(),
                UUID.randomUUID().toString(),
                SendMessageRequestDto(body = " ")
            )
        }

        assertEquals("MESSAGE_BODY_EMPTY", error.code)
    }

    @Test
    fun sendMessageRejectsRateLimitedFirstMessageBeforePersisting() = runBlocking {
        val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000021")
        val targetId = UUID.fromString("00000000-0000-0000-0000-000000000022")
        val repository = InMemoryMessagingRepository(setOf(targetId))
        val service = MessagingService(
            repository = repository,
            rateLimitService = denyingRateLimitService()
        )
        val detail = service.createDirectConversation(
            viewerId,
            CreateDirectConversationRequestDto(targetUserId = targetId.toString())
        )

        val error = assertFailsWith<MessagingException> {
            service.sendMessage(viewerId, detail.conversation.conversationId, SendMessageRequestDto("First"))
        }

        assertEquals("RATE_LIMITED", error.code)
        assertEquals(emptyList(), repository.messagesSnapshot(detail.conversation.conversationId))
    }

    @Test
    fun sendMessageRejectsRiskRestrictedSenderBeforePersisting() = runBlocking {
        val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000027")
        val targetId = UUID.fromString("00000000-0000-0000-0000-000000000028")
        val repository = InMemoryMessagingRepository(setOf(targetId))
        val conversation = repository.getOrCreateDirectConversation(viewerId, targetId, "grid")!!
        val service = MessagingService(
            repository = repository,
            riskPolicyService = restrictedRiskPolicy()
        )

        val error = assertFailsWith<MessagingException> {
            service.sendMessage(
                viewerId,
                conversation.conversationId.toString(),
                SendMessageRequestDto("First")
            )
        }

        assertEquals("RISK_RESTRICTED", error.code)
        assertEquals(emptyList(), repository.messagesSnapshot(conversation.conversationId.toString()))
    }

    @Test
    fun sendMessageDoesNotRateLimitFollowUpMessages() = runBlocking {
        val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000023")
        val targetId = UUID.fromString("00000000-0000-0000-0000-000000000024")
        val repository = InMemoryMessagingRepository(setOf(targetId))
        val conversation = repository.getOrCreateDirectConversation(viewerId, targetId, "grid")!!
        repository.sendMessage(
            userId = viewerId,
            conversationId = conversation.conversationId,
            body = "Existing",
            moderationStatus = MessageModerationStatus.VISIBLE,
            bodyFingerprint = null,
            safetyReason = null,
            messageIdFactory = UUID::randomUUID
        )
        val service = MessagingService(
            repository = repository,
            rateLimitService = denyingRateLimitService()
        )

        val message = service.sendMessage(
            viewerId,
            conversation.conversationId.toString(),
            SendMessageRequestDto("Follow up")
        )

        assertEquals("Follow up", message.body)
        assertEquals(2, repository.messagesSnapshot(conversation.conversationId.toString()).size)
    }

    @Test
    fun firstMessageWithExternalLinkIsHeldForReviewAndNotNotified() = runBlocking {
        val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000025")
        val targetId = UUID.fromString("00000000-0000-0000-0000-000000000026")
        val repository = InMemoryMessagingRepository(setOf(targetId))
        val notifications = RecordingNotificationPublisher()
        val riskSignals = RecordingMessageReviewRiskSignalPublisher()
        val service = MessagingService(
            repository = repository,
            notificationPublisher = notifications,
            messageReviewRiskSignalPublisher = riskSignals
        )
        val detail = service.createDirectConversation(
            viewerId,
            CreateDirectConversationRequestDto(targetUserId = targetId.toString())
        )

        val message = service.sendMessage(
            viewerId,
            detail.conversation.conversationId,
            SendMessageRequestDto("Find me at https://example.com")
        )

        assertEquals("held", message.status)
        assertEquals("held_for_review", message.deliveryStatus)
        assertEquals(emptyList(), notifications.events)
        assertEquals(MessageSafetyReason.LINK_FIRST_MESSAGE, repository.reviewQueue.single().reason)
        assertEquals(message.messageId, repository.reviewQueue.single().messageId.toString())
        assertEquals(MessageSafetyReason.LINK_FIRST_MESSAGE, riskSignals.events.single().reason)

        val senderView = service.getConversationDetail(viewerId, detail.conversation.conversationId)
        val recipientView = service.getConversationDetail(targetId, detail.conversation.conversationId)
        assertEquals(1, senderView.messages.size)
        assertEquals("held", senderView.messages.single().status)
        assertEquals(emptyList(), recipientView.messages)
    }

    @Test
    fun repeatedFirstMessageCopyIsHeldForReview() = runBlocking {
        val senderId = UUID.fromString("00000000-0000-0000-0000-000000000031")
        val firstTarget = UUID.fromString("00000000-0000-0000-0000-000000000032")
        val secondTarget = UUID.fromString("00000000-0000-0000-0000-000000000033")
        val thirdTarget = UUID.fromString("00000000-0000-0000-0000-000000000034")
        val repository = InMemoryMessagingRepository(setOf(firstTarget, secondTarget, thirdTarget))
        val service = MessagingService(repository, clock = fixedMessageClock())
        val first = service.createDirectConversation(
            senderId,
            CreateDirectConversationRequestDto(targetUserId = firstTarget.toString())
        )
        val second = service.createDirectConversation(
            senderId,
            CreateDirectConversationRequestDto(targetUserId = secondTarget.toString())
        )
        val third = service.createDirectConversation(
            senderId,
            CreateDirectConversationRequestDto(targetUserId = thirdTarget.toString())
        )

        service.sendMessage(senderId, first.conversation.conversationId, SendMessageRequestDto("Hey, loved your profile"))
        service.sendMessage(senderId, second.conversation.conversationId, SendMessageRequestDto("  hey, loved your profile  "))
        val held = service.sendMessage(
            senderId,
            third.conversation.conversationId,
            SendMessageRequestDto("Hey, loved your profile")
        )

        assertEquals("held", held.status)
        assertEquals(MessageSafetyReason.DUPLICATE_FIRST_MESSAGE, repository.reviewQueue.single().reason)
    }
}

private fun denyingRateLimitService(): RateLimitService =
    RateLimitService(
        object : RateLimitRepository {
            override suspend fun increment(key: String, windowSeconds: Long): RateLimitCounter =
                RateLimitCounter(count = Long.MAX_VALUE, ttlSeconds = windowSeconds)
        }
    )

private fun restrictedRiskPolicy(tier: String = "quarantine"): RiskPolicyService =
    RiskPolicyService { userId ->
        AccountRiskStateRecord(
            userId = userId,
            score = 70,
            tier = tier,
            lastEventAt = Instant.parse("2026-04-24T18:00:00Z"),
            lastReviewedAt = null,
            updatedAt = Instant.parse("2026-04-24T18:00:00Z")
        )
    }

private fun fixedMessageClock(): Clock =
    Clock.fixed(Instant.parse("2026-04-24T18:00:10Z"), ZoneOffset.UTC)

private class InMemoryMessagingRepository(
    private val allowedTargets: Set<UUID>,
    private val linkedPartnerByUser: Map<UUID, UUID> = emptyMap()
) : MessagingRepository {
    private val conversationsByPair = mutableMapOf<Set<UUID>, ConversationRecord>()
    private val messagesByConversation = mutableMapOf<UUID, MutableList<MessageRecord>>()
    private val lastReadAtByParticipant = mutableMapOf<Pair<UUID, UUID>, Instant>()
    private val messageFingerprints = mutableMapOf<UUID, String?>()
    private var nextConversationIndex = 1
    private var nextMessageIndex = 1
    val reviewQueue = mutableListOf<ReviewQueueItem>()

    override suspend fun listConversations(userId: UUID, limit: Int): List<ConversationRecord> =
        conversationsByPair.values
            .filter { conversation ->
                conversation.participants.any {
                    it.userId == userId && it.status in visibleParticipantStatuses
                }
            }
            .map { conversation ->
                val messages = visibleMessagesFor(
                    userId = userId,
                    conversationId = conversation.conversationId
                )
                conversation.copy(
                    lastMessage = messages.lastOrNull()?.withDeliveryStatus(userId),
                    unreadCount = unreadCount(userId, conversation.conversationId),
                    updatedAt = messages.lastOrNull()?.createdAt ?: conversation.updatedAt
                )
            }
            .sortedByDescending { it.updatedAt }
            .take(limit)

    override suspend fun getOrCreateDirectConversation(
        viewerUserId: UUID,
        targetUserId: UUID,
        origin: String,
        conversationIdFactory: () -> UUID
    ): ConversationRecord? {
        if (targetUserId !in allowedTargets) return null

        val pair = setOf(viewerUserId, targetUserId)
        return conversationsByPair.getOrPut(pair) {
            val conversationId =
                UUID.fromString("10000000-0000-0000-0000-${nextConversationIndex++.toString().padStart(12, '0')}")
            lastReadAtByParticipant[conversationId to viewerUserId] = Instant.parse("2026-04-24T17:59:00Z")
            lastReadAtByParticipant[conversationId to targetUserId] = Instant.parse("2026-04-24T17:59:00Z")
            ConversationRecord(
                conversationId = conversationId,
                kind = "direct",
                origin = origin,
                participants = listOf(
                    ConversationParticipantRecord(viewerUserId, "Viewer"),
                    ConversationParticipantRecord(targetUserId, "Target")
                ),
                lastMessage = null,
                updatedAt = Instant.parse("2026-04-24T18:00:00Z")
            )
        }
    }

    override suspend fun getActiveLinkedPartnerUserId(userId: UUID): UUID? =
        linkedPartnerByUser[userId]

    override suspend fun inviteLinkedPartner(
        inviterUserId: UUID,
        conversationId: UUID,
        partnerUserId: UUID
    ): PartnerInviteResult {
        val entry = conversationsByPair.entries.firstOrNull { (_, conversation) ->
            conversation.conversationId == conversationId &&
                conversation.participants.any {
                    it.userId == inviterUserId && it.status == ConversationParticipantStatus.ACTIVE
                }
        } ?: return PartnerInviteResult.ConversationNotFound

        val currentPartner = entry.value.participants.firstOrNull { it.userId == partnerUserId }
        if (currentPartner?.status == ConversationParticipantStatus.ACTIVE) {
            return PartnerInviteResult.AlreadyInConversation
        }
        if (currentPartner?.status == ConversationParticipantStatus.INVITED) {
            return PartnerInviteResult.AlreadyInvited
        }

        lastReadAtByParticipant[conversationId to partnerUserId] = Instant.parse("2026-04-24T18:00:00Z")
        val updatedParticipants = entry.value.participants
            .filterNot { it.userId == partnerUserId }
            .plus(
                ConversationParticipantRecord(
                    userId = partnerUserId,
                    displayName = "Partner",
                    status = ConversationParticipantStatus.INVITED,
                    invitedByUserId = inviterUserId
                )
            )
        val updated = entry.value.copy(kind = "group", participants = updatedParticipants)
        conversationsByPair[entry.key] = updated
        return PartnerInviteResult.Invited(updated)
    }

    override suspend fun acceptPartnerInvite(userId: UUID, conversationId: UUID): ConversationDetailRecord? {
        val entry = conversationsByPair.entries.firstOrNull { (_, conversation) ->
            conversation.conversationId == conversationId &&
                conversation.participants.any {
                    it.userId == userId && it.status == ConversationParticipantStatus.INVITED
                }
        } ?: return null

        val updated = entry.value.copy(
            kind = "group",
            participants = entry.value.participants.map { participant ->
                if (participant.userId == userId) participant.copy(status = ConversationParticipantStatus.ACTIVE) else participant
            }
        )
        conversationsByPair[entry.key] = updated
        return getConversationDetail(userId, conversationId, limit = 50)
    }

    override suspend fun declinePartnerInvite(userId: UUID, conversationId: UUID): Boolean =
        updateParticipantStatus(
            userId = userId,
            conversationId = conversationId,
            from = ConversationParticipantStatus.INVITED,
            to = ConversationParticipantStatus.DECLINED
        )

    override suspend fun leaveConversation(userId: UUID, conversationId: UUID): Boolean {
        val entry = conversationsByPair.entries.firstOrNull { (_, conversation) ->
            conversation.conversationId == conversationId &&
                conversation.participants.any {
                    it.userId == userId && it.status in visibleParticipantStatuses
                }
        } ?: return false
        val updated = entry.value.copy(
            participants = entry.value.participants.map { participant ->
                if (participant.userId == userId) participant.copy(status = ConversationParticipantStatus.LEFT) else participant
            }
        )
        conversationsByPair[entry.key] = updated
        return true
    }

    override suspend fun getConversationDetail(
        userId: UUID,
        conversationId: UUID,
        limit: Int,
        before: MessagePageCursor?
    ): ConversationDetailRecord? {
        val conversation = conversationsByPair.values.firstOrNull {
            it.conversationId == conversationId &&
                it.participants.any { participant ->
                    participant.userId == userId && participant.status in visibleParticipantStatuses
                }
        } ?: return null

        val messages = messagesByConversation[conversationId].orEmpty().sortedWith(messageSort)
        val beforeIndex = before?.let { cursor ->
            messages.indexOfFirst { message ->
                message.createdAt == cursor.createdAt && message.messageId == cursor.messageId
            }.takeIf { it >= 0 }
        }
        val eligible = if (beforeIndex == null) messages else messages.take(beforeIndex)
        val pageMessages = eligible.takeLast(limit)
        val nextCursor = if (eligible.size > limit) pageMessages.firstOrNull()?.toCursor() else null
        val visibleMessages = visibleMessagesFor(userId, pageMessages)
        return ConversationDetailRecord(
            conversation = conversation.copy(
                lastMessage = visibleMessagesFor(userId, conversationId).lastOrNull()?.withDeliveryStatus(userId),
                unreadCount = unreadCount(userId, conversationId)
            ),
            messages = visibleMessages.map { it.withDeliveryStatus(userId) },
            nextCursor = nextCursor
        )
    }

    override suspend fun markConversationRead(userId: UUID, conversationId: UUID): ConversationRecord? {
        val conversation = conversationsByPair.values.firstOrNull {
            it.conversationId == conversationId &&
                it.participants.any { participant ->
                    participant.userId == userId && participant.status in visibleParticipantStatuses
                }
        } ?: return null

        val lastMessageAt = messagesByConversation[conversationId].orEmpty().lastOrNull()?.createdAt
            ?: Instant.parse("2026-04-24T18:00:00Z")
        lastReadAtByParticipant[conversationId to userId] = lastMessageAt
        return conversation.copy(
            lastMessage = visibleMessagesFor(userId, conversationId).lastOrNull()?.withDeliveryStatus(userId),
            unreadCount = 0,
            updatedAt = lastMessageAt
        )
    }

    override suspend fun sendMessage(
        userId: UUID,
        conversationId: UUID,
        body: String,
        moderationStatus: String,
        bodyFingerprint: String?,
        safetyReason: String?,
        messageIdFactory: () -> UUID
    ): MessageRecord? {
        val conversation = conversationsByPair.values.firstOrNull {
            it.conversationId == conversationId &&
                it.participants.any { participant ->
                    participant.userId == userId && participant.status == ConversationParticipantStatus.ACTIVE
                }
        } ?: return null

        val messageIndex = nextMessageIndex++
        val message = MessageRecord(
            messageId = UUID.fromString("20000000-0000-0000-0000-${messageIndex.toString().padStart(12, '0')}"),
            conversationId = conversation.conversationId,
            senderUserId = userId,
            body = body,
            createdAt = Instant.parse("2026-04-24T18:00:00Z").plusSeconds(messageIndex.toLong()),
            status = moderationStatus,
            deliveryStatus = if (moderationStatus == MessageModerationStatus.HELD) {
                "held_for_review"
            } else {
                "sent"
            }
        )
        messagesByConversation.getOrPut(conversationId) { mutableListOf() }.add(message)
        messageFingerprints[message.messageId] = bodyFingerprint
        if (moderationStatus == MessageModerationStatus.HELD && safetyReason != null) {
            reviewQueue += ReviewQueueItem(
                messageId = message.messageId,
                senderUserId = userId,
                conversationId = conversationId,
                reason = safetyReason
            )
        }
        lastReadAtByParticipant[conversationId to userId] = message.createdAt
        return message
    }

    override suspend fun hasSentMessageInConversation(userId: UUID, conversationId: UUID): Boolean =
        messagesByConversation[conversationId].orEmpty().any { message -> message.senderUserId == userId }

    override suspend fun countRecentMessagesByFingerprint(
        userId: UUID,
        bodyFingerprint: String,
        since: Instant
    ): Int =
        messagesByConversation.values
            .flatten()
            .count { message ->
                message.senderUserId == userId &&
                    message.createdAt >= since &&
                    messageFingerprints[message.messageId] == bodyFingerprint
            }

    fun messagesSnapshot(conversationId: String): List<MessageRecord> =
        messagesByConversation[UUID.fromString(conversationId)].orEmpty().toList()

    private fun unreadCount(userId: UUID, conversationId: UUID): Int {
        val readAt = lastReadAtByParticipant[conversationId to userId] ?: Instant.EPOCH
        return messagesByConversation[conversationId].orEmpty()
            .count { message ->
                message.senderUserId != userId &&
                    message.status == MessageModerationStatus.VISIBLE &&
                    message.createdAt > readAt
            }
    }

    private fun MessageRecord.withDeliveryStatus(viewerUserId: UUID): MessageRecord {
        if (status == MessageModerationStatus.HELD) return copy(deliveryStatus = "held_for_review")
        if (senderUserId != viewerUserId) return copy(deliveryStatus = "received")
        val conversation = conversationsByPair.values.firstOrNull { it.conversationId == conversationId }
            ?: return copy(deliveryStatus = "sent")
        val hasUnreadRecipient = conversation.participants
            .filter { participant ->
                participant.userId != senderUserId &&
                    participant.status == ConversationParticipantStatus.ACTIVE
            }
            .any { participant ->
                val readAt = lastReadAtByParticipant[conversationId to participant.userId] ?: Instant.EPOCH
                readAt < createdAt
            }
        return copy(deliveryStatus = if (hasUnreadRecipient) "sent" else "read")
    }

    private fun visibleMessagesFor(userId: UUID, conversationId: UUID): List<MessageRecord> =
        visibleMessagesFor(
            userId = userId,
            messages = messagesByConversation[conversationId].orEmpty().sortedWith(messageSort)
        )

    private fun visibleMessagesFor(userId: UUID, messages: List<MessageRecord>): List<MessageRecord> =
        messages.filter { message ->
            message.status == MessageModerationStatus.VISIBLE ||
                (message.status == MessageModerationStatus.HELD && message.senderUserId == userId)
        }

    private fun updateParticipantStatus(
        userId: UUID,
        conversationId: UUID,
        from: String,
        to: String
    ): Boolean {
        val entry = conversationsByPair.entries.firstOrNull { (_, conversation) ->
            conversation.conversationId == conversationId &&
                conversation.participants.any { it.userId == userId && it.status == from }
        } ?: return false
        conversationsByPair[entry.key] = entry.value.copy(
            participants = entry.value.participants.map { participant ->
                if (participant.userId == userId) participant.copy(status = to) else participant
            }
        )
        return true
    }

    private fun MessageRecord.toCursor(): MessagePageCursor = MessagePageCursor(
        createdAt = createdAt,
        messageId = messageId
    )

    private val visibleParticipantStatuses = setOf(
        ConversationParticipantStatus.ACTIVE,
        ConversationParticipantStatus.INVITED
    )

    private val messageSort = compareBy<MessageRecord> { it.createdAt }.thenBy { it.messageId }
}

private data class ReviewQueueItem(
    val messageId: UUID,
    val senderUserId: UUID,
    val conversationId: UUID,
    val reason: String
)

private class RecordingMessageReviewRiskSignalPublisher : MessageReviewRiskSignalPublisher {
    val events = mutableListOf<HeldMessageRiskSignal>()

    override suspend fun recordMessageHeld(userId: UUID, messageId: UUID, reason: String) {
        events += HeldMessageRiskSignal(userId, messageId, reason)
    }
}

private data class HeldMessageRiskSignal(
    val userId: UUID,
    val messageId: UUID,
    val reason: String
)

private class RecordingNotificationPublisher : NotificationPublisher {
    val events = mutableListOf<NotificationEventDraft>()

    override suspend fun enqueue(notification: NotificationEventDraft): NotificationOutboxRecord? {
        events += notification
        return null
    }
}
