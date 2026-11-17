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
import com.pynapli.app.design.PynapliBottomSheet
import com.pynapli.app.design.PynapliShape
import com.pynapli.app.design.PynapliSheetActionRow
import com.pynapli.app.design.PynapliSheetColumn
import com.pynapli.app.design.PynapliSheetTitle
import com.pynapli.app.design.handCursor
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.filled.Person
import com.pynapli.shared.network.dto.PineappleLikeNote

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
                modifier = Modifier.testTag(SwipeTestTags.LINKED_PARTNER_PREVIEW_VIEW_PROFILE).handCursor(),
            ) {
                Text("View profile")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.handCursor()) {
                Text("Close")
            }
        },
    )
}

/**
 * The Pineapple Like flow (Intro Notes merged into it, 2026-11-17): one Pineapple Like is spent,
 * with or without the optional note. When the recipient doesn't accept notes ([notesRefused]), the
 * field goes away and the sheet offers the Pineapple Like on its own.
 */
@Composable
internal fun PineappleLikeSheet(
    displayName: String,
    noteText: String,
    notesRefused: Boolean,
    isSubmitting: Boolean,
    onNoteTextChanged: (String) -> Unit,
    onDismiss: () -> Unit,
    onSend: () -> Unit,
) {
    val trimmed = noteText.trim()
    val noteFits = trimmed.length <= PineappleLikeNote.MAX_LENGTH
    val canSend = !isSubmitting && (notesRefused || noteFits)
    PynapliBottomSheet(
        onDismiss = { if (!isSubmitting) onDismiss() },
        modifier = Modifier.testTag(SwipeTestTags.PINEAPPLE_LIKE_SHEET),
    ) {
        PynapliSheetColumn {
            PynapliSheetTitle(text = "Send $displayName a Pineapple Like")
            if (notesRefused) {
                Text(
                    text = "$displayName isn't accepting notes with Pineapple Likes. You can still send the Pineapple Like on its own.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    text = "Stand out in their likes. Add a short note if you want; it uses one Pineapple Like either way.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = noteText,
                    onValueChange = onNoteTextChanged,
                    enabled = !isSubmitting,
                    placeholder = { Text("Add a note (optional)") },
                    isError = !noteFits,
                    maxLines = 4,
                    singleLine = false,
                    shape = PynapliShape.CardShape,
                    modifier = Modifier.fillMaxWidth().testTag(SwipeTestTags.PINEAPPLE_LIKE_NOTE_FIELD),
                )
                Text(
                    text = "${trimmed.length}/${PineappleLikeNote.MAX_LENGTH}",
                    color = if (noteFits) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            PynapliSheetActionRow {
                TextButton(
                    onClick = onDismiss,
                    enabled = !isSubmitting,
                    modifier = Modifier.handCursor(enabled = !isSubmitting),
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = onSend,
                    enabled = canSend,
                    modifier =
                        Modifier
                            .handCursor(enabled = canSend)
                            .testTag(
                                if (notesRefused) SwipeTestTags.PINEAPPLE_LIKE_SEND_WITHOUT_NOTE else SwipeTestTags.PINEAPPLE_LIKE_SEND,
                            ),
                ) {
                    Text(
                        text =
                            when {
                                isSubmitting -> "Sending"
                                notesRefused -> "Send without note"
                                else -> "Send Pineapple Like"
                            },
                    )
                }
            }
        }
    }
}

/** The note field accepts a little past the limit so a paste isn't silently cut; the counter turns red. */
internal const val PINEAPPLE_NOTE_INPUT_MAX_LENGTH = PineappleLikeNote.MAX_LENGTH + 20
