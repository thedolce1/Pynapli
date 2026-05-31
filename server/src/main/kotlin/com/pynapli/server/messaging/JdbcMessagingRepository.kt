package com.pynapli.server.messaging

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcMessagingRepository(
    private val dataSource: DataSource,
) : MessagingRepository {
    override suspend fun listConversations(
        userId: UUID,
        limit: Int,
    ): List<ConversationRecord> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.listConversationIds(userId, limit).mapNotNull { conversationId ->
                    connection.loadConversation(userId, conversationId)
                }
            }
        }

    override suspend fun getOrCreateDirectConversation(
        viewerUserId: UUID,
        targetUserId: UUID,
        origin: String,
        conversationIdFactory: () -> UUID,
    ): ConversationRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                try {
                    if (!connection.targetAllowsConversation(viewerUserId, targetUserId, origin)) {
                        connection.rollback()
                        return@withContext null
                    }

                    val existingId = connection.findDirectConversationId(viewerUserId, targetUserId)
                    val conversationId = existingId ?: conversationIdFactory()
                    if (existingId == null) {
                        connection.insertDirectConversation(
                            conversationId = conversationId,
                            viewerUserId = viewerUserId,
                            targetUserId = targetUserId,
                            origin = origin,
                        )
                    }

                    connection.commit()
                    connection.loadConversation(viewerUserId, conversationId)
                } catch (error: Throwable) {
                    connection.rollback()
                    throw error
                } finally {
                    connection.autoCommit = true
                }
            }
        }

    override suspend fun getConversationDetail(
        userId: UUID,
        conversationId: UUID,
        limit: Int,
        before: MessagePageCursor?,
    ): ConversationDetailRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                val conversation = connection.loadConversation(userId, conversationId) ?: return@withContext null
                val page = connection.loadMessages(userId, conversationId, limit, before)
                ConversationDetailRecord(
                    conversation = conversation,
                    messages = page.messages,
                    nextCursor = page.nextCursor,
                )
            }
        }

    override suspend fun markConversationRead(
        userId: UUID,
        conversationId: UUID,
    ): ConversationRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                val updatedRows = connection.markRead(userId, conversationId)
                if (updatedRows == 0) return@withContext null
                connection.loadConversation(userId, conversationId)
            }
        }

    override suspend fun sendMessage(
        userId: UUID,
        conversationId: UUID,
        body: String,
        moderationStatus: String,
        bodyFingerprint: String?,
        safetyReason: String?,
        messageIdFactory: () -> UUID,
    ): MessageRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                try {
                    if (!connection.isActiveParticipant(userId, conversationId)) {
                        connection.rollback()
                        return@withContext null
                    }

                    val message =
                        connection.insertMessage(
                            messageId = messageIdFactory(),
                            userId = userId,
                            conversationId = conversationId,
                            body = body,
                            moderationStatus = moderationStatus,
                            bodyFingerprint = bodyFingerprint,
                            safetyReason = safetyReason,
                        )

                    if (moderationStatus == MessageModerationStatus.HELD && safetyReason != null) {
                        connection.insertMessageReviewQueue(
                            messageId = message.messageId,
                            senderUserId = userId,
                            conversationId = conversationId,
                            reason = safetyReason,
                        )
                    }

                    connection.touchConversationAt(conversationId, message.createdAt)
                    connection.markSenderReadAt(userId, conversationId, message.createdAt)

                    connection.commit()
                    message
                } catch (error: Throwable) {
                    connection.rollback()
                    throw error
                } finally {
                    connection.autoCommit = true
                }
            }
        }

    override suspend fun deleteOwnMessage(
        userId: UUID,
        messageId: UUID,
    ): MessageRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                try {
                    val message = connection.deleteOwnVisibleMessage(userId, messageId)

                    if (message == null) {
                        connection.rollback()
                        return@withContext null
                    }

                    connection.touchConversation(message.conversationId)
                    connection.commit()
                    message
                } catch (error: Throwable) {
                    connection.rollback()
                    throw error
                } finally {
                    connection.autoCommit = true
                }
            }
        }

    override suspend fun countRecentMessagesByFingerprint(
        userId: UUID,
        bodyFingerprint: String,
        since: Instant,
    ): Int =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.countRecentMessagesByFingerprint(userId, bodyFingerprint, since)
            }
        }

    override suspend fun hasSentMessageInConversation(
        userId: UUID,
        conversationId: UUID,
    ): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.hasSentMessageInConversation(userId, conversationId)
            }
        }

    override suspend fun getActiveLinkedPartnerUserId(userId: UUID): UUID? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.loadActiveLinkedPartnerUserId(userId)
            }
        }

    override suspend fun inviteLinkedPartner(
        inviterUserId: UUID,
        conversationId: UUID,
        partnerUserId: UUID,
    ): PartnerInviteResult =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                val previousAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    if (!connection.isActiveParticipant(inviterUserId, conversationId)) {
                        connection.rollback()
                        return@withContext PartnerInviteResult.ConversationNotFound
                    }

                    when (connection.participantStatus(conversationId, partnerUserId)) {
                        ParticipantStatus.Active -> {
                            connection.rollback()
                            return@withContext PartnerInviteResult.AlreadyInConversation
                        }
                        ParticipantStatus.Invited -> {
                            connection.rollback()
                            return@withContext PartnerInviteResult.AlreadyInvited
                        }
                        ParticipantStatus.None,
                        ParticipantStatus.Inactive,
                        -> Unit
                    }

                    if (connection.partnerInviteCooldownActive(conversationId, partnerUserId)) {
                        connection.rollback()
                        return@withContext PartnerInviteResult.CooldownActive
                    }

                    val inviteInserted =
                        connection.insertPartnerChatInvite(
                            conversationId = conversationId,
                            inviterUserId = inviterUserId,
                            partnerUserId = partnerUserId,
                        )
                    if (!inviteInserted) {
                        connection.rollback()
                        return@withContext PartnerInviteResult.AlreadyInvited
                    }

                    connection.upsertInvitedParticipant(
                        conversationId = conversationId,
                        partnerUserId = partnerUserId,
                        inviterUserId = inviterUserId,
                    )
                    connection.markConversationGroup(conversationId)
                    val conversation =
                        connection.loadConversation(inviterUserId, conversationId)
                            ?: run {
                                connection.rollback()
                                return@withContext PartnerInviteResult.ConversationNotFound
                            }
                    connection.commit()
                    PartnerInviteResult.Invited(conversation)
                } catch (error: Throwable) {
                    connection.rollback()
                    throw error
                } finally {
                    connection.autoCommit = previousAutoCommit
                }
            }
        }

    override suspend fun acceptPartnerInvite(
        userId: UUID,
        conversationId: UUID,
    ): ConversationDetailRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                val previousAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    val invite =
                        connection.loadOpenPartnerInvite(conversationId, forUpdate = true)
                            ?: run {
                                connection.rollback()
                                return@withContext null
                            }
                    val userRole = connection.partnerInviteUserRole(userId, invite)
                    if (userRole == PartnerInviteUserRole.None) {
                        connection.rollback()
                        return@withContext null
                    }

                    connection.acceptOpenPartnerInvite(conversationId, userId, invite, userRole)
                    val detail =
                        connection.loadConversation(userId, conversationId)?.let { conversation ->
                            val page = connection.loadMessages(userId, conversationId, DEFAULT_MESSAGE_PAGE_LIMIT, before = null)
                            ConversationDetailRecord(
                                conversation = conversation,
                                messages = page.messages,
                                nextCursor = page.nextCursor,
                            )
                        }
                    connection.commit()
                    detail
                } catch (error: Throwable) {
                    connection.rollback()
                    throw error
                } finally {
                    connection.autoCommit = previousAutoCommit
                }
            }
        }

    override suspend fun declinePartnerInvite(
        userId: UUID,
        conversationId: UUID,
    ): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                val previousAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    val invite =
                        connection.loadOpenPartnerInvite(conversationId, forUpdate = true)
                            ?: run {
                                connection.rollback()
                                return@withContext false
                            }
                    val userRole = connection.partnerInviteUserRole(userId, invite)
                    if (userRole == PartnerInviteUserRole.None) {
                        connection.rollback()
                        return@withContext false
                    }

                    connection.declineOpenPartnerInvite(conversationId, invite, userRole)
                    connection.commit()
                    true
                } catch (error: Throwable) {
                    connection.rollback()
                    throw error
                } finally {
                    connection.autoCommit = previousAutoCommit
                }
            }
        }

    override suspend fun leaveConversation(
        userId: UUID,
        conversationId: UUID,
    ): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                val updated = connection.leaveConversationParticipant(userId, conversationId)
                if (updated > 0) connection.touchConversation(conversationId)
                updated > 0
            }
        }

    companion object {
        private const val DEFAULT_MESSAGE_PAGE_LIMIT = 50
    }
}
