package com.pynapli.app.swipe

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pynapli.shared.network.dto.MatchDto

@Composable
internal fun SwipeActions(
    actionEnabled: Boolean,
    rewindEnabled: Boolean,
    isSubmitting: Boolean,
    onRewind: () -> Unit,
    onPass: () -> Unit,
    onIntroNote: () -> Unit,
    onPineappleLike: () -> Unit,
    onLike: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, alignment = Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SwipeActionButton(
            enabled = rewindEnabled,
            isSubmitting = isSubmitting,
            testTag = SwipeTestTags.REWIND_ACTION,
            icon = Icons.Filled.Refresh,
            contentDescription = "Rewind",
            background = MaterialTheme.colorScheme.surfaceVariant,
            iconTint = MaterialTheme.colorScheme.primary,
            onClick = onRewind
        )
        SwipeActionButton(
            enabled = actionEnabled,
            isSubmitting = isSubmitting,
            testTag = SwipeTestTags.PASS_ACTION,
            icon = Icons.Filled.Close,
            contentDescription = "Pass",
            background = Color(0x33EE6650),
            iconTint = Color(0xFFE85A4A),
            onClick = onPass
        )
        SwipeActionButton(
            enabled = actionEnabled,
            isSubmitting = isSubmitting,
            testTag = SwipeTestTags.INTRO_NOTE_ACTION,
            icon = Icons.Filled.Edit,
            contentDescription = "Intro Note",
            background = MaterialTheme.colorScheme.surfaceVariant,
            iconTint = MaterialTheme.colorScheme.primary,
            onClick = onIntroNote
        )
        SwipeActionButton(
            enabled = actionEnabled,
            isSubmitting = isSubmitting,
            testTag = SwipeTestTags.PINEAPPLE_LIKE_ACTION,
            icon = Icons.Filled.Star,
            contentDescription = "Pineapple Like",
            background = Color(0x33F2B84B),
            iconTint = Color(0xFFF2B84B),
            onClick = onPineappleLike
        )
        SwipeActionButton(
            enabled = actionEnabled,
            isSubmitting = isSubmitting,
            testTag = SwipeTestTags.LIKE_ACTION,
            icon = Icons.Filled.Favorite,
            contentDescription = "Like",
            background = Color(0x331FBF7B),
            iconTint = Color(0xFF1FBF7B),
            onClick = onLike
        )
    }
}

@Composable
internal fun LinkedPartnerPreviewDialog(
    cardDisplayName: String,
    partnerDisplayName: String?,
    partnerThumbnailUrl: String?,
    partnerOnline: Boolean,
    onDismiss: () -> Unit,
    onViewProfile: () -> Unit
) {
    val resolvedPartnerName = partnerDisplayName?.takeIf { it.isNotBlank() } ?: "their partner"
    AlertDialog(
        modifier = Modifier.testTag(SwipeTestTags.LINKED_PARTNER_PREVIEW_DIALOG),
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Linked with $resolvedPartnerName",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (partnerThumbnailUrl != null) {
                            AsyncImage(
                                model = partnerThumbnailUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                    Text(
                        text = "$resolvedPartnerName is the linked partner of $cardDisplayName. " +
                            "Swipe stays on $cardDisplayName.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (partnerOnline) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.testTag(SwipeTestTags.LINKED_PARTNER_PREVIEW_ONLINE)
                    ) {
                        SwipeOnlineDot()
                        Text(
                            text = "Active now",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onViewProfile,
                modifier = Modifier.testTag(SwipeTestTags.LINKED_PARTNER_PREVIEW_VIEW_PROFILE)
            ) {
                Text("View profile")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
internal fun IntroNoteDialog(
    displayName: String,
    noteText: String,
    isSubmitting: Boolean,
    onNoteTextChanged: (String) -> Unit,
    onDismiss: () -> Unit,
    onSend: () -> Unit
) {
    val trimmed = noteText.trim()
    val canSend = trimmed.isNotEmpty() && trimmed.length <= INTRO_NOTE_UI_MAX_LENGTH && !isSubmitting
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Intro Note",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Send a short note to $displayName before matching.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = noteText,
                    onValueChange = onNoteTextChanged,
                    enabled = !isSubmitting,
                    maxLines = 4,
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "${trimmed.length}/$INTRO_NOTE_UI_MAX_LENGTH",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onSend,
                enabled = canSend
            ) {
                Text(text = if (isSubmitting) "Sending" else "Send")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSubmitting
            ) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun SwipeActionButton(
    enabled: Boolean,
    isSubmitting: Boolean,
    testTag: String,
    icon: ImageVector,
    contentDescription: String,
    background: Color,
    iconTint: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(background)
            .border(1.dp, iconTint.copy(alpha = 0.45f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(56.dp).testTag(testTag)
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(
                    color = iconTint,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(24.dp)
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = iconTint,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
internal fun EmptyDeck(
    filtersActive: Boolean,
    onRefresh: () -> Unit,
    onOpenFilters: () -> Unit
) {
    // FILTERS_SPEC L487-489: when filters are active and the deck is empty (server
    // already ran the relaxation pass per B3 and still found nothing), nudge the
    // user toward widening filters or switching to Grid. Plain "no profiles" copy
    // remains for the unfiltered case.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Style,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
            modifier = Modifier.size(56.dp)
        )
        Text(
            text = if (filtersActive) "No more recommendations match your filters"
                else "No more profiles nearby",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Text(
            text = if (filtersActive)
                "Try widening distance or removing a dealbreaker. Grid shows live nearby profiles."
                else "Check back later or expand your radius.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (filtersActive) {
                OutlinedButton(onClick = onOpenFilters) {
                    Text("Adjust filters")
                }
            }
            Button(onClick = onRefresh) {
                Icon(imageVector = Icons.Filled.Refresh, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text("Refresh")
            }
        }
    }
}

@Composable
internal fun SwipeError(message: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
internal fun MatchOverlay(
    match: MatchDto,
    showDetails: Boolean,
    onDismiss: () -> Unit,
    onMessage: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .padding(32.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "It's a match",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (showDetails) {
                    match.matchedDisplayName ?: "You both liked each other."
                } else {
                    "You both liked each other."
                },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            Button(
                onClick = onMessage,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("Message")
            }
            TextButton(
                onClick = onDismiss,
            ) {
                Text("Keep swiping")
            }
        }
    }
}

private const val INTRO_NOTE_UI_MAX_LENGTH = 140
internal const val INTRO_NOTE_INPUT_MAX_LENGTH = 160
