package com.pynapli.server.messaging

import com.pynapli.shared.network.dto.CreateDirectConversationRequestDto
import com.pynapli.shared.network.dto.SendMessageRequestDto
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.Instant
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
    fun sendMessageStoresTrimmedBody() = runBlocking {
        val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val targetId = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val repository = InMemoryMessagingRepository(setOf(targetId))
        val service = MessagingService(repository)
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
}

private class InMemoryMessagingRepository(
    private val allowedTargets: Set<UUID>
) : MessagingRepository {
    private val conversationsByPair = mutableMapOf<Set<UUID>, ConversationRecord>()
    private val messagesByConversation = mutableMapOf<UUID, MutableList<MessageRecord>>()
    private val lastReadAtByParticipant = mutableMapOf<Pair<UUID, UUID>, Instant>()
    private var nextConversationIndex = 1
    private var nextMessageIndex = 1

    override suspend fun listConversations(userId: UUID, limit: Int): List<ConversationRecord> =
        conversationsByPair.values
            .filter { conversation -> conversation.participants.any { it.userId == userId } }
            .map { conversation ->
                val messages = messagesByConversation[conversation.conversationId].orEmpty()
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

    override suspend fun getConversationDetail(
        userId: UUID,
        conversationId: UUID,
        limit: Int,
        before: MessagePageCursor?
    ): ConversationDetailRecord? {
        val conversation = conversationsByPair.values.firstOrNull {
            it.conversationId == conversationId && it.participants.any { participant -> participant.userId == userId }
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
        return ConversationDetailRecord(
            conversation = conversation.copy(
                lastMessage = messages.lastOrNull()?.withDeliveryStatus(userId),
                unreadCount = unreadCount(userId, conversationId)
            ),
            messages = pageMessages.map { it.withDeliveryStatus(userId) },
            nextCursor = nextCursor
        )
    }

    override suspend fun markConversationRead(userId: UUID, conversationId: UUID): ConversationRecord? {
        val conversation = conversationsByPair.values.firstOrNull {
            it.conversationId == conversationId && it.participants.any { participant -> participant.userId == userId }
        } ?: return null

        val lastMessageAt = messagesByConversation[conversationId].orEmpty().lastOrNull()?.createdAt
            ?: Instant.parse("2026-04-24T18:00:00Z")
        lastReadAtByParticipant[conversationId to userId] = lastMessageAt
        return conversation.copy(
            lastMessage = messagesByConversation[conversationId].orEmpty().lastOrNull()?.withDeliveryStatus(userId),
            unreadCount = 0,
            updatedAt = lastMessageAt
        )
    }

    override suspend fun sendMessage(
        userId: UUID,
        conversationId: UUID,
        body: String,
        messageIdFactory: () -> UUID
    ): MessageRecord? {
        val conversation = conversationsByPair.values.firstOrNull {
            it.conversationId == conversationId && it.participants.any { participant -> participant.userId == userId }
        } ?: return null

        val messageIndex = nextMessageIndex++
        val message = MessageRecord(
            messageId = UUID.fromString("20000000-0000-0000-0000-${messageIndex.toString().padStart(12, '0')}"),
            conversationId = conversation.conversationId,
            senderUserId = userId,
            body = body,
            createdAt = Instant.parse("2026-04-24T18:00:00Z").plusSeconds(messageIndex.toLong()),
            status = "visible",
            deliveryStatus = "sent"
        )
        messagesByConversation.getOrPut(conversationId) { mutableListOf() }.add(message)
        lastReadAtByParticipant[conversationId to userId] = message.createdAt
        return message
    }

    private fun unreadCount(userId: UUID, conversationId: UUID): Int {
        val readAt = lastReadAtByParticipant[conversationId to userId] ?: Instant.EPOCH
        return messagesByConversation[conversationId].orEmpty()
            .count { message -> message.senderUserId != userId && message.createdAt > readAt }
    }

    private fun MessageRecord.withDeliveryStatus(viewerUserId: UUID): MessageRecord {
        if (senderUserId != viewerUserId) return copy(deliveryStatus = "received")
        val conversation = conversationsByPair.values.firstOrNull { it.conversationId == conversationId }
            ?: return copy(deliveryStatus = "sent")
        val hasUnreadRecipient = conversation.participants
            .filterNot { participant -> participant.userId == senderUserId }
            .any { participant ->
                val readAt = lastReadAtByParticipant[conversationId to participant.userId] ?: Instant.EPOCH
                readAt < createdAt
            }
        return copy(deliveryStatus = if (hasUnreadRecipient) "sent" else "read")
    }

    private fun MessageRecord.toCursor(): MessagePageCursor = MessagePageCursor(
        createdAt = createdAt,
        messageId = messageId
    )

    private val messageSort = compareBy<MessageRecord> { it.createdAt }.thenBy { it.messageId }
}
