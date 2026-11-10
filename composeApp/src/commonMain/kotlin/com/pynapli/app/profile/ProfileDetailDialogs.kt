package com.pynapli.app.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliConfirmDialog
import com.pynapli.app.design.handCursor
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.filled.Check
import com.pynapli.shared.network.dto.ReportCategory

@Composable
internal fun BlockConfirmDialog(
    displayName: String,
    isBlocking: Boolean,
    onConfirm: (reason: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    PynapliConfirmDialog(
        title = "Block $displayName?",
        body =
            "They will not see you in the grid or messages, " +
                "and any open conversation will be hidden.",
        confirmLabel = "Block",
        destructive = true,
        isBusy = isBlocking,
        onConfirm = { onConfirm(null) },
        onDismiss = onDismiss,
    )
}

@Composable
internal fun HideProfileConfirmDialog(
    displayName: String,
    isHiding: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    PynapliConfirmDialog(
        title = "Hide $displayName?",
        body =
            "They will stop appearing in your Grid and Swipe deck. " +
                "You can unhide them from Safety Center.",
        confirmLabel = "Hide",
        isBusy = isHiding,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Composable
internal fun ReportCategoryDialog(
    isSubmitting: Boolean,
    onSubmit: (category: String, note: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report this profile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Pick the closest reason. Moderators review reports.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ReportCategory.ALL.forEach { category ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                    ) {
                        TextButton(
                            onClick = { selectedCategory = category },
                            modifier = Modifier.weight(1f).handCursor(),
                        ) {
                            Text(
                                text = category.toReportLabel(),
                                color =
                                    if (selectedCategory == category) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (selectedCategory == category) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val category = selectedCategory ?: return@Button
                    onSubmit(category, null)
                },
                enabled = !isSubmitting && selectedCategory != null,
                modifier = Modifier.handCursor(enabled = !isSubmitting && selectedCategory != null),
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                } else {
                    Text("Submit")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSubmitting, modifier = Modifier.handCursor(enabled = !isSubmitting)) { Text("Cancel") }
        },
    )
}

private fun String.toReportLabel(): String =
    when (this) {
        ReportCategory.UNDERAGE_USER -> "Underage user"
        ReportCategory.HARASSMENT_OR_THREATS -> "Harassment or threats"
        ReportCategory.EXPLICIT_PUBLIC_CONTENT -> "Explicit public content"
        ReportCategory.SPAM_OR_FAKE_PROFILE -> "Spam or fake profile"
        ReportCategory.IMPERSONATION -> "Impersonation"
        ReportCategory.SOLICITATION -> "Solicitation"
        ReportCategory.NON_CONSENSUAL_CONTENT -> "Non-consensual content"
        ReportCategory.PRIVATE_INFO_DOXXING -> "Private info or doxxing"
        ReportCategory.OTHER -> "Other"
        else -> this
    }
