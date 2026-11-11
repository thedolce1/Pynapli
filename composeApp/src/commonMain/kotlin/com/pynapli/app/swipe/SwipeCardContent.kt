package com.pynapli.app.swipe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.pynapli.app.grid.discoveryTagLabel
import com.pynapli.shared.network.dto.SwipeCardDto

@Composable
internal fun BoxScope.SwipeCardPhotos(
    isTop: Boolean,
    resolvedPhotos: List<String>,
    pagerState: PagerState,
    displayName: String,
) {
    if (isTop && resolvedPhotos.size > 1) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = false,
            modifier =
                Modifier
                    .fillMaxSize()
                    .testTag(SwipeTestTags.PHOTO_CAROUSEL),
        ) { page ->
            AsyncImage(
                model = resolvedPhotos[page],
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    } else {
        val url = resolvedPhotos.firstOrNull()
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
            currentPage = pagerState.currentPage,
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp, start = 18.dp, end = 18.dp),
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
) {
    Column(
        modifier =
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, PynapliColors.PhotoScrimStrong),
                    ),
                ).padding(20.dp)
                // Read the card's identity (name + presence + verified + visiting + age + distance +
                // bio + tags) as one item; the action bar buttons are the accessible actions.
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
            if (card.verified) {
                SwipeVerifiedBadge()
            }
            if (card.visiting) {
                SwipeVisitingPill()
            }
            card.displayAge?.let { age ->
                // De-emphasized next to the name (handoff: lighter, dimmer age beside the bold name).
                Text(
                    text = "$age",
                    color = PynapliColors.OnPhotoSecondary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Normal,
                )
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
        if (card.tags.isNotEmpty()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(SwipeTestTags.TAGS_ROW),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                card.tags.forEach { tag ->
                    // The server sends taxonomy keys; show the label people read elsewhere.
                    SwipeTagChip(label = discoveryTagLabel(tag))
                }
            }
        }
    }
}
