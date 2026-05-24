package com.pynapli.app.swipe

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pynapli.app.grid.GridCardTone
import com.pynapli.app.grid.GridProfileCardUi
import com.pynapli.app.grid.LinkedPartnerTone
import com.pynapli.app.grid.toUi
import com.pynapli.shared.distance.formatDistanceForUnit
import com.pynapli.shared.network.dto.SwipeAction
import com.pynapli.shared.network.dto.SwipeCardDto
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

@Composable
internal fun SwipeCardStack(
    cards: List<SwipeCardDto>,
    enabled: Boolean,
    requestedAction: String?,
    distanceUnit: String,
    onActionConsumed: () -> Unit,
    onOpenProfile: (SwipeCardDto) -> Unit,
    onLinkedPartnerTap: (SwipeCardDto) -> Unit,
    onSwiped: (SwipeCardDto, String) -> Unit
) {
    val visibleStack = cards.take(3)
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            visibleStack.asReversed().forEachIndexed { reverseIndex, card ->
                val depth = visibleStack.lastIndex - reverseIndex
                SwipeCard(
                    card = card,
                    depth = depth,
                    isTop = depth == 0,
                    enabled = enabled,
                    widthPx = widthPx,
                    requestedAction = if (depth == 0) requestedAction else null,
                    distanceUnit = distanceUnit,
                    onActionConsumed = onActionConsumed,
                    onOpenProfile = onOpenProfile,
                    onLinkedPartnerTap = onLinkedPartnerTap,
                    onSwiped = onSwiped
                )
            }
        }
    }
}

@Composable
private fun SwipeCard(
    card: SwipeCardDto,
    depth: Int,
    isTop: Boolean,
    enabled: Boolean,
    widthPx: Float,
    requestedAction: String?,
    distanceUnit: String,
    onActionConsumed: () -> Unit,
    onOpenProfile: (SwipeCardDto) -> Unit,
    onLinkedPartnerTap: (SwipeCardDto) -> Unit,
    onSwiped: (SwipeCardDto, String) -> Unit
) {
    val verticalOffset = (depth * 8).dp
    val horizontalInset = (depth * 6).dp
    val tone = card.userId.toBackgroundBrush()
    val scope = rememberCoroutineScope()
    val offset = remember(card.userId) { Animatable(Offset.Zero, Offset.VectorConverter) }
    val threshold = widthPx * 0.26f
    var isProgrammaticFlyOut by remember(card.userId) { mutableStateOf(false) }

    suspend fun settleBack() {
        offset.animateTo(Offset.Zero, tween(durationMillis = 180))
    }

    suspend fun flyOut(action: String) {
        val direction = if (action == SwipeAction.PASS) -1f else 1f
        offset.animateTo(
            targetValue = Offset(
                x = direction * widthPx * 1.25f,
                y = offset.value.y
            ),
            animationSpec = tween(durationMillis = 210)
        )
        onSwiped(card, action)
    }

    LaunchedEffect(requestedAction, card.userId) {
        val action = requestedAction ?: return@LaunchedEffect
        if (isProgrammaticFlyOut) {
            onActionConsumed()
            return@LaunchedEffect
        }
        isProgrammaticFlyOut = true
        scope.launch {
            try {
                flyOut(action)
            } finally {
                isProgrammaticFlyOut = false
            }
        }
        onActionConsumed()
    }

    val dragModifier = if (isTop && enabled && !isProgrammaticFlyOut) {
        Modifier.pointerInput(card.userId, widthPx) {
            detectDragGestures(
                onDrag = { change, dragAmount ->
                    change.consume()
                    scope.launch {
                        offset.snapTo(
                            offset.value + Offset(
                                x = dragAmount.x,
                                y = dragAmount.y * 0.28f
                            )
                        )
                    }
                },
                onDragEnd = {
                    scope.launch {
                        val action = when {
                            offset.value.x > threshold -> SwipeAction.LIKE
                            offset.value.x < -threshold -> SwipeAction.PASS
                            else -> null
                        }
                        if (action == null) {
                            settleBack()
                        } else {
                            flyOut(action)
                        }
                    }
                },
                onDragCancel = {
                    scope.launch { settleBack() }
                }
            )
        }
    } else {
        Modifier
    }

    val decisionColor = if (offset.value.x >= 0f) Color(0xFF1FBF7B) else Color(0xFFE85A4A)
    val decisionLabel = if (offset.value.x >= 0f) "Like" else "Pass"
    val decisionAlpha = (offset.value.x.absoluteValue / threshold).coerceIn(0f, 1f)
    val distanceLabel = formatDistanceForUnit(
        distanceMeters = card.distanceMeters,
        fallbackLabel = card.distanceLabel,
        distanceUnit = distanceUnit
    )
    val resolvedPhotos = remember(card.userId, card.photoUrls, card.photoUrl) {
        card.photoUrls.ifEmpty { listOfNotNull(card.photoUrl) }
    }
    val pagerState = rememberPagerState(initialPage = 0) { resolvedPhotos.size.coerceAtLeast(1) }
    val tapZoneModifier = if (isTop && enabled) {
        Modifier.pointerInput(card.userId, resolvedPhotos.size) {
            detectTapGestures(
                onTap = { tapOffset ->
                    val width = size.width.toFloat()
                    val zone = if (width > 0f) tapOffset.x / width else 0.5f
                    when {
                        resolvedPhotos.size > 1 && zone < 0.30f && pagerState.currentPage > 0 ->
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                        resolvedPhotos.size > 1 && zone > 0.70f && pagerState.currentPage < resolvedPhotos.lastIndex ->
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        else -> onOpenProfile(card)
                    }
                }
            )
        }
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = horizontalInset,
                end = horizontalInset,
                top = verticalOffset
            )
            .graphicsLayer {
                if (isTop) {
                    translationX = offset.value.x
                    translationY = offset.value.y
                    rotationZ = (offset.value.x / 42f).coerceIn(-10f, 10f)
                }
            }
            .testTag(if (isTop) SwipeTestTags.TOP_CARD else "${SwipeTestTags.CARD_PREFIX}${card.userId}")
            .then(dragModifier)
            .then(tapZoneModifier)
            .clip(RoundedCornerShape(8.dp))
            .background(tone)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
    ) {
        if (isTop && resolvedPhotos.size > 1) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = false,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(SwipeTestTags.PHOTO_CAROUSEL)
            ) { page ->
                AsyncImage(
                    model = resolvedPhotos[page],
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            resolvedPhotos.firstOrNull()?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        if (isTop && resolvedPhotos.size > 1) {
            PhotoCarouselIndicators(
                count = resolvedPhotos.size,
                currentPage = pagerState.currentPage,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp, start = 18.dp, end = 18.dp)
            )
        }
        val linkedPartner = card.linkedPartner
        if (linkedPartner != null && decisionAlpha < 0.12f) {
            SwipeLinkedPartnerBadge(
                thumbnailUrl = linkedPartner.thumbnailUrl,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(18.dp),
                enabled = isTop && enabled,
                onClick = if (isTop) {
                    { onLinkedPartnerTap(card) }
                } else {
                    null
                }
            )
        }
        if (isTop && decisionAlpha > 0f) {
            Text(
                text = decisionLabel,
                color = decisionColor,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(if (offset.value.x >= 0f) Alignment.TopStart else Alignment.TopEnd)
                    .padding(22.dp)
                    .graphicsLayer { alpha = decisionAlpha }
                    .border(2.dp, decisionColor, RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xE6000000))
                    )
                )
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (card.online) {
                    SwipeOnlineDot()
                }
                Text(
                    text = card.displayName,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (card.verified) {
                    SwipeVerifiedBadge()
                }
                if (card.visiting) {
                    SwipeVisitingPill()
                }
                card.displayAge?.let { age ->
                    Text(
                        text = "$age",
                        color = Color(0xFFEDEDED),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
            distanceLabel?.let { distance ->
                Text(
                    text = distance,
                    color = Color(0xFFCFCFCF),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            card.shortBio?.takeIf { it.isNotBlank() }?.let { bio ->
                Text(
                    text = bio,
                    color = Color(0xFFEDEDED),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SwipeTestTags.SHORT_BIO)
                )
            }
            if (card.tags.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SwipeTestTags.TAGS_ROW),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    card.tags.forEach { tag ->
                        SwipeTagChip(label = tag)
                    }
                }
            }
        }
    }
}

@Composable
private fun SwipeTagChip(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0x33FFFFFF))
            .border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PhotoCarouselIndicators(
    count: Int,
    currentPage: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(SwipeTestTags.PHOTO_CAROUSEL_INDICATORS),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        repeat(count) { index ->
            val active = index == currentPage
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (active) Color(0xCCFFFFFF) else Color(0x40FFFFFF))
            )
        }
    }
}

@Composable
internal fun SwipeOnlineDot(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .testTag(SwipeTestTags.ONLINE_DOT)
            .size(10.dp)
            .clip(CircleShape)
            .background(Color(0xFF13E58A))
            .border(1.dp, Color(0xFF050505), CircleShape)
    )
}

@Composable
private fun SwipeVerifiedBadge(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Filled.CheckCircle,
        contentDescription = "Verified",
        tint = Color(0xFF52A8FF),
        modifier = modifier
            .testTag(SwipeTestTags.VERIFIED_BADGE)
            .size(18.dp)
    )
}

@Composable
private fun SwipeVisitingPill(modifier: Modifier = Modifier) {
    // SWIPE_IMPLEMENTATION_SPEC L348: marks targets who are in the viewer's
    // area via travel mode, not physically local. Subtle pill rather than a
    // hard badge so it reads as context, not a tier signal.
    Text(
        text = "Visiting",
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .testTag(SwipeTestTags.VISITING_PILL)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xCC4E342E))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun SwipeLinkedPartnerBadge(
    thumbnailUrl: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    val baseModifier = modifier
        .testTag(SwipeTestTags.LINKED_PARTNER_BADGE)
        .size(34.dp)
        .clip(CircleShape)
    val tappableModifier = if (onClick != null && enabled) {
        baseModifier.clickable(onClick = onClick)
    } else {
        baseModifier
    }
    Box(
        modifier = tappableModifier
            .background(Color(0xCC1B1B1B))
            .border(1.dp, Color(0xCCFFFFFF), CircleShape),
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
                modifier = Modifier.size(19.dp)
            )
        }
    }
}

private fun String.toBackgroundBrush(): Brush {
    val tones = listOf(
        Brush.linearGradient(listOf(Color(0xFF2A2622), Color(0xFFB08A44))),
        Brush.linearGradient(listOf(Color(0xFF17211E), Color(0xFF486C5D))),
        Brush.linearGradient(listOf(Color(0xFF251D22), Color(0xFF854D62))),
        Brush.linearGradient(listOf(Color(0xFF191F24), Color(0xFF53616D))),
        Brush.linearGradient(listOf(Color(0xFF20201E), Color(0xFF72634F)))
    )
    val index = stableIndex(tones.size)
    return tones[index]
}

internal fun SwipeCardDto.toProfileCardUi(): GridProfileCardUi {
    val toneIndex = userId.stableIndex(GridCardTone.entries.size)
    val linkedToneIndex = userId.stableIndex(LinkedPartnerTone.entries.size)
    return GridProfileCardUi(
        id = userId,
        displayName = displayName,
        thumbnailUrl = photoUrl,
        online = online,
        distanceLabel = distanceLabel,
        tone = GridCardTone.entries[toneIndex],
        linkedPartner = linkedPartner?.toUi(),
        linkedPartnerTone = linkedPartner?.let { LinkedPartnerTone.entries[linkedToneIndex] },
        distanceMeters = distanceMeters
    )
}

private fun String.stableIndex(size: Int): Int =
    fold(0) { total, char -> total + char.code }.mod(size)
