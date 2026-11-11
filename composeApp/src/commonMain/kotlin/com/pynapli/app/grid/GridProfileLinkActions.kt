package com.pynapli.app.grid

import com.pynapli.app.profile.ProfileLinkActionState
import com.pynapli.app.profilelink.ProfileLinksUiState

internal fun ProfileLinksUiState.profileLinkActionFor(
    targetUserId: String?,
    viewerUserId: String?,
): ProfileLinkActionState? {
    if (targetUserId == null || targetUserId == viewerUserId) return null
    val currentLink = current
    val pendingInvite = pendingInviteWith(targetUserId)
    val isSubmitting = targetUserId in submittingTargetUserIds
    val targetFeedback = feedbackMessage.takeIf { feedbackTargetUserId == targetUserId }
    val targetError = errorMessage.takeIf { errorTargetUserId == targetUserId }

    return when {
        currentLink?.otherUserId == targetUserId ->
            ProfileLinkActionState(
                label = "Linked",
                enabled = false,
                isSubmitting = false,
                feedbackMessage = targetFeedback,
                errorMessage = targetError,
            )

        currentLink != null -> null

        pendingInvite != null && pendingInvite.direction == "outgoing" ->
            ProfileLinkActionState(
                label = "Invite sent",
                enabled = false,
                isSubmitting = false,
                feedbackMessage = targetFeedback,
                errorMessage = targetError,
            )

        pendingInvite != null && pendingInvite.direction == "incoming" ->
            ProfileLinkActionState(
                label = "Review invite",
                enabled = false,
                isSubmitting = false,
                feedbackMessage = targetFeedback,
                errorMessage = targetError,
            )

        else ->
            ProfileLinkActionState(
                label = if (isSubmitting) "Sending invite" else "Invite to link as partners",
                enabled = true,
                isSubmitting = isSubmitting,
                feedbackMessage = targetFeedback,
                errorMessage = targetError,
                offeredInMenu = !isSubmitting,
            )
    }
}
