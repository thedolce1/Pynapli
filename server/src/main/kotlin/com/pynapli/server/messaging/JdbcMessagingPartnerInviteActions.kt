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
                connection.markConversationGroup(conversationId, causedByUserId = partnerUserId)
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
    inviteId: UUID?,
): ConversationDetailRecord? =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.inTransaction {
                when (val action = connection.resolvePartnerInviteAction(userId, conversationId, inviteId)) {
                    null -> {
                        connection.rollback()
                        return@withContext null
                    }
                    // A lost response's retry: already done, answer with the thread as it is.
                    PartnerInviteAction.OwnInviteAlreadyResolved -> Unit
                    // Throws MESSAGING_NOT_ALLOWED (rolled back) when activating would put blocked people together.
                    is PartnerInviteAction.Respond ->
                        connection.acceptOpenPartnerInvite(conversationId, userId, action.invite, action.role)
                }
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
    inviteId: UUID?,
): Boolean =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.inTransaction {
                val action = connection.resolvePartnerInviteAction(userId, conversationId, inviteId)
                if (action !is PartnerInviteAction.Respond) {
                    connection.rollback()
                    return@withContext false
                }

                connection.declineOpenPartnerInvite(conversationId, action.invite, action.role)
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
            connection.inTransaction {
                // Lock the open invites first, in the same order accept/decline do.
                connection.loadOpenPartnerInvites(conversationId, forUpdate = true)
                val wasActive = connection.participantStatus(conversationId, userId) == ParticipantStatus.Active
                val updated = connection.leaveConversationParticipant(userId, conversationId)
                if (updated > 0) {
                    connection.touchConversation(conversationId, causedByUserId = userId)
                    // The people a waiting invitee agreed to join changed: they must say yes again.
                    if (wasActive) connection.resetWaitingPartnerAcceptances(conversationId)
                }
                updated > 0
            }
        }
    }
