package com.pynapli.app.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pynapli.shared.distance.formatDistanceForUnit

@Composable
internal fun GridProfileTile(
    card: GridProfileCardUi,
    distanceUnit: String,
    onClick: () -> Unit
) {
    val distanceLabel = formatDistanceForUnit(
        distanceMeters = card.distanceMeters,
        fallbackLabel = card.distanceLabel,
        distanceUnit = distanceUnit
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable(onClick = onClick)
            .background(card.tone.backgroundBrush())
    ) {
        card.thumbnailUrl?.let { thumbnailUrl ->
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.52f to Color.Transparent,
                            1.0f to Color(0xD9000000)
                        )
                    )
                )
        )

        if (card.linkedPartner != null) {
            LinkedPartnerBadge(
                thumbnailUrl = card.linkedPartner.thumbnailUrl,
                color = card.linkedPartnerTone.badgeColor(),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(7.dp)
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (card.online) {
                OnlineDot(size = 8)
            }

            Text(
                text = card.displayName,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )

            if (card.visiting) {
                // SWIPE_IMPLEMENTATION_SPEC L348: target is here via passport,
                // not physically local. Same wording + tone as the Swipe pill.
                Text(
                    text = "Visiting",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier
                        .testTag(GridCardTags.visitingTag(card.id))
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xCC4E342E))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }

            distanceLabel?.let { distance ->
                Text(
                    text = distance,
                    color = Color(0xFFD7D7D7),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
            }
        }
    }
}

internal object GridCardTags {
    fun visitingTag(cardId: String): String = "grid-card-visiting-$cardId"
}

@Composable
private fun LinkedPartnerBadge(
    thumbnailUrl: String?,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(if (thumbnailUrl == null) color else Color(0xCC111111))
            .border(1.dp, Color(0xD9FFFFFF), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (thumbnailUrl != null) {
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = "Linked partner",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = "Linked partner",
                tint = Color.White,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

private fun GridCardTone.backgroundBrush(): Brush = when (this) {
    GridCardTone.Warm -> Brush.linearGradient(listOf(Color(0xFF2A2A2A), Color(0xFF7D5B3F)))
    GridCardTone.Slate -> Brush.linearGradient(listOf(Color(0xFF263238), Color(0xFF607D8B)))
    GridCardTone.Rust -> Brush.linearGradient(listOf(Color(0xFF1E1B1B), Color(0xFFB85C38)))
    GridCardTone.DeepGreen -> Brush.linearGradient(listOf(Color(0xFF252525), Color(0xFF455A40)))
    GridCardTone.Bronze -> Brush.linearGradient(listOf(Color(0xFF2B2622), Color(0xFF9A6B38)))
    GridCardTone.Graphite -> Brush.linearGradient(listOf(Color(0xFF202124), Color(0xFF6B7280)))
    GridCardTone.Teal -> Brush.linearGradient(listOf(Color(0xFF171717), Color(0xFF355C5B)))
    GridCardTone.Rose -> Brush.linearGradient(listOf(Color(0xFF2E2524), Color(0xFF884C5E)))
    GridCardTone.Cocoa -> Brush.linearGradient(listOf(Color(0xFF1C1B1A), Color(0xFF6D5D4F)))
    GridCardTone.Moss -> Brush.linearGradient(listOf(Color(0xFF1F2421), Color(0xFF526E48)))
    GridCardTone.Stone -> Brush.linearGradient(listOf(Color(0xFF212121), Color(0xFF7B6257)))
    GridCardTone.Midnight -> Brush.linearGradient(listOf(Color(0xFF1A202C), Color(0xFF57606A)))
}

private fun LinkedPartnerTone?.badgeColor(): Color = when (this) {
    LinkedPartnerTone.Violet -> Color(0xFF8B5CF6)
    LinkedPartnerTone.Gold -> Color(0xFFC39A49)
    LinkedPartnerTone.Pink -> Color(0xFFE85D75)
    LinkedPartnerTone.Teal -> Color(0xFF4DB6AC)
    LinkedPartnerTone.Sun -> Color(0xFFFFC928)
    null -> Color(0xFFC39A49)
}
