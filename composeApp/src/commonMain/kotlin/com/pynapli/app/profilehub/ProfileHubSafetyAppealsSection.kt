package com.pynapli.app.profilehub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.handCursor
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.filled.Security
import com.pynapli.shared.network.dto.APPEAL_REASON_MAX_LENGTH
import com.pynapli.shared.network.dto.AppealDto

private const val MIN_APPEAL_REASON = 10

/**
 * Your appeals and their status, plus a way to appeal a restriction on the account (when Sparks,
 * messages or Tonight say they're unavailable). Removed photos and messages are appealed the same
 * way from support; this covers the account-level case people actually hit.
 */
internal fun LazyListScope.profileHubSafetyAppealsSection(
    state: ProfileHubUiState,
    onAppealRestriction: (String) -> Unit,
) {
    item {
        var showDialog by remember { mutableStateOf(false) }
        ProfileHubSection("Appeals") {
            if (state.appeals.isEmpty()) {
                SafetyCenterInfoRow(
                    icon = Icons.Filled.Security,
                    label = "No appeals",
                    value = "If something on your account is limited and you think it's a mistake, appeal it here.",
                )
            } else {
                state.appeals.forEach { appeal ->
                    SafetyCenterInfoRow(
                        icon = Icons.Filled.Security,
                        label = appeal.actionLabel(),
                        value = appeal.statusLabel(),
                    )
                }
            }
            state.appealMessage?.let { message ->
                Text(
                    text = message,
                    color = if (state.appealMessageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(
                onClick = { showDialog = true },
                enabled = !state.isSubmittingAppeal,
                modifier = Modifier.fillMaxWidth().handCursor(enabled = !state.isSubmittingAppeal),
            ) {
                Text(if (state.isSubmittingAppeal) "Sending appeal" else "Appeal a restriction")
            }
        }
        if (showDialog) {
            AppealRestrictionDialog(
                onSend = { reason ->
                    showDialog = false
                    onAppealRestriction(reason)
                },
                onDismiss = { showDialog = false },
            )
        }
    }
}

@Composable
private fun AppealRestrictionDialog(
    onSend: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var reason by remember { mutableStateOf("") }
    val canSend = reason.trim().length >= MIN_APPEAL_REASON
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Appeal a restriction") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tell our safety team what happened. A person reviews every appeal.")
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it.take(APPEAL_REASON_MAX_LENGTH) },
                    label = { Text("What happened?") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSend(reason) }, enabled = canSend, modifier = Modifier.handCursor(enabled = canSend)) {
                Text("Send appeal")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.handCursor()) { Text("Cancel") }
        },
    )
}

internal fun AppealDto.actionLabel(): String =
    when (actionType) {
        "risk_restriction" -> "Account restriction"
        "account_suspension" -> "Account suspension"
        "account_ban" -> "Account ban"
        "media_removal" -> "Removed photo"
        "message_removal" -> "Removed message"
        else -> "Appeal"
    }

internal fun AppealDto.statusLabel(): String =
    when (status) {
        "open", "under_review", "escalated" -> "In review"
        "restored" -> "Restored: the decision was reversed"
        "reduced" -> "Reduced: the restriction was eased"
        "upheld" -> adminNote?.let { "Upheld: $it" } ?: "Upheld: the decision stands"
        "verification_required" -> "Verify your profile to continue"
        "withdrawn" -> "Withdrawn"
        else -> status
    }
