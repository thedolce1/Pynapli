package com.pynapli.server.messaging

import com.pynapli.server.db.inTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.sql.DataSource

internal suspend fun DataSource.inviteLinkedMessagingPartner(
    inviterUserId: UUID,
    conversationId: UUID,
    partnerUserId: UUID,
): PartnerInviteResult =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.inTransaction {
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
            }
        }
    }

internal suspend fun DataSource.acceptMessagingPartnerInvite(
    userId: UUID,
    conversationId: UUID,
): ConversationDetailRecord? =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.inTransaction {
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
                        val page =
                            connection.loadMessages(
                                userId,
                                conversationId,
                                JdbcMessagingRepositoryDefaults.MessagePageLimit,
                                before = null,
                                conversation.effectiveExpirySeconds,
                            )
                        ConversationDetailRecord(
                            conversation = conversation,
                            messages = page.messages,
                            nextCursor = page.nextCursor,
                        )
                    }
                connection.commit()
                detail
            }
        }
    }

internal suspend fun DataSource.declineMessagingPartnerInvite(
    userId: UUID,
    conversationId: UUID,
): Boolean =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.inTransaction {
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
            }
        }
    }

internal suspend fun DataSource.leaveMessagingConversation(
    userId: UUID,
    conversationId: UUID,
): Boolean =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            val updated = connection.leaveConversationParticipant(userId, conversationId)
            if (updated > 0) connection.touchConversation(conversationId)
            updated > 0
        }
    }
