package com.pynapli.app.grid

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pynapli.app.design.GridTileTokens
import com.pynapli.app.design.OnlineDot
import com.pynapli.app.design.PhotoBottomScrim
import com.pynapli.app.design.PynapliColors
import com.pynapli.app.design.PynapliMotion
import com.pynapli.app.design.PynapliShape
import com.pynapli.app.design.VerifiedBadge
import com.pynapli.app.design.handCursor
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.filled.DarkMode
import com.pynapli.app.design.icons.filled.Lock
import com.pynapli.app.design.icons.filled.Person
import com.pynapli.app.media.ImageLoadStats
import com.pynapli.app.media.rememberCachedImageModel
import com.pynapli.shared.distance.formatDistanceForUnit

// Pure black on purpose: a shadow is an occlusion effect, not a palette value, so it does not
// follow the warm neutrals the way on-photo text does.
private val OnPhotoCaptionShadow = Color.Black.copy(alpha = 0.55f) // token-guard:allow

// Subtle drop shadow so the compact captions stay legible directly on photos.
private val OnPhotoCaptionStyle =
    TextStyle(shadow = Shadow(color = OnPhotoCaptionShadow, offset = Offset(0f, 1f), blurRadius = 3f))

@Composable
internal fun GridProfileTile(
    card: GridProfileCardUi,
    distanceUnit: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val distanceLabel =
        formatDistanceForUnit(
            distanceMeters = card.distanceMeters,
            fallbackLabel = card.distanceLabel,
            distanceUnit = distanceUnit,
        )

    // Pointer hover (web/desktop): a quiet brighten so the cursor's target reads
    // without zooming or repainting the photo. Touch devices never emit hover.
    val hoverInteractions = remember { MutableInteractionSource() }
    val hovered by hoverInteractions.collectIsHoveredAsState()
    val hoverAlpha by animateFloatAsState(
        targetValue = if (hovered) 1f else 0f,
        animationSpec = PynapliMotion.standardTween(),
        label = "gridTileHover",
    )

    val locked = card.isLockedCard
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(PynapliShape.SmallShape)
                .hoverable(hoverInteractions)
                // A locked tile opens the plans, never a profile (Section 3).
                .handCursor()
                .clickable(onClickLabel = if (locked) "See plans" else "Open profile", role = Role.Button, onClick = onClick)
                // Group the tile so a screen reader announces it as one item (name + presence +
                // visiting + distance + partner/tonight badges) instead of several fragments. A
                // locked tile leads with a neutral line and has no name to read.
                .semantics(mergeDescendants = true) {
                    if (locked) contentDescription = LOCKED_TILE_DESCRIPTION
                }.then(if (locked) Modifier.testTag(GridCardTags.LOCKED_TILE) else Modifier)
                // One neutral surface for every tile (plan G11): no colour chosen by list position,
                // which made the same no-photo person change colour when the order changed.
                .background(PynapliColors.SurfaceRaised),
    ) {
        val thumbnailUrl = card.thumbnailUrl
        if (thumbnailUrl != null) {
            AsyncImage(
                // G2-2: cached by the server's stable key, so a re-signed URL is a cache hit.
                model = rememberCachedImageModel(thumbnailUrl, card.thumbnailCacheKey, stats = ImageLoadStats.grid),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            NoPhotoSilhouette(modifier = Modifier.align(Alignment.Center).testTag(GridCardTags.silhouetteTag(card.id)))
        }

        if (hoverAlpha > 0f) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = hoverAlpha }
                        .background(PynapliColors.OnPhotoHoverTint),
            )
        }

        if (locked) {
            // A light dim, not a blur: past the limit the faces are the upsell (Grindr-style cascade),
            // the dim and the lock just say "not yet".
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(PynapliColors.Black.copy(alpha = 0.22f)),
            )
        }

        PhotoBottomScrim(heightFraction = 0.48f)

        if (locked) {
            LockedTileBadge(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(7.dp),
            )
        } else if (card.linkedPartner != null) {
            LinkedPartnerBadge(
                thumbnailUrl = card.linkedPartner.thumbnailUrl,
                thumbnailCacheKey = card.linkedPartner.thumbnailCacheKey,
                coupleVerified = card.coupleVerified,
                color = card.linkedPartnerTone.badgeColor(),
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(7.dp),
            )
        }

        if (card.tonightActive || card.fresh) {
            Row(
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (card.tonightActive) {
                    // PLAYING_TONIGHT_SPEC: a subtle per-tile indicator that this individual
                    // has a live Playing Tonight session. Gold accent, never a couple card.
                    TonightIndicator(modifier = Modifier.testTag(GridCardTags.tonightTag(card.id)))
                }
                if (card.fresh) {
                    // Plan G4/G11: the server's Fresh rule (new here, or a new photo, and active).
                    NewMarker(modifier = Modifier.testTag(GridCardTags.newTag(card.id)))
                }
            }
        }

        val activeAgoLabel = if (card.online) null else GridActiveAgo.label(card.activeAgo)
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 5.dp),
        ) {
            if (activeAgoLabel != null) {
                // Recently here but not online now: a coarse bucket, never a minute-exact time.
                Text(
                    text = activeAgoLabel,
                    color = PynapliColors.OnPhotoSecondary,
                    fontSize = GridTileTokens.MetaTextSize,
                    style = OnPhotoCaptionStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(GridCardTags.activeAgoTag(card.id)),
                )
            }
            GridTileCaptionRow(card = card, distanceLabel = distanceLabel)
        }
    }
}

@Composable
private fun GridTileCaptionRow(
    card: GridProfileCardUi,
    distanceLabel: String?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (card.online) {
            OnlineDot(size = 7.dp, contentDescription = "Online")
        }

        if (card.isLockedCard) {
            // No name on a locked tile; the distance still sits at the end of the row.
            Spacer(modifier = Modifier.weight(1f))
        } else {
            Text(
                text = card.displayName,
                color = PynapliColors.TextPrimary,
                fontSize = GridTileTokens.NameTextSize,
                fontWeight = FontWeight.SemiBold,
                style = OnPhotoCaptionStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }

        if (card.verified) {
            VerifiedBadge(size = 11.dp, modifier = Modifier.testTag(GridCardTags.verifiedTag(card.id)))
        }

        if (card.visiting || card.visitingDates != null) {
            // SWIPE_IMPLEMENTATION_SPEC L348: target is here via passport,
            // not physically local. Same wording + tone as the Swipe pill.
            Text(
                text = card.visitingDates ?: "Visiting",
                color = PynapliColors.OnPhotoPrimary,
                fontSize = GridTileTokens.MetaTextSize,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier =
                    Modifier
                        .testTag(GridCardTags.visitingTag(card.id))
                        .clip(PynapliShape.PillShape)
                        .background(PynapliColors.WarmBrown.copy(alpha = 0.8f))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }

        distanceLabel?.let { distance ->
            Text(
                text = distance,
                color = PynapliColors.OnPhotoSecondary,
                fontSize = GridTileTokens.MetaTextSize,
                style = OnPhotoCaptionStyle,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

internal object GridCardTags {
    const val LOCKED_TILE: String = "grid-card-locked"
    const val LOCKED_BADGE: String = "grid-card-locked-badge"

    fun visitingTag(cardId: String): String = "grid-card-visiting-$cardId"

    fun tonightTag(cardId: String): String = "grid-card-tonight-$cardId"

    fun newTag(cardId: String): String = "grid-card-new-$cardId"

    fun activeAgoTag(cardId: String): String = "grid-card-active-ago-$cardId"

    fun silhouetteTag(cardId: String): String = "grid-card-silhouette-$cardId"

    fun verifiedTag(cardId: String): String = "grid-card-verified-$cardId"
}

/** No approved photo: a quiet person outline on the neutral tile, the same for everyone. */
@Composable
private fun NoPhotoSilhouette(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Filled.Person,
        contentDescription = "No photo",
        tint = PynapliColors.TextTertiary,
        modifier = modifier.fillMaxSize(0.46f),
    )
}

/** "New": the server's Fresh rule. A small gold tag, top-left beside the Tonight moon. */
@Composable
private fun NewMarker(modifier: Modifier = Modifier) {
    Text(
        text = "New",
        color = PynapliColors.TextInverse,
        fontSize = GridTileTokens.MetaTextSize,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier =
            modifier
                .clip(PynapliShape.PillShape)
                .background(PynapliColors.Accent)
                .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/**
 * The locked tile's mark (Section 3): a small gold lock on the dark on-photo disc, top-right where an
 * unlocked tile would show the partner mini. Gold because it sells a plan (DESIGN_SPEC accent rule).
 */
@Composable
private fun LockedTileBadge(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(PynapliColors.OnPhotoBadgeFill)
                .testTag(GridCardTags.LOCKED_BADGE),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            // The tile's own description already says it is locked.
            contentDescription = null,
            tint = PynapliColors.Accent,
            modifier = Modifier.size(11.dp),
        )
    }
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
    thumbnailCacheKey: String?,
    coupleVerified: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    // The couple badge rides on the partner mini: both partners verified (TRUST_AND_SAFETY_SPEC).
    Box(modifier = modifier) {
        PartnerMiniCircle(thumbnailUrl = thumbnailUrl, thumbnailCacheKey = thumbnailCacheKey, color = color)
        if (coupleVerified) {
            VerifiedBadge(
                size = 12.dp,
                contentDescription = "Verified couple",
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 3.dp, y = 3.dp)
                        .clip(CircleShape)
                        .background(PynapliColors.OnPhotoBadgeFill),
            )
        }
    }
}

@Composable
private fun PartnerMiniCircle(
    thumbnailUrl: String?,
    thumbnailCacheKey: String?,
    color: Color,
) {
    Box(
        modifier =
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(if (thumbnailUrl == null) color else PynapliColors.OnPhotoBadgeFill)
                .border(1.dp, PynapliColors.OnPhotoStroke, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (thumbnailUrl != null) {
            AsyncImage(
                model = rememberCachedImageModel(thumbnailUrl, thumbnailCacheKey, stats = ImageLoadStats.grid),
                contentDescription = "Linked partner",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = "Linked partner",
                tint = PynapliColors.OnPhotoPrimary,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

private fun LinkedPartnerTone?.badgeColor(): Color =
    when (this) {
        LinkedPartnerTone.Violet -> PynapliColors.PartnerViolet
        LinkedPartnerTone.Gold -> PynapliColors.PartnerGold
        LinkedPartnerTone.Pink -> PynapliColors.PartnerPink
        LinkedPartnerTone.Teal -> PynapliColors.PartnerTeal
        LinkedPartnerTone.Sun -> PynapliColors.Accent
        null -> PynapliColors.PartnerGold
    }
