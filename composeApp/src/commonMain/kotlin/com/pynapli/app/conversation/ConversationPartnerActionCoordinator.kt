package com.pynapli.app.conversation

import com.pynapli.shared.core.result.AppResult

internal class ConversationPartnerActionCoordinator(
    private val repository: ConversationRepository,
    private val state: ConversationStateAccess,
    private val refreshOpenConversation: suspend () -> Unit,
    private val dismissConversation: () -> Unit,
) {
    suspend fun inviteLinkedPartner() {
        val current = state.current
        if (current.isInvitingPartner || current.isOpening || current.isViewerInvited) return
        val conversationId = current.detail?.conversation?.conversationId ?: return

        state.update {
            it.copy(
                isInvitingPartner = true,
                partnerInviteFeedback = null,
                errorMessage = null,
            )
        }

        when (val result = repository.inviteLinkedPartner(conversationId)) {
            is AppResult.Success -> {
                state.update { existing ->
                    val existingDetail = existing.detail
                    existing.copy(
                        detail = existingDetail?.copy(conversation = result.value),
                        titleOverride = result.value.titleFor(existing.viewerUserId),
                        isInvitingPartner = false,
                        partnerInviteFeedback = "Partner invited",
                        errorMessage = null,
                    )
                }
            }

            is AppResult.Failure -> {
                state.update { existing ->
                    existing.copy(
                        isInvitingPartner = false,
                        partnerInviteFeedback = null,
                        errorMessage = result.error.message,
                    )
                }
            }
        }
    }

    suspend fun acceptPartnerInvite() {
        val current = state.current
        if (current.isRespondingToInvite || !current.canRespondToPartnerInvite) return
        val conversationId = current.detail?.conversation?.conversationId ?: return

        state.update {
            it.copy(
                isRespondingToInvite = true,
                partnerInviteFeedback = null,
                errorMessage = null,
            )
        }

        when (val result = repository.acceptPartnerInvite(conversationId)) {
            is AppResult.Success -> {
                val detail = result.value.withReadConversation(repository)
                state.update { existing ->
                    existing.copy(
                        detail = detail,
                        titleOverride = detail.conversation.titleFor(existing.viewerUserId),
                        isRespondingToInvite = false,
                        partnerInviteFeedback = current.partnerInviteAcceptedFeedback(detail),
                        errorMessage = null,
                    )
                }
            }

            is AppResult.Failure -> {
                state.update { existing ->
                    existing.copy(
                        isRespondingToInvite = false,
                        partnerInviteFeedback = null,
                        errorMessage = result.error.message,
                    )
                }
            }
        }
    }

    suspend fun declinePartnerInvite() {
        val current = state.current
        if (current.isRespondingToInvite || !current.canRespondToPartnerInvite) return
        val conversationId = current.detail?.conversation?.conversationId ?: return

        state.update {
            it.copy(
                isRespondingToInvite = true,
                partnerInviteFeedback = null,
                errorMessage = null,
            )
        }

        when (val result = repository.declinePartnerInvite(conversationId)) {
            is AppResult.Success -> {
                if (current.isViewerInvited) {
                    dismissConversation()
                } else {
                    refreshOpenConversationAfterInviteDecline(conversationId)
                }
            }
            is AppResult.Failure -> {
                state.update { existing ->
                    existing.copy(
                        isRespondingToInvite = false,
                        partnerInviteFeedback = null,
                        errorMessage = result.error.message,
                    )
                }
            }
        }
    }

    suspend fun leaveConversation() {
        val current = state.current
        if (current.isLeaving || current.isOpening) return
        val conversationId = current.detail?.conversation?.conversationId ?: return

        state.update {
            it.copy(
                isLeaving = true,
                partnerInviteFeedback = null,
                errorMessage = null,
            )
        }

        when (val result = repository.leaveConversation(conversationId)) {
            is AppResult.Success -> dismissConversation()
            is AppResult.Failure -> {
                state.update { existing ->
                    existing.copy(
                        isLeaving = false,
                        partnerInviteFeedback = null,
                        errorMessage = result.error.message,
                    )
                }
            }
        }
    }

    suspend fun setExpiry(expirySeconds: Int?) {
        val current = state.current
        if (current.isSettingExpiry || current.isOpening) return
        if (current.viewerExpirySeconds == expirySeconds) return
        val conversationId = current.detail?.conversation?.conversationId ?: return

        state.update { it.copy(isSettingExpiry = true, partnerInviteFeedback = null, errorMessage = null) }

        when (val result = repository.setConversationExpiry(conversationId, expirySeconds)) {
            is AppResult.Success -> {
                state.update { existing ->
                    existing.copy(
                        detail = existing.detail?.copy(conversation = result.value),
                        isSettingExpiry = false,
                        errorMessage = null,
                    )
                }
                refreshOpenConversation()
            }

            is AppResult.Failure -> {
                val needsPlan = result.error.code == "ENTITLEMENT_REQUIRED"
                state.update { existing ->
                    existing.copy(
                        isSettingExpiry = false,
                        expiryNeedsUpgrade = needsPlan,
                        errorMessage = if (needsPlan) null else result.error.message,
                    )
                }
            }
        }
    }

    private suspend fun refreshOpenConversationAfterInviteDecline(conversationId: String) {
        when (val detailResult = repository.getConversationDetail(conversationId)) {
            is AppResult.Success -> {
                val detail = detailResult.value.withReadConversation(repository)
                state.update { existing ->
                    existing.copy(
                        detail = detail,
                        titleOverride = detail.conversation.titleFor(existing.viewerUserId),
                        isRespondingToInvite = false,
                        partnerInviteFeedback = "Invite declined",
                        errorMessage = null,
                    )
                }
            }

            is AppResult.Failure -> {
                state.update { existing ->
                    existing.copy(
                        isRespondingToInvite = false,
                        partnerInviteFeedback = null,
                        errorMessage = detailResult.error.message,
                    )
                }
            }
        }
    }
}
