package com.pynapli.app.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.pynapli.app.design.PynapliColors
import com.pynapli.app.design.PynapliPlaceholderGradients
import com.pynapli.app.design.PynapliSemantic
import com.pynapli.app.design.PynapliShape
import com.pynapli.app.design.VerifiedBadge

@Composable
internal fun ProfileHero(
    displayName: String,
    age: Int?,
    verified: Boolean,
    online: Boolean,
    distanceLabel: String?,
    neighborhood: String?,
    photoUrls: List<String>,
    profilePhotoUrl: String?,
    onDismiss: () -> Unit,
    onHideClick: () -> Unit,
    onBlockClick: () -> Unit,
    onReportClick: () -> Unit,
    isOwnProfile: Boolean = false,
) {
    val photos =
        remember(photoUrls, profilePhotoUrl) {
            photoUrls.ifEmpty { listOfNotNull(profilePhotoUrl) }
        }
    val pagerState = rememberPagerState(pageCount = { photos.size.coerceAtLeast(1) })

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(380.dp)
                .clip(PynapliShape.CardShape)
                .background(PynapliPlaceholderGradients.profileHero),
    ) {
        if (photos.isNotEmpty()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                AsyncImage(
                    model = photos[page],
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Bottom scrim so the name/meta stay legible over any photo.
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, PynapliColors.OverlayScrimSoft, PynapliColors.PhotoGradientBottom),
                        ),
                    ),
        )

        // Carousel pips (only when there is more than one photo).
        if (photos.size > 1) {
            Row(
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                photos.indices.forEach { index ->
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .height(3.dp)
                                .clip(PynapliShape.PillShape)
                                .background(
                                    if (index == pagerState.currentPage) PynapliColors.OnPhotoStroke else PynapliColors.OnPhotoIndicatorInactive,
                                ),
                    )
                }
            }
        }

        Row(
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!isOwnProfile) {
                ProfileOverflowMenu(
                    onHideClick = onHideClick,
                    onBlockClick = onBlockClick,
                    onReportClick = onReportClick,
                )
            }

            IconButton(
                onClick = onDismiss,
                modifier =
                    Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(PynapliColors.OverlayScrim),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close profile",
                    tint = PynapliColors.OnPhotoPrimary,
                )
            }
        }

        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = displayName,
                    color = PynapliColors.OnPhotoPrimary,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                age?.let { value ->
                    Text(
                        text = value.toString(),
                        color = PynapliColors.OnPhotoSecondary,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Light,
                    )
                }
                if (verified) {
                    VerifiedBadge(size = 18.dp)
                }
            }

            if (online || distanceLabel != null || neighborhood != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    if (online) {
                        Box(
                            modifier =
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(PynapliSemantic.Online),
                        )
                        Text(
                            text = "Online",
                            color = PynapliSemantic.Online,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    var precededBySegment = online
                    distanceLabel?.let {
                        if (precededBySegment) {
                            MetaDivider()
                        }
                        Text(
                            text = it,
                            color = PynapliColors.OnPhotoPrimary,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                        )
                        precededBySegment = true
                    }
                    // User-declared neighborhood ("distance · hood · Online"). Self-described,
                    // never derived from GPS, so it doesn't narrow the person's exact location.
                    neighborhood?.let { hood ->
                        if (precededBySegment) {
                            MetaDivider()
                        }
                        Text(
                            text = hood,
                            color = PynapliColors.OnPhotoPrimary,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
            }
        }
    }
}

/** Mid-dot "·" separator between profile meta segments (online · distance · hood). */
@Composable
private fun MetaDivider() {
    Text(
        text = "·",
        color = PynapliColors.OnPhotoPrimary,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun ProfileOverflowMenu(
    onHideClick: () -> Unit,
    onBlockClick: () -> Unit,
    onReportClick: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier =
                Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(PynapliColors.OverlayScrim),
        ) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "More options",
                tint = PynapliColors.OnPhotoPrimary,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("Hide") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.VisibilityOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                },
                onClick = {
                    expanded = false
                    onHideClick()
                },
            )
            DropdownMenuItem(
                text = { Text("Block") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Block,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    expanded = false
                    onBlockClick()
                },
            )
            DropdownMenuItem(
                text = { Text("Report") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Flag,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                },
                onClick = {
                    expanded = false
                    onReportClick()
                },
            )
        }
    }
}
