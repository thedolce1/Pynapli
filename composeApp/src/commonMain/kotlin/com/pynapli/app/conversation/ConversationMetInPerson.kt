package com.pynapli.app.conversation

import androidx.compose.runtime.Immutable
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.MetInPersonOfferDto

/**
 * "We've met in person" in a conversation (TRUST_AND_SAFETY_SPEC "Met in person"). The server decides
 * everything (`GET .../met-in-person`, asked for when the overflow menu opens, never on open or refresh):
 * whether an item shows, whether the partner can be ticked, whether the viewer can take it back. A
 * failure only means the menu shows nothing; it never touches the conversation itself.
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
internal data class MetInPersonMenuUi(
    val isLoading: Boolean,
    val items: List<MetInPersonMenuItemUi>,
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

internal const val MET_IN_PERSON_TITLE = "We've met in person"

private fun ConversationUiState.actionableMetInPersonOffers(): List<MetInPersonOfferDto> {
    if (isViewerInvited || isLeaving) return emptyList()
    return metInPersonOffers.orEmpty().filter { it.canConfirm || it.canWithdraw }
}

/** Overflow items; empty when the server offers nothing (or predates the feature). */
internal fun ConversationUiState.metInPersonMenuItems(): List<MetInPersonMenuItemUi> {
    val offers = actionableMetInPersonOffers()
    return offers.map { offer ->
        MetInPersonMenuItemUi(
            subjectUserId = offer.subjectUserId,
            // A direct chat has one other person; a group names who it is about.
            label = if (offers.size == 1) MET_IN_PERSON_TITLE else "We've met ${offer.subjectDisplayName} in person",
        )
    }
}

internal fun ConversationUiState.metInPersonMenuUi(): MetInPersonMenuUi =
    MetInPersonMenuUi(isLoading = isLoadingMetInPersonOffers, items = metInPersonMenuItems())

internal fun ConversationUiState.metInPersonSheetUi(): MetInPersonSheetUi? {
    val sheet = metInPersonSheet ?: return null
    val offer = actionableMetInPersonOffers().firstOrNull { it.subjectUserId == sheet.subjectUserId } ?: return null
    val name = offer.subjectDisplayName
    val partner = offer.partner?.takeIf { offer.canConfirm }
    return if (offer.canConfirm) {
        MetInPersonSheetUi(
            title = MET_IN_PERSON_TITLE,
            body = "Have you met $name in person? They won't be told. Pynapli's safety team can see confirmations if needed.",
            partnerTickLabel = partner?.let { "And ${it.partnerDisplayName}" },
            includePartner = partner != null && sheet.includePartner,
            showConfirm = true,
            showTakeBack = false,
            isSubmitting = sheet.isSubmitting,
            errorMessage = sheet.errorMessage,
        )
    } else {
        MetInPersonSheetUi(
            title = MET_IN_PERSON_TITLE,
            body = "You said you've met $name in person. They weren't told. You can take it back for 30 days after you said it.",
            partnerTickLabel = null,
            includePartner = false,
            showConfirm = false,
            showTakeBack = offer.canWithdraw,
            isSubmitting = sheet.isSubmitting,
            errorMessage = sheet.errorMessage,
        )
    }
}

/** State transitions for the menu and sheet; the server's answer replaces the offer, then the menu refetches. */
internal class ConversationMetInPersonActions(
    private val repository: ConversationRepository,
    private val state: ConversationStateAccess,
) {
    /** Asked for when the overflow menu opens. Any failure leaves the menu empty and nothing else. */
    suspend fun load() {
        val conversationId = state.current.detail?.conversation?.conversationId ?: return
        state.update { it.copy(isLoadingMetInPersonOffers = true) }
        val offers =
            when (val result = repository.metInPersonOffers(conversationId)) {
                is AppResult.Success -> result.value
                is AppResult.Failure -> emptyList()
            }
        state.update { existing ->
            if (existing.detail?.conversation?.conversationId != conversationId) {
                existing.copy(isLoadingMetInPersonOffers = false)
            } else {
                existing.copy(metInPersonOffers = offers, isLoadingMetInPersonOffers = false)
            }
        }
    }

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
                val updated = result.value
                state.update { existing ->
                    existing.copy(
                        metInPersonOffers = existing.metInPersonOffers?.map { if (it.subjectUserId == updated.subjectUserId) updated else it },
                        metInPersonSheet = null,
                    )
                }
                // A couple confirmation changes the partner's entry too: take the server's word for all of it.
                load()
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
