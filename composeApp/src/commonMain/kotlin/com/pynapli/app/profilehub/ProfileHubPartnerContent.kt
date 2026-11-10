package com.pynapli.app.profilehub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliLayout
import com.pynapli.app.design.handCursor
import com.pynapli.app.profilelink.ProfileLinkUi
import com.pynapli.app.profilelink.ProfileLinksUiState

/** PROFILE_SETTINGS_SPEC "Partner Visibility Options" — owner-controlled. */
private val partnerVisibilityOptions =
    listOf(
        "public" to "Show partner publicly",
        "matches_only" to "Matches only",
        "verified_only" to "Verified users only",
        "hidden" to "Hide partner",
    )

@Composable
internal fun PartnerManagementContent(
    state: ProfileHubUiState,
    profileLinksState: ProfileLinksUiState,
    onBack: () -> Unit,
    onAcceptInvite: (String) -> Unit,
    onDeclineInvite: (String) -> Unit,
    onUnlink: () -> Unit,
    onPartnerVisibilityChanged: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasCurrentPartner = profileLinksState.current != null
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        ProfileHubTopBar(
            title = "Linked partner",
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        LazyColumn(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            contentPadding = PaddingValues(start = PynapliLayout.ScreenPadding, end = PynapliLayout.ScreenPadding, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.errorMessage?.let { message ->
                item { ProfileHubMessageBand(message = message, isError = true, onDismiss = { }) }
            }
            profileLinksState.errorMessage?.let { message ->
                item { ProfileHubMessageBand(message = message, isError = true, onDismiss = { }) }
            }
            profileLinksState.feedbackMessage?.let { message ->
                item { ProfileHubMessageBand(message = message, isError = false, onDismiss = { }) }
            }

            item {
                ProfileHubSection("Linked partner") {
                    val current = profileLinksState.current
                    if (current != null) {
                        PartnerLinkRow(
                            link = current,
                            statusLabel = "Linked",
                            busy = current.linkId in profileLinksState.mutatingLinkIds,
                            actionLabel = "Unlink",
                            onAction = onUnlink,
                        )
                    } else {
                        Text(
                            text = "No linked partner. Open a profile to send a link invite.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            if (profileLinksState.incomingInvites.isNotEmpty()) {
                item {
                    ProfileHubSection("Invites to review") {
                        profileLinksState.incomingInvites.forEach { invite ->
                            PartnerInviteRow(
                                link = invite,
                                busy = invite.linkId in profileLinksState.mutatingLinkIds,
                                onAccept = { onAcceptInvite(invite.linkId) },
                                onDecline = { onDeclineInvite(invite.linkId) },
                            )
                        }
                    }
                }
            }

            if (profileLinksState.outgoingInvites.isNotEmpty()) {
                item {
                    ProfileHubSection("Sent invites") {
                        profileLinksState.outgoingInvites.forEach { invite ->
                            PartnerLinkRow(
                                link = invite,
                                statusLabel = "Pending",
                                busy = false,
                                actionLabel = null,
                                onAction = {},
                            )
                        }
                    }
                }
            }

            if (hasCurrentPartner) {
                item {
                    ProfileHubSection("Partner visibility") {
                        Text(
                            text = "Who can see that you have a linked partner.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                        partnerVisibilityOptions.forEach { (value, label) ->
                            DiscoveryStyleRow(
                                label = label,
                                value = value,
                                selected = state.privacyDraft.partnerVisibility == value,
                                onSelected = onPartnerVisibilityChanged,
                            )
                        }
                    }
                }
            }
        }

        if (hasCurrentPartner) {
            ProfileHubSaveButton(
                label = "Save partner settings",
                isSaving = state.isSaving,
                onSave = onSave,
            )
        }
    }
}

@Composable
private fun PartnerLinkRow(
    link: ProfileLinkUi,
    statusLabel: String,
    busy: Boolean,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = link.displayLabel,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = statusLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (busy) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        } else if (actionLabel != null) {
            TextButton(onClick = onAction, modifier = Modifier.handCursor()) {
                Text(text = actionLabel, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun PartnerInviteRow(
    link: ProfileLinkUi,
    busy: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = link.displayLabel,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (busy) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        } else {
            TextButton(onClick = onDecline, modifier = Modifier.handCursor()) {
                Text(text = "Decline", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onAccept, modifier = Modifier.handCursor()) {
                Text(text = "Accept", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
