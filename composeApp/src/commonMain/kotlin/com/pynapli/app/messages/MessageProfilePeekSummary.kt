package com.pynapli.app.messages

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pynapli.app.profile.ProfileDetailUiState

@Composable
internal fun ProfilePeekSummary(state: ProfileDetailUiState) {
    val profile = state.profile
    val chips = listOfNotNull(
        profile?.displayAge?.toString(),
        profile?.relationshipContext?.displayToken(),
        profile?.gender?.displayToken(),
        profile?.orientation?.displayToken()
    )

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (chips.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                chips.forEach { label ->
                    ProfilePeekChip(label = label)
                }
            }
        }
        Text(
            text = profile?.discoveryStyle?.displayProfileSentence()
                ?: "Open to a calm, respectful conversation.",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium
        )
        profile?.linkedPartner?.displayName?.let { partnerName ->
            Text(
                text = "Linked with $partnerName",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun ProfilePeekChip(label: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
        )
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

private fun String.displayToken(): String =
    split('_', '-', ' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { part -> part.replaceFirstChar { char -> char.uppercaseChar() } }

private fun String.displayProfileSentence(): String =
    when (this) {
        "solo" -> "Dating solo. Social first."
        "together" -> "Best when plans include their linked partner."
        "both" -> "Open to solo or together, with clear intent."
        else -> "Open to a calm, respectful conversation."
    }
