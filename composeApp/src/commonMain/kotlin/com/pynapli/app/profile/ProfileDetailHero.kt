package com.pynapli.app.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pynapli.app.design.PynapliColors
import com.pynapli.app.design.PynapliPlaceholderGradients
import com.pynapli.app.design.PynapliSemantic
import com.pynapli.app.design.PynapliShape
import com.pynapli.app.design.VerifiedBadge
import com.pynapli.app.design.handCursor
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.filled.Block
import com.pynapli.app.design.icons.filled.Close
import com.pynapli.app.design.icons.filled.Flag
import com.pynapli.app.design.icons.filled.MoreVert
import com.pynapli.app.design.icons.filled.PersonAdd
import com.pynapli.app.design.icons.filled.Star
import com.pynapli.app.design.icons.filled.StarBorder
import com.pynapli.app.design.icons.filled.VisibilityOff
import com.pynapli.app.media.BEING_REVIEWED_LABEL
import com.pynapli.app.media.rememberCachedImageModel
import kotlinx.coroutines.launch

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
    onInviteProfileLink: (() -> Unit)? = null,
    coupleVerified: Boolean = false,
    // Coarse server bucket ("Active today"); shown only while the person is not online.
    lastActiveLabel: String? = null,
    favorite: Boolean = false,
    // Null hides the star (own profile, or a surface that doesn't offer Favorites).
    onToggleFavorite: (() -> Unit)? = null,
    // Photos change by tapping the left or right half instead of swiping, so a horizontal swipe is
    // free for the Grid's profile pager (GRID_IMPLEMENTATION_SPEC "Profile open").
    photosByTap: Boolean = false,
    // Own preview only: photos the owner can see that others can't yet (moderation pending).
    beingReviewedPhotoUrls: Set<String> = emptySet(),
    // G2-2: the server's stable cache keys for [photoUrls] (index for index) and [profilePhotoUrl];
    // keys only name bytes, the URLs still guard access. Empty/null = cache by URL as before.
    photoCacheKeys: List<String> = emptyList(),
    profilePhotoCacheKey: String? = null,
    // The key the first photo's small Grid version may already be cached under: painted while the
    // original loads, so opening a profile from the Grid shows the face at once.
    firstPhotoPlaceholderCacheKey: String? = null,
) {
    val lastSeen = lastActiveLabel?.takeUnless { online }
    val photos =
        remember(photoUrls, profilePhotoUrl) {
            photoUrls.ifEmpty { listOfNotNull(profilePhotoUrl) }
        }
    val photoKeys =
        remember(photoUrls, photoCacheKeys, profilePhotoCacheKey) {
            when {
                photoUrls.isEmpty() -> listOf(profilePhotoCacheKey)
                photoCacheKeys.size == photoUrls.size -> photoCacheKeys
                else -> photoUrls.map { null }
            }
        }
    val pagerState = rememberPagerState(pageCount = { photos.size.coerceAtLeast(1) })
    val photoScope = rememberCoroutineScope()

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
                userScrollEnabled = !photosByTap,
            ) { page ->
                AsyncImage(
                    model =
                        rememberCachedImageModel(
                            url = photos[page],
                            cacheKey = photoKeys.getOrNull(page),
                            placeholderCacheKey = firstPhotoPlaceholderCacheKey?.takeIf { page == 0 && it != photoKeys.getOrNull(0) },
                        ),
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

        if (photosByTap && photos.size > 1) {
            // Under the top-row buttons in z-order, so the star, menu and close keep their taps.
            Row(modifier = Modifier.matchParentSize().testTag(ProfileDetailTags.PHOTO_TAP_ZONES)) {
                PhotoTapZone(
                    label = "Previous photo",
                    enabled = pagerState.currentPage > 0,
                    onTap = { photoScope.launch { pagerState.scrollToPage(pagerState.currentPage - 1) } },
                )
                PhotoTapZone(
                    label = "Next photo",
                    enabled = pagerState.currentPage < photos.lastIndex,
                    onTap = { photoScope.launch { pagerState.scrollToPage(pagerState.currentPage + 1) } },
                )
            }
        }

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

        if (photos.getOrNull(pagerState.currentPage)?.let { it in beingReviewedPhotoUrls } == true) {
            Text(
                text = BEING_REVIEWED_LABEL,
                color = PynapliColors.OnPhotoPrimary,
                style = MaterialTheme.typography.labelMedium,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 12.dp, top = 22.dp)
                        .clip(PynapliShape.PillShape)
                        .background(PynapliColors.OnPhotoBadgeFill)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .testTag(ProfileDetailTags.BEING_REVIEWED_BADGE),
            )
        }

        Row(
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!isOwnProfile && onToggleFavorite != null) {
                FavoriteStarButton(favorite = favorite, onToggle = onToggleFavorite)
            }
            if (!isOwnProfile) {
                ProfileOverflowMenu(
                    onHideClick = onHideClick,
                    onBlockClick = onBlockClick,
                    onReportClick = onReportClick,
                    onInviteProfileLink = onInviteProfileLink,
                )
            }

            IconButton(
                onClick = onDismiss,
                modifier =
                    Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(PynapliColors.OverlayScrim)
                        .handCursor(),
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

            if (coupleVerified) {
                // Both linked partners are photo-verified (TRUST_AND_SAFETY_SPEC).
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.semantics(mergeDescendants = true) {},
                ) {
                    VerifiedBadge(size = 14.dp, contentDescription = null)
                    Text(
                        text = "Verified couple",
                        color = PynapliColors.OnPhotoPrimary,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (online || lastSeen != null || distanceLabel != null || neighborhood != null) {
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
                    lastSeen?.let { label ->
                        Text(
                            text = label,
                            color = PynapliColors.OnPhotoSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            modifier = Modifier.testTag(ProfileDetailTags.LAST_SEEN),
                        )
                    }
                    var precededBySegment = online || lastSeen != null
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

/** Half of the hero photo: a tap moves one photo back or forward. */
@Composable
private fun RowScope.PhotoTapZone(
    label: String,
    enabled: Boolean,
    onTap: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    Box(
        modifier =
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .handCursor(enabled = enabled)
                .clickable(
                    interactionSource = interactions,
                    indication = null,
                    enabled = enabled,
                    onClickLabel = label,
                    role = Role.Button,
                    onClick = onTap,
                ).semantics { contentDescription = label },
    )
}

internal object ProfileDetailTags {
    const val FAVORITE = "profile-detail-favorite"
    const val PHOTO_TAP_ZONES = "profile-detail-photo-tap-zones"
    const val LAST_SEEN = "profile-detail-last-seen"
    const val LIKE_BACK = "profile-detail-like-back"
    const val LIFESTYLE = "profile-detail-lifestyle"
    const val PARTNER_SUMMARY = "profile-detail-partner-summary"
    const val BEING_REVIEWED_BADGE = "profile-detail-being-reviewed"
}

/** Private Favorites star (GRID_IMPLEMENTATION_SPEC "Favorites"): the other person is never told. */
@Composable
private fun FavoriteStarButton(
    favorite: Boolean,
    onToggle: () -> Unit,
) {
    IconButton(
        onClick = onToggle,
        modifier =
            Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(PynapliColors.OverlayScrim)
                .handCursor()
                .testTag(ProfileDetailTags.FAVORITE),
    ) {
        Icon(
            imageVector = if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
            contentDescription = if (favorite) "Remove from Favorites" else "Add to Favorites",
            tint = if (favorite) PynapliColors.Accent else PynapliColors.OnPhotoPrimary,
        )
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
    onInviteProfileLink: (() -> Unit)?,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier =
                Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(PynapliColors.OverlayScrim)
                    .handCursor(),
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
            onInviteProfileLink?.let { invite ->
                DropdownMenuItem(
                    text = { Text("Invite to link as partners") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.PersonAdd,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = {
                        expanded = false
                        invite()
                    },
                    modifier = Modifier.handCursor(),
                )
            }
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
                modifier = Modifier.handCursor(),
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
                modifier = Modifier.handCursor(),
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
                modifier = Modifier.handCursor(),
            )
        }
    }
}
