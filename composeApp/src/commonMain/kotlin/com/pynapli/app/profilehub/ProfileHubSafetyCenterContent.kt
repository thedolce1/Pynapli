package com.pynapli.app.profilehub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pynapli.shared.network.dto.BlockSummaryDto

@Composable
internal fun ProfileHubSafetyCenterContent(
    state: ProfileHubUiState,
    onBack: () -> Unit,
    onUnblockUser: (String) -> Unit,
    onClearMessage: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = ProfileSurfaceBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ProfileHubTopBar(
                title = "Safety center",
                onBack = onBack
            )
        }

        state.errorMessage?.let { message ->
            item {
                ProfileHubMessageBand(
                    message = message,
                    isError = true,
                    onDismiss = onClearMessage
                )
            }
        }

        state.feedbackMessage?.let { message ->
            item {
                ProfileHubMessageBand(
                    message = message,
                    isError = false,
                    onDismiss = onClearMessage
                )
            }
        }

        item {
            ProfileHubSection("Safety") {
                SafetyCenterInfoRow(
                    icon = Icons.Filled.VerifiedUser,
                    label = "Consent and boundaries",
                    value = "Only continue when everyone is clear and comfortable."
                )
                SafetyCenterInfoRow(
                    icon = Icons.Filled.Block,
                    label = "Block and report",
                    value = "Blocking removes contact. Reporting sends context for review."
                )
                SafetyCenterInfoRow(
                    icon = Icons.Filled.LocationOn,
                    label = "Location privacy",
                    value = "Use distance controls and pause discovery when needed."
                )
                SafetyCenterInfoRow(
                    icon = Icons.Filled.PhotoLibrary,
                    label = "Private albums",
                    value = "Grant intentionally. Revoke access any time."
                )
                SafetyCenterInfoRow(
                    icon = Icons.Filled.People,
                    label = "Partner links",
                    value = "Your messages, albums, and Activity stay yours."
                )
                SafetyCenterInfoRow(
                    icon = Icons.Filled.ChatBubble,
                    label = "Support",
                    value = "support@pynapli.com"
                )
            }
        }

        item {
            ProfileHubSection("Blocked users") {
                when {
                    state.isBlocksLoading -> {
                        SafetyCenterInfoRow(
                            icon = Icons.Filled.Security,
                            label = "Loading",
                            value = "Checking your block list."
                        )
                    }

                    state.blockedUsers.isEmpty() -> {
                        SafetyCenterInfoRow(
                            icon = Icons.Filled.Lock,
                            label = "No blocked users",
                            value = "Blocked accounts will appear here."
                        )
                    }

                    else -> Unit
                }
            }
        }

        if (!state.isBlocksLoading && state.blockedUsers.isNotEmpty()) {
            items(
                items = state.blockedUsers,
                key = { it.blockedUserId }
            ) { blockedUser ->
                BlockedUserRow(
                    blockedUser = blockedUser,
                    isUnblocking = state.unblockingUserId == blockedUser.blockedUserId,
                    onUnblockUser = onUnblockUser
                )
            }
        }
    }
}

@Composable
private fun SafetyCenterInfoRow(
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SafetyIcon(icon = icon)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun BlockedUserRow(
    blockedUser: BlockSummaryDto,
    isUnblocking: Boolean,
    onUnblockUser: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .clickable(enabled = !isUnblocking) { onUnblockUser(blockedUser.blockedUserId) }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SafetyIcon(icon = Icons.Filled.Block)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = blockedUser.blockedDisplayName?.takeIf { it.isNotBlank() }
                    ?: "Member ${blockedUser.blockedUserId.take(8)}",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = blockedUser.createdAt.take(10).takeIf { it.isNotBlank() } ?: "Blocked",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        TextButton(
            onClick = { onUnblockUser(blockedUser.blockedUserId) },
            enabled = !isUnblocking
        ) {
            Text(if (isUnblocking) "Unblocking" else "Unblock")
        }
    }
}

@Composable
private fun SafetyIcon(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
    }
}
