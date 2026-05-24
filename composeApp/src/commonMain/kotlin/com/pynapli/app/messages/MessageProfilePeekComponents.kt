package com.pynapli.app.messages

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pynapli.app.profile.ProfileDetailUiState

@Composable
internal fun ProfilePeekHero(
    displayName: String,
    displayAge: Int?,
    online: Boolean,
    distanceLabel: String?,
    photoUrl: String?,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(330.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF1F3630), Color(0xFF111214))
                )
            )
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.64f), RoundedCornerShape(8.dp))
    ) {
        if (photoUrl != null) {
            AsyncImage(
                model = photoUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(330.dp)
            )
        } else {
            Text(
                text = displayName.firstOrNull()?.uppercaseChar()?.toString().orEmpty(),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.58f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.68f)
                    )
                )
        )

        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp)
                .size(38.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.56f))
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Close profile",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = displayName,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                displayAge?.let { age ->
                    Text(
                        text = age.toString(),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (online) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF13E58A))
                    )
                    Text(
                        text = "Online",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                distanceLabel?.let { distance ->
                    Text(
                        text = if (online) "- $distance" else distance,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

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

@Composable
internal fun ProfilePeekActions(
    canMessage: Boolean,
    canSpark: Boolean,
    canRequestAlbum: Boolean,
    isSparkLoading: Boolean,
    isAlbumLoading: Boolean,
    albumStatus: String,
    onMessage: () -> Unit,
    onSpark: () -> Unit,
    onRequestAlbum: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onMessage,
                enabled = canMessage,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Filled.ChatBubble,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Message",
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            IconButton(
                onClick = {},
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreHoriz,
                    contentDescription = "Profile options",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onSpark,
                enabled = canSpark && !isSparkLoading,
                modifier = Modifier.weight(1f)
            ) {
                if (isSparkLoading) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                } else {
                    Icon(
                        imageVector = Icons.Filled.FavoriteBorder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = "Spark",
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            OutlinedButton(
                onClick = onRequestAlbum,
                enabled = canRequestAlbum && albumStatus != "pending" && !isAlbumLoading,
                modifier = Modifier.weight(1f)
            ) {
                if (isAlbumLoading) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                } else {
                    Icon(
                        imageVector = Icons.Filled.PhotoLibrary,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = when (albumStatus) {
                        "granted" -> "View"
                        "pending" -> "Pending"
                        else -> "Request"
                    },
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

@Composable
internal fun FeedbackText(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold
    )
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
