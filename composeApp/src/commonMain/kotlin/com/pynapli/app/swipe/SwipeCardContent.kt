package com.pynapli.app.swipe

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pynapli.app.design.PynapliColors
import com.pynapli.app.design.PynapliLayout
import com.pynapli.app.design.handCursor
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.filled.ArrowUpward
import com.pynapli.shared.network.dto.SwipeCardDto

@Composable
internal fun BoxScope.SwipeCardPhotos(
    isTop: Boolean,
    resolvedPhotos: List<String>,
    photoIndex: Int,
    displayName: String,
) {
    // One image slot whose source changes on tap: photos switch instantly, as on Tinder, and the
    // slot is the same on every card so a card keeps its loaded image when it is promoted to the top.
    val url = resolvedPhotos.getOrNull(photoIndex.coerceIn(0, (resolvedPhotos.size - 1).coerceAtLeast(0)))
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .then(if (resolvedPhotos.size > 1) Modifier.testTag(SwipeTestTags.PHOTO_CAROUSEL) else Modifier),
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Photo-less profile: a centered initial over the card gradient reads as an
            // intentional avatar rather than a blank wash.
            SwipeCardPhotoFallback(displayName)
        }
    }
    if (isTop && resolvedPhotos.size > 1) {
        PhotoCarouselIndicators(
            count = resolvedPhotos.size,
            currentPage = photoIndex,
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp, start = 12.dp, end = 12.dp),
        )
    }
}

@Composable
private fun SwipeCardPhotoFallback(displayName: String) {
    val initials =
        displayName
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .ifBlank { "?" }
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            color = PynapliColors.OnPhotoPrimary.copy(alpha = 0.55f),
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun BoxScope.SwipeCardFooter(
    card: SwipeCardDto,
    distanceLabel: String?,
    onOpenProfile: (() -> Unit)? = null,
) {
    Row(
        modifier =
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, PynapliColors.PhotoScrimStrong),
                    ),
                ).padding(start = 20.dp, end = 12.dp, top = 28.dp, bottom = 20.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    // Read the card's identity (name + presence + verified + visiting + age + distance +
                    // bio) as one item; the action bar buttons are the accessible actions. Tags live in
                    // the expanded profile only.
                    .semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (card.online) {
                    SwipeOnlineDot()
                }
                Text(
                    text = card.displayName,
                    color = PynapliColors.OnPhotoPrimary,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                card.displayAge?.let { age ->
                    // De-emphasized next to the name (handoff: lighter, dimmer age beside the bold name).
                    Text(
                        text = "$age",
                        color = PynapliColors.OnPhotoSecondary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Normal,
                    )
                }
                if (card.verified) {
                    SwipeVerifiedBadge()
                }
                if (card.visiting) {
                    SwipeVisitingPill()
                }
            }
            distanceLabel?.let { distance ->
                Text(
                    text = distance,
                    color = PynapliColors.OnPhotoSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            card.shortBio?.takeIf { it.isNotBlank() }?.let { bio ->
                Text(
                    text = bio,
                    color = PynapliColors.OnPhotoPrimary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag(SwipeTestTags.SHORT_BIO),
                )
            }
        }
        if (onOpenProfile != null) {
            SwipeOpenProfileButton(onClick = onOpenProfile)
        }
    }
}

/** The expand affordance: opens the full profile (tags, prompts, albums) in the shared sheet. */
@Composable
private fun SwipeOpenProfileButton(onClick: () -> Unit) {
    Box(
        modifier =
            Modifier
                .size(PynapliLayout.MinTouchTarget)
                .handCursor()
                .clickable(onClickLabel = "Open profile", onClick = onClick)
                .testTag(SwipeTestTags.OPEN_PROFILE),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(PynapliColors.OnPhotoBadgeFill)
                    .border(1.dp, PynapliColors.OnPhotoChipStroke, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.ArrowUpward,
                contentDescription = "Open profile",
                tint = PynapliColors.OnPhotoPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}