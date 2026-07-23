package com.pynapli.app.swipe

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pynapli.app.design.PynapliColors
import com.pynapli.app.design.PynapliShape

@Composable
internal fun SwipeTagChip(label: String) {
    Box(
        modifier =
            Modifier
                .clip(PynapliShape.PillShape)
                .background(PynapliColors.OnPhotoChipFill)
                .border(1.dp, PynapliColors.OnPhotoChipStroke, PynapliShape.PillShape)
                .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            color = PynapliColors.OnPhotoPrimary,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun PhotoCarouselIndicators(
    count: Int,
    currentPage: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(SwipeTestTags.PHOTO_CAROUSEL_INDICATORS),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(count) { index ->
            val active = index == currentPage
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .height(3.dp)
                        .clip(PynapliShape.PillShape)
                        .background(if (active) PynapliColors.OnPhotoStroke else PynapliColors.OnPhotoIndicatorInactive),
            )
        }
    }
}

@Composable
internal fun SwipeOnlineDot(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .testTag(SwipeTestTags.ONLINE_DOT)
                .size(10.dp)
                .clip(CircleShape)
                .background(PynapliColors.Success)
                .border(1.dp, PynapliColors.Black, CircleShape)
                .semantics { contentDescription = "Online" },
    )
}

@Composable
internal fun SwipeVerifiedBadge(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Filled.CheckCircle,
        contentDescription = "Verified",
        tint = PynapliColors.Info,
        modifier =
            modifier
                .testTag(SwipeTestTags.VERIFIED_BADGE)
                .size(18.dp),
    )
}

@Composable
internal fun SwipeVisitingPill(modifier: Modifier = Modifier) {
    // SWIPE_IMPLEMENTATION_SPEC L348: marks targets who are in the viewer's
    // area via travel mode, not physically local. Subtle pill rather than a
    // hard badge so it reads as context, not a tier signal.
    Text(
        text = "Visiting",
        color = PynapliColors.OnPhotoPrimary,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        modifier =
            modifier
                .testTag(SwipeTestTags.VISITING_PILL)
                .clip(PynapliShape.PillShape)
                .background(PynapliColors.WarmBrown.copy(alpha = 0.8f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
internal fun SwipeLinkedPartnerBadge(
    thumbnailUrl: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val baseModifier =
        modifier
            .testTag(SwipeTestTags.LINKED_PARTNER_BADGE)
            .size(34.dp)
            .clip(CircleShape)
    val tappableModifier =
        if (onClick != null && enabled) {
            baseModifier.clickable(onClick = onClick)
        } else {
            baseModifier
        }
    Box(
        modifier =
            tappableModifier
                .background(PynapliColors.OnPhotoBadgeFill)
                .border(1.dp, PynapliColors.OnPhotoStroke, CircleShape),
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
                tint = PynapliColors.OnPhotoPrimary,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}
