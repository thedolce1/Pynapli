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
import androidx.compose.material.icons.filled.DarkMode
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
import com.pynapli.app.design.OnlineDot
import com.pynapli.app.design.PhotoBottomScrim
import com.pynapli.app.design.PynapliColors
import com.pynapli.app.design.PynapliPlaceholderGradients
import com.pynapli.shared.distance.formatDistanceForUnit

@Composable
internal fun GridProfileTile(
    card: GridProfileCardUi,
    distanceUnit: String,
    onClick: () -> Unit,
) {
    val distanceLabel =
        formatDistanceForUnit(
            distanceMeters = card.distanceMeters,
            fallbackLabel = card.distanceLabel,
            distanceUnit = distanceUnit,
        )

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clickable(onClick = onClick)
                .background(card.tone.backgroundBrush()),
    ) {
        card.thumbnailUrl?.let { thumbnailUrl ->
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        PhotoBottomScrim(heightFraction = 0.48f)

        if (card.linkedPartner != null) {
            LinkedPartnerBadge(
                thumbnailUrl = card.linkedPartner.thumbnailUrl,
                color = card.linkedPartnerTone.badgeColor(),
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(7.dp),
            )
        }

        if (card.tonightActive) {
            // PLAYING_TONIGHT_SPEC: a subtle per-tile indicator that this individual
            // has a live Playing Tonight session. Gold accent, never a couple card.
            TonightIndicator(
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(7.dp)
                        .testTag(GridCardTags.tonightTag(card.id)),
            )
        }

        Row(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (card.online) {
                OnlineDot(size = 8.dp)
            }

            Text(
                text = card.displayName,
                color = PynapliColors.TextPrimary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
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
                    modifier =
                        Modifier
                            .testTag(GridCardTags.visitingTag(card.id))
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xCC4E342E))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }

            distanceLabel?.let { distance ->
                Text(
                    text = distance,
                    color = PynapliColors.TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

internal object GridCardTags {
    fun visitingTag(cardId: String): String = "grid-card-visiting-$cardId"

    fun tonightTag(cardId: String): String = "grid-card-tonight-$cardId"
}

@Composable
private fun TonightIndicator(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(PynapliColors.Black.copy(alpha = 0.8f))
                .border(1.dp, PynapliColors.Accent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.DarkMode,
            contentDescription = "Playing tonight",
            tint = PynapliColors.Accent,
            modifier = Modifier.size(10.dp),
        )
    }
}

@Composable
private fun LinkedPartnerBadge(
    thumbnailUrl: String?,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(if (thumbnailUrl == null) color else Color(0xCC111111))
                .border(1.dp, Color(0xD9FFFFFF), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (thumbnailUrl != null) {
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = "Linked partner",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = "Linked partner",
                tint = Color.White,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

// Order mirrors GridCardTone (and PynapliPlaceholderGradients.cardTones) by ordinal.
private fun GridCardTone.backgroundBrush(): Brush = PynapliPlaceholderGradients.cardTones[ordinal]

private fun LinkedPartnerTone?.badgeColor(): Color =
    when (this) {
        LinkedPartnerTone.Violet -> Color(0xFF8B5CF6)
        LinkedPartnerTone.Gold -> Color(0xFFC39A49)
        LinkedPartnerTone.Pink -> Color(0xFFE85D75)
        LinkedPartnerTone.Teal -> Color(0xFF4DB6AC)
        LinkedPartnerTone.Sun -> PynapliColors.Accent
        null -> Color(0xFFC39A49)
    }
