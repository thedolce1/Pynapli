package com.pynapli.app.profilehub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pynapli.shared.network.dto.ReportCategory
import com.pynapli.shared.network.dto.ReportHistoryItemDto
import com.pynapli.shared.network.dto.ReportTargetType
import com.pynapli.shared.network.dto.SupportContactDto

internal fun LazyListScope.profileHubSafetyReportsSection(
    state: ProfileHubUiState,
    onContactSupport: (SupportContactDto) -> Unit,
) {
    item {
        ProfileHubSection("Reports and support") {
            when {
                state.isSupportContactLoading -> {
                    SafetyCenterInfoRow(
                        icon = Icons.Filled.ChatBubble,
                        label = "Support",
                        value = "Loading support contact.",
                    )
                }

                state.supportContact != null -> {
                    SupportContactRow(
                        contact = state.supportContact,
                        onContactSupport = onContactSupport,
                    )
                }

                else -> {
                    SafetyCenterInfoRow(
                        icon = Icons.Filled.ChatBubble,
                        label = "Support",
                        value = "Support contact will appear here.",
                    )
                }
            }

            when {
                state.isReportHistoryLoading -> {
                    SafetyCenterInfoRow(
                        icon = Icons.Filled.Security,
                        label = "Report history",
                        value = "Checking reports you submitted.",
                    )
                }

                state.reportHistory.isEmpty() -> {
                    SafetyCenterInfoRow(
                        icon = Icons.Filled.Security,
                        label = "No report history",
                        value = "Reports you submit will appear here.",
                    )
                }

                else -> {
                    SafetyCenterInfoRow(
                        icon = Icons.Filled.Security,
                        label = "Report history",
                        value = "${state.reportHistory.size} submitted report${if (state.reportHistory.size == 1) "" else "s"}.",
                    )
                }
            }
        }
    }

    if (!state.isReportHistoryLoading && state.reportHistory.isNotEmpty()) {
        items(
            items = state.reportHistory,
            key = { it.reportId },
        ) { report ->
            ReportHistoryRow(report = report)
        }
    }
}

@Composable
private fun SupportContactRow(
    contact: SupportContactDto,
    onContactSupport: (SupportContactDto) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                .clickable { onContactSupport(contact) }
                .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SafetyIcon(icon = Icons.Filled.ChatBubble)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = "Contact support",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = contact.email,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = { onContactSupport(contact) }) {
            Text("Email")
        }
    }
}

@Composable
private fun ReportHistoryRow(report: ReportHistoryItemDto) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SafetyIcon(icon = Icons.Filled.Security)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = report.reportedDisplayName?.takeIf { it.isNotBlank() } ?: report.targetType.toTargetLabel(),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${report.category.toReportCategoryLabel()} - ${report.status.toReportStatusLabel()}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = report.createdAt.take(10).takeIf { it.isNotBlank() } ?: "Submitted",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun String.toTargetLabel(): String =
    when (this) {
        ReportTargetType.MESSAGE -> "Reported message"
        ReportTargetType.MEDIA -> "Reported media"
        ReportTargetType.ALBUM -> "Reported album"
        ReportTargetType.SESSION -> "Reported session"
        else -> "Reported profile"
    }

private fun String.toReportStatusLabel(): String =
    when (this) {
        "under_review" -> "Under review"
        "reviewed" -> "Reviewed"
        else -> "Received"
    }

private fun String.toReportCategoryLabel(): String =
    when (this) {
        ReportCategory.UNDERAGE_USER -> "Underage user"
        ReportCategory.HARASSMENT_OR_THREATS -> "Harassment or threats"
        ReportCategory.EXPLICIT_PUBLIC_CONTENT -> "Explicit public content"
        ReportCategory.SPAM_OR_FAKE_PROFILE -> "Spam or fake profile"
        ReportCategory.IMPERSONATION -> "Impersonation"
        ReportCategory.SOLICITATION -> "Solicitation"
        ReportCategory.NON_CONSENSUAL_CONTENT -> "Non-consensual content"
        ReportCategory.PRIVATE_INFO_DOXXING -> "Private info or doxxing"
        else -> "Other"
    }
