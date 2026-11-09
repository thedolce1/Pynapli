package com.pynapli.app.swipe

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

@Composable
internal fun LinkedPartnerPreviewDialog(
    cardDisplayName: String,
    partnerDisplayName: String?,
    partnerThumbnailUrl: String?,
    partnerOnline: Boolean,
    onDismiss: () -> Unit,
    onViewProfile: () -> Unit,
) {
    val resolvedPartnerName = partnerDisplayName?.takeIf { it.isNotBlank() } ?: "their partner"
    AlertDialog(
        modifier = Modifier.testTag(SwipeTestTags.LINKED_PARTNER_PREVIEW_DIALOG),
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Linked with $resolvedPartnerName",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (partnerThumbnailUrl != null) {
                            AsyncImage(
                                model = partnerThumbnailUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                    }
                    Text(
                        text =
                            "$resolvedPartnerName is the linked partner of $cardDisplayName. " +
                                "Swipe stays on $cardDisplayName.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (partnerOnline) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.testTag(SwipeTestTags.LINKED_PARTNER_PREVIEW_ONLINE),
                    ) {
                        SwipeOnlineDot()
                        Text(
                            text = "Active now",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onViewProfile,
                modifier = Modifier.testTag(SwipeTestTags.LINKED_PARTNER_PREVIEW_VIEW_PROFILE),
            ) {
                Text("View profile")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

@Composable
internal fun IntroNoteDialog(
    displayName: String,
    noteText: String,
    isSubmitting: Boolean,
    onNoteTextChanged: (String) -> Unit,
    onDismiss: () -> Unit,
    onSend: () -> Unit,
) {
    val trimmed = noteText.trim()
    val canSend = trimmed.isNotEmpty() && trimmed.length <= INTRO_NOTE_UI_MAX_LENGTH && !isSubmitting
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Intro Note",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Send a short note to $displayName before matching.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = noteText,
                    onValueChange = onNoteTextChanged,
                    enabled = !isSubmitting,
                    maxLines = 4,
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "${trimmed.length}/$INTRO_NOTE_UI_MAX_LENGTH",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onSend,
                enabled = canSend,
            ) {
                Text(text = if (isSubmitting) "Sending" else "Send")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSubmitting,
            ) {
                Text("Cancel")
            }
        },
    )
}

private const val INTRO_NOTE_UI_MAX_LENGTH = 140
internal const val INTRO_NOTE_INPUT_MAX_LENGTH = 160
