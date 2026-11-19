package com.pynapli.app.conversation

import androidx.compose.runtime.Immutable

/**
 * What the conversation chrome shows for the server's "Bring your partner" offer
 * (`ConversationDto.partnerOffers`). The server decides every condition; this only words it. Acting on
 * [bringPartnerName] is the ordinary partner invite, so its accepts and approvals are unchanged.
 */
@Immutable
internal data class ConversationPartnerOfferUi(
    // The viewer's own linked partner, offered with one tap ("Invite <name>").
    val bringPartnerName: String?,
    // Other participants' partners the viewer may see: a note to ask that participant, no action.
    val participantPartnerNotes: List<String>,
)

/** Null when the server sent no offer (or predates it): the chrome then renders as before. */
internal fun ConversationUiState.partnerOfferUi(): ConversationPartnerOfferUi? {
    if (isViewerInvited || isLeaving) return null
    val conversation = detail?.conversation ?: return null
    val offers = conversation.partnerOffers ?: return null
    val notes =
        offers.participantPartners.mapNotNull { offer ->
            val participantName =
                conversation.participants
                    .firstOrNull { it.userId == offer.participantUserId }
                    ?.displayName
                    ?: return@mapNotNull null
            "$participantName is linked with ${offer.partnerDisplayName}. " +
                "Ask $participantName if ${offer.partnerDisplayName} would like to join."
        }
    val bringPartnerName = offers.bringPartner?.partnerDisplayName
    if (bringPartnerName == null && notes.isEmpty()) return null
    return ConversationPartnerOfferUi(bringPartnerName = bringPartnerName, participantPartnerNotes = notes)
}
