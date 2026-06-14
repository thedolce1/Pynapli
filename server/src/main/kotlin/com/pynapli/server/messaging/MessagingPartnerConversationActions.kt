package com.pynapli.server.messaging

import com.pynapli.server.activity.ActivityService
import com.pynapli.server.safety.RiskPolicyService
import com.pynapli.shared.network.dto.ConversationDetailDto
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.ConversationParticipantStatus
import com.pynapli.shared.network.dto.ConversationPartnerInviteResponseDto
import com.pynapli.shared.network.dto.MessageDeltaType
import java.util.UUID

internal class MessagingPartnerConversationActions(
    private val repository: MessagingRepository,
    private val dtoMapper: MessagingDtoMapper,
    private val conversationGuard: MessagingConversationGuard,
    private val realtimePublisher: MessagingRealtimePublisher,
    private val notificationDispatcher: MessagingNotificationDispatcher,
    private val activityService: ActivityService?,
    private val riskPolicyService: RiskPolicyService?,
) {
    suspend fun inviteLinkedPartnerToConversation(
        userId: UUID,
        conversationId: UUID,
    ): ConversationDto {
        if (riskPolicyService?.isSocialActionRestricted(userId) == true) {
            throw MessagingException("RISK_RESTRICTED")
        }
        val partnerUserId =
            repository.getActiveLinkedPartnerUserId(userId)
                ?: throw MessagingException("LINKED_PARTNER_NOT_FOUND")

        val detail =
            repository.getConversationDetail(userId, conversationId, limit = 1)
                ?: throw MessagingException("CONVERSATION_NOT_FOUND")
        conversationGuard.requireVisibleToViewer(detail.conversation, userId)
        conversationGuard.requireMessagingAllowed(detail.conversation, partnerUserId)

        return when (
            val result =
                repository.inviteLinkedPartner(
                    inviterUserId = userId,
                    conversationId = conversationId,
                    partnerUserId = partnerUserId,
                )
        ) {
            is PartnerInviteResult.Invited -> {
                realtimePublisher.publishDelta(
                    type = MessageDeltaType.CONVERSATION_UPDATED,
                    conversationId = conversationId,
                    participants = result.conversation.participants,
                    statuses = MessagingParticipantStatuses.visible,
                )
                activityService?.recordPartnerChatInvite(
                    inviterUserId = userId,
                    inviteeUserId = partnerUserId,
                    conversationId = conversationId,
                )
                notificationDispatcher.enqueuePartnerChatInvite(
                    inviterUserId = userId,
                    inviteeUserId = partnerUserId,
                    conversationId = conversationId,
                )
                dtoMapper.toDto(result.conversation, userId)
            }
            PartnerInviteResult.ConversationNotFound -> throw MessagingException("CONVERSATION_NOT_FOUND")
            PartnerInviteResult.AlreadyInConversation -> throw MessagingException("PARTNER_ALREADY_IN_CONVERSATION")
            PartnerInviteResult.AlreadyInvited -> throw MessagingException("PARTNER_INVITE_ALREADY_SENT")
            PartnerInviteResult.CooldownActive -> throw MessagingException("PARTNER_INVITE_COOLDOWN")
        }
    }

    suspend fun acceptPartnerInvite(
        userId: UUID,
        conversationId: UUID,
    ): ConversationDetailDto {
        val detail =
            repository.getConversationDetail(userId, conversationId, limit = 1)
                ?: throw MessagingException("PARTNER_INVITE_NOT_FOUND")
        val invite = detail.conversation.partnerInvite ?: throw MessagingException("PARTNER_INVITE_NOT_FOUND")
        val viewerParticipant = detail.conversation.participants.firstOrNull { it.userId == userId }
        val viewerIsInvitedPartner = invite.invitedPartnerUserId == userId
        val viewerCanApproveAsThirdParty =
            viewerParticipant?.status == ConversationParticipantStatus.ACTIVE &&
                userId != invite.inviterUserId &&
                userId != invite.invitedPartnerUserId
        if (!viewerIsInvitedPartner && !viewerCanApproveAsThirdParty) {
            throw MessagingException("PARTNER_INVITE_NOT_FOUND")
        }
        conversationGuard.requireMessagingAllowed(detail.conversation, userId)

        val accepted =
            repository.acceptPartnerInvite(userId, conversationId)
                ?: throw MessagingException("PARTNER_INVITE_NOT_FOUND")
        realtimePublisher.publishDelta(
            type = MessageDeltaType.CONVERSATION_UPDATED,
            conversationId = conversationId,
            participants = accepted.conversation.participants,
            statuses = MessagingParticipantStatuses.visible,
        )
        return dtoMapper.toDto(accepted, userId)
    }

    suspend fun declinePartnerInvite(
        userId: UUID,
        conversationId: UUID,
    ): ConversationPartnerInviteResponseDto {
        val detailBeforeDecline =
            repository.getConversationDetail(userId, conversationId, limit = 1)
                ?: throw MessagingException("PARTNER_INVITE_NOT_FOUND")
        if (!repository.declinePartnerInvite(userId, conversationId)) {
            throw MessagingException("PARTNER_INVITE_NOT_FOUND")
        }
        realtimePublisher.publishDelta(
            type = MessageDeltaType.CONVERSATION_UPDATED,
            conversationId = conversationId,
            participants = detailBeforeDecline.conversation.participants,
            statuses = MessagingParticipantStatuses.visible,
        )
        return ConversationPartnerInviteResponseDto(status = ConversationParticipantStatus.DECLINED)
    }

    suspend fun leaveConversation(
        userId: UUID,
        conversationId: UUID,
    ): ConversationPartnerInviteResponseDto {
        val detailBeforeLeave =
            repository.getConversationDetail(userId, conversationId, limit = 1)
                ?: throw MessagingException("CONVERSATION_NOT_FOUND")
        if (!repository.leaveConversation(userId, conversationId)) {
            throw MessagingException("CONVERSATION_NOT_FOUND")
        }
        realtimePublisher.publishDelta(
            type = MessageDeltaType.CONVERSATION_UPDATED,
            conversationId = conversationId,
            participants = detailBeforeLeave.conversation.participants,
            statuses = MessagingParticipantStatuses.visible,
        )
        return ConversationPartnerInviteResponseDto(status = ConversationParticipantStatus.LEFT)
    }
}
