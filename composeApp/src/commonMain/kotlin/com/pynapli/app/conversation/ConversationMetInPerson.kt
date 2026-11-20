package com.pynapli.app.conversation

import androidx.compose.runtime.Immutable
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.MetInPersonOfferDto

/**
 * "We've met in person" in a conversation (TRUST_AND_SAFETY_SPEC "Met in person"). The server decides
 * everything (`ConversationDto.metInPersonOffers`, sent on the read mark): whether the item shows,
 * whether the partner can be ticked, whether the viewer can take it back. This file only words it.
 */
@Immutable
data class MetInPersonSheetState(
    val subjectUserId: String,
    val includePartner: Boolean = false,
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
)

@Immutable
internal data class MetInPersonMenuItemUi(
    val subjectUserId: String,
    val label: String,
)

@Immutable
internal data class MetInPersonSheetUi(
    val title: String,
    val body: String,
    // Present only when the server lets the viewer tick the person's partner too.
    val partnerTickLabel: String?,
    val includePartner: Boolean,
    val showConfirm: Boolean,
    val showTakeBack: Boolean,
    val isSubmitting: Boolean,
    val errorMessage: String?,
)

private fun ConversationUiState.metInPersonOffers(): List<MetInPersonOfferDto> {
    if (isViewerInvited || isLeaving) return emptyList()
    return detail
        ?.conversation
        ?.metInPersonOffers
        .orEmpty()
        .filter { it.canConfirm || it.canWithdraw }
}

/** Overflow items; empty when the server offers nothing (or predates the feature). */
internal fun ConversationUiState.metInPersonMenuItems(): List<MetInPersonMenuItemUi> {
    val offers = metInPersonOffers()
    return offers.map { offer ->
        MetInPersonMenuItemUi(
            subjectUserId = offer.subjectUserId,
            // A direct chat has one other person; a group names who it is about.
            label = if (offers.size == 1) "We've met in person" else "We've met ${offer.subjectDisplayName} in person",
        )
    }
}

internal fun ConversationUiState.metInPersonSheetUi(): MetInPersonSheetUi? {
    val sheet = metInPersonSheet ?: return null
    val offer = metInPersonOffers().firstOrNull { it.subjectUserId == sheet.subjectUserId } ?: return null
    val name = offer.subjectDisplayName
    val partner = offer.partner?.takeIf { offer.canConfirm }
    return if (offer.canConfirm) {
        MetInPersonSheetUi(
            title = "We've met in person",
            body =
                "Have you met $name in person, and were they who their profile says? Only you'll know you " +
                    "answered. They won't be told, and nobody sees who confirmed.",
            partnerTickLabel = partner?.let { "And ${it.partnerDisplayName}" },
            includePartner = partner != null && sheet.includePartner,
            showConfirm = true,
            showTakeBack = false,
            isSubmitting = sheet.isSubmitting,
            errorMessage = sheet.errorMessage,
        )
    } else {
        MetInPersonSheetUi(
            title = "We've met in person",
            body =
                "You said you've met $name in person. Only you know you answered, and you can take it back " +
                    "for 30 days after you said it.",
            partnerTickLabel = null,
            includePartner = false,
            showConfirm = false,
            showTakeBack = offer.canWithdraw,
            isSubmitting = sheet.isSubmitting,
            errorMessage = sheet.errorMessage,
        )
    }
}

/** State transitions for the sheet; the server's answer replaces the offer, then the thread refetches. */
internal class ConversationMetInPersonActions(
    private val repository: ConversationRepository,
    private val state: ConversationStateAccess,
    private val refreshOpenConversation: suspend () -> Unit,
) {
    fun open(subjectUserId: String) {
        state.update { it.copy(metInPersonSheet = MetInPersonSheetState(subjectUserId = subjectUserId)) }
    }

    fun setIncludePartner(include: Boolean) {
        state.update { current ->
            current.copy(metInPersonSheet = current.metInPersonSheet?.copy(includePartner = include))
        }
    }

    fun dismiss() {
        state.update { it.copy(metInPersonSheet = null) }
    }

    suspend fun confirm() {
        val current = state.current
        val ui = current.metInPersonSheetUi() ?: return
        if (!ui.showConfirm || ui.isSubmitting) return
        val sheet = current.metInPersonSheet ?: return
        submit { conversationId -> repository.confirmMetInPerson(conversationId, sheet.subjectUserId, ui.includePartner) }
    }

    suspend fun takeBack() {
        val current = state.current
        val ui = current.metInPersonSheetUi() ?: return
        if (!ui.showTakeBack || ui.isSubmitting) return
        val sheet = current.metInPersonSheet ?: return
        submit { conversationId -> repository.withdrawMetInPerson(conversationId, sheet.subjectUserId) }
    }

    private suspend fun submit(call: suspend (String) -> AppResult<MetInPersonOfferDto>) {
        val conversationId = state.current.detail?.conversation?.conversationId ?: return
        state.update { it.copy(metInPersonSheet = it.metInPersonSheet?.copy(isSubmitting = true, errorMessage = null)) }
        when (val result = call(conversationId)) {
            is AppResult.Success -> {
                state.update { existing ->
                    val detail = existing.detail
                    if (detail?.conversation?.conversationId != conversationId) {
                        existing.copy(metInPersonSheet = null)
                    } else {
                        val updated = result.value
                        val offers =
                            detail.conversation.metInPersonOffers
                                .orEmpty()
                                .map { if (it.subjectUserId == updated.subjectUserId) updated else it }
                        existing.copy(
                            detail = detail.copy(conversation = detail.conversation.copy(metInPersonOffers = offers)),
                            metInPersonSheet = null,
                        )
                    }
                }
                // A couple confirmation changes the partner's entry too: take the server's word for all of it.
                refreshOpenConversation()
            }

            is AppResult.Failure -> {
                state.update {
                    it.copy(
                        metInPersonSheet =
                            it.metInPersonSheet?.copy(
                                isSubmitting = false,
                                errorMessage = "That didn't go through. Try again in a moment.",
                            ),
                    )
                }
            }
        }
    }
}
