package com.pynapli.shared.network.dto

import kotlinx.serialization.Serializable

/**
 * The viewer's own "Met in person" state for one other participant of a conversation
 * (TRUST_AND_SAFETY_SPEC "Met in person"). Server-decided from facts the viewer already knows; it never
 * says whether anyone else confirmed this person, how many did, or whether a badge exists.
 *
 * [canConfirm]: the overflow item "We've met in person" may be shown. [canWithdraw]: the viewer's own
 * confirmation is less than 30 days old and can be taken back. [partner]: the person's linked partner,
 * also in this conversation, whom the viewer may tick in the same confirmation.
 */
@Serializable
data class MetInPersonOfferDto(
    val subjectUserId: String,
    val subjectDisplayName: String,
    val given: Boolean = false,
    val canConfirm: Boolean = false,
    val canWithdraw: Boolean = false,
    val partner: MetInPersonPartnerDto? = null,
)

/** `POST /v1/met-in-person/offers`: the viewer's own actions, asked for when the menu opens. */
@Serializable
data class MetInPersonOffersDto(
    val offers: List<MetInPersonOfferDto> = emptyList(),
)

@Serializable
data class MetInPersonPartnerDto(
    val partnerUserId: String,
    val partnerDisplayName: String,
)

@Serializable
data class ConfirmMetInPersonRequestDto(
    val subjectUserId: String,
    val includePartner: Boolean = false,
)

@Serializable
data class WithdrawMetInPersonRequestDto(
    val subjectUserId: String,
)

/**
 * `POST /v1/met-in-person/offers`. The conversation travels in the body, never the path: request logs
 * record paths, and a path per conversation would pin who looked at whom.
 */
@Serializable
data class MetInPersonOffersRequestDto(
    val threadId: String,
)

/** `POST /v1/met-in-person/confirmations`: confirm or take back, everything in the body (see above). */
@Serializable
data class MetInPersonConfirmationRequestDto(
    val threadId: String,
    val subjectUserId: String,
    val includePartner: Boolean = false,
    val action: String = ACTION_CONFIRM,
) {
    companion object {
        const val ACTION_CONFIRM = "confirm"
        const val ACTION_WITHDRAW = "withdraw"
    }
}
