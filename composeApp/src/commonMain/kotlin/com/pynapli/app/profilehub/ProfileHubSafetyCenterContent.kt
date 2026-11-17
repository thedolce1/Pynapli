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
import com.pynapli.app.design.PynapliLayout
import com.pynapli.app.design.PynapliShape
import com.pynapli.app.design.handCursor
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.filled.Block
import com.pynapli.app.design.icons.filled.ChatBubble
import com.pynapli.app.design.icons.filled.LocationOn
import com.pynapli.app.design.icons.filled.Lock
import com.pynapli.app.design.icons.filled.People
import com.pynapli.app.design.icons.filled.PhotoLibrary
import com.pynapli.app.design.icons.filled.Security
import com.pynapli.app.design.icons.filled.VerifiedUser
import com.pynapli.app.design.icons.filled.VisibilityOff
import com.pynapli.shared.network.dto.BlockSummaryDto
import com.pynapli.shared.network.dto.HiddenProfileSummaryDto
import com.pynapli.shared.network.dto.SupportContactDto

@Composable
internal fun ProfileHubSafetyCenterContent(
    state: ProfileHubUiState,
    onBack: () -> Unit,
    onUnblockUser: (String) -> Unit,
    onUnhideProfile: (String) -> Unit,
    onContactSupport: (SupportContactDto) -> Unit,
    onClearMessage: () -> Unit,
    modifier: Modifier = Modifier,
    onAppealRestriction: (String) -> Unit = {},
    onWithdrawAppeal: (String) -> Unit = {},
) {
    LazyColumn(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = PynapliLayout.ScreenPadding, end = PynapliLayout.ScreenPadding, bottom = ProfileSurfaceBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ProfileHubTopBar(
                title = "Safety center",
                onBack = onBack,
            )
        }

        state.errorMessage?.let { message ->
            item {
                ProfileHubMessageBand(
                    message = message,
                    isError = true,
                    onDismiss = onClearMessage,
                )
            }
        }

        state.feedbackMessage?.let { message ->
            item {
                ProfileHubMessageBand(
                    message = message,
                    isError = false,
                    onDismiss = onClearMessage,
                )
            }
        }

        item {
            ProfileHubSection("Safety") {
                SafetyCenterInfoRow(
                    icon = Icons.Filled.VerifiedUser,
                    label = "Consent and boundaries",
                    value = "Only continue when everyone is clear and comfortable.",
                )
                SafetyCenterInfoRow(
                    icon = Icons.Filled.Block,
                    label = "Block and report",
                    value = "Blocking removes contact. Reporting sends context for review.",
                )
                SafetyCenterInfoRow(
                    icon = Icons.Filled.LocationOn,
                    label = "Location privacy",
                    value = "Use distance controls and pause discovery when needed.",
                )
                SafetyCenterInfoRow(
                    icon = Icons.Filled.PhotoLibrary,
                    label = "Private albums",
                    value = "Grant intentionally. Revoke access any time.",
                )
                SafetyCenterInfoRow(
                    icon = Icons.Filled.People,
                    label = "Partner links",
                    value = "Your messages, albums, and Activity stay yours.",
                )
                SafetyCenterInfoRow(
                    icon = Icons.Filled.ChatBubble,
                    label = "Support",
                    value = state.supportContact?.email ?: "Support contact is loading.",
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
                            value = "Checking your block list.",
                        )
                    }

                    state.blockedUsers.isEmpty() -> {
                        SafetyCenterInfoRow(
                            icon = Icons.Filled.Lock,
                            label = "No blocked users",
                            value = "Blocked accounts will appear here.",
                        )
                    }

                    else -> Unit
                }
            }
        }

        if (!state.isBlocksLoading && state.blockedUsers.isNotEmpty()) {
            items(
                items = state.blockedUsers,
                key = { it.blockedUserId },
            ) { blockedUser ->
                BlockedUserRow(
                    blockedUser = blockedUser,
                    isUnblocking = state.unblockingUserId == blockedUser.blockedUserId,
                    onUnblockUser = onUnblockUser,
                )
            }
        }

        item {
            ProfileHubSection("Hidden profiles") {
                when {
                    state.isHiddenProfilesLoading -> {
                        SafetyCenterInfoRow(
                            icon = Icons.Filled.VisibilityOff,
                            label = "Loading",
                            value = "Checking profiles hidden from discovery.",
                        )
                    }

                    state.hiddenProfiles.isEmpty() -> {
                        SafetyCenterInfoRow(
                            icon = Icons.Filled.VisibilityOff,
                            label = "No hidden profiles",
                            value = "Hidden profiles will not appear in your Grid or Swipe deck.",
                        )
                    }

                    else -> Unit
                }
            }
        }

        if (!state.isHiddenProfilesLoading && state.hiddenProfiles.isNotEmpty()) {
            items(
                items = state.hiddenProfiles,
                key = { it.hiddenUserId },
            ) { hiddenProfile ->
                HiddenProfileRow(
                    hiddenProfile = hiddenProfile,
                    isUnhiding = state.unhidingUserId == hiddenProfile.hiddenUserId,
                    onUnhideProfile = onUnhideProfile,
                )
            }
        }

        profileHubSafetyReportsSection(
            state = state,
            onContactSupport = onContactSupport,
        )

        profileHubSafetyAppealsSection(
            state = state,
            onAppealRestriction = onAppealRestriction,
            onWithdrawAppeal = onWithdrawAppeal,
        )
    }
}

@Composable
internal fun SafetyCenterInfoRow(
    icon: ImageVector,
    label: String,
    value: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SafetyIcon(icon = icon)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BlockedUserRow(
    blockedUser: BlockSummaryDto,
    isUnblocking: Boolean,
    onUnblockUser: (String) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(PynapliShape.CardShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, PynapliShape.CardShape)
                .handCursor(enabled = !isUnblocking)
                .clickable(enabled = !isUnblocking) { onUnblockUser(blockedUser.blockedUserId) }
                .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SafetyIcon(icon = Icons.Filled.Block)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text =
                    blockedUser.blockedDisplayName?.takeIf { it.isNotBlank() }
                        ?: "Member ${blockedUser.blockedUserId.take(8)}",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = blockedUser.createdAt.take(10).takeIf { it.isNotBlank() } ?: "Blocked",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(
            onClick = { onUnblockUser(blockedUser.blockedUserId) },
            enabled = !isUnblocking,
            modifier = Modifier.handCursor(enabled = !isUnblocking),
        ) {
            Text(if (isUnblocking) "Unblocking" else "Unblock")
        }
    }
}

@Composable
private fun HiddenProfileRow(
    hiddenProfile: HiddenProfileSummaryDto,
    isUnhiding: Boolean,
    onUnhideProfile: (String) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(PynapliShape.CardShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, PynapliShape.CardShape)
                .handCursor(enabled = !isUnhiding)
                .clickable(enabled = !isUnhiding) { onUnhideProfile(hiddenProfile.hiddenUserId) }
                .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SafetyIcon(icon = Icons.Filled.VisibilityOff)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text =
                    hiddenProfile.hiddenDisplayName?.takeIf { it.isNotBlank() }
                        ?: "Member ${hiddenProfile.hiddenUserId.take(8)}",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = hiddenProfile.createdAt.take(10).takeIf { it.isNotBlank() } ?: "Hidden",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(
            onClick = { onUnhideProfile(hiddenProfile.hiddenUserId) },
            enabled = !isUnhiding,
            modifier = Modifier.handCursor(enabled = !isUnhiding),
        ) {
            Text(if (isUnhiding) "Unhiding" else "Unhide")
        }
    }
}

@Composable
internal fun SafetyIcon(icon: ImageVector) {
    Box(
        modifier =
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
    }
}
