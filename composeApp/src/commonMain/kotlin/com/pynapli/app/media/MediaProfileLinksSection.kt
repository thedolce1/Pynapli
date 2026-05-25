package com.pynapli.app.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pynapli.app.profilelink.ProfileLinksUiState

internal fun LazyGridScope.profileLinksSection(
    state: ProfileLinksUiState,
    onAccept: (String) -> Unit,
    onDecline: (String) -> Unit,
    onUnlink: () -> Unit,
    onClearMessage: () -> Unit
) {
    item(span = { GridItemSpan(maxLineSpan) }) {
        ProfileLinksSection(
            state = state,
            onAccept = onAccept,
            onDecline = onDecline,
            onUnlink = onUnlink,
            onClearMessage = onClearMessage
        )
    }
}

@Composable
private fun ProfileLinksSection(
    state: ProfileLinksUiState,
    onAccept: (String) -> Unit,
    onDecline: (String) -> Unit,
    onUnlink: () -> Unit,
    onClearMessage: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ProfileLinksHeader()

        state.errorMessage?.let { message ->
            LinkMessageBand(
                message = message,
                isError = true,
                onDismiss = onClearMessage
            )
        }
        state.feedbackMessage?.let { message ->
            LinkMessageBand(
                message = message,
                isError = false,
                onDismiss = onClearMessage
            )
        }

        when {
            state.isLoading && state.current == null &&
                state.incomingInvites.isEmpty() &&
                state.outgoingInvites.isEmpty() -> LoadingLinkedProfileRow()

            state.current != null -> ProfileLinkRow(
                link = state.current,
                statusLabel = "Active",
                isBusy = state.current.linkId in state.mutatingLinkIds,
                primaryActionLabel = "Unlink",
                onPrimaryAction = onUnlink
            )

            else -> EmptyLinkedProfileRow()
        }

        if (state.incomingInvites.isNotEmpty()) {
            Text(
                text = "Invites to review",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 2.dp)
            )
            state.incomingInvites.forEach { invite ->
                IncomingProfileLinkRow(
                    link = invite,
                    isBusy = invite.linkId in state.mutatingLinkIds,
                    onAccept = { onAccept(invite.linkId) },
                    onDecline = { onDecline(invite.linkId) }
                )
            }
        }

        if (state.outgoingInvites.isNotEmpty()) {
            Text(
                text = "Sent invites",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 2.dp)
            )
            state.outgoingInvites.forEach { invite ->
                ProfileLinkRow(
                    link = invite,
                    statusLabel = "Pending",
                    isBusy = false,
                    primaryActionLabel = null,
                    onPrimaryAction = {}
                )
            }
        }
    }
}
