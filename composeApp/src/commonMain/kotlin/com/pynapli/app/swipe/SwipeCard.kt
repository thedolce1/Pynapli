package com.pynapli.app.swipe

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pynapli.app.design.PynapliColors
import com.pynapli.app.design.SwipeCardTokens
import com.pynapli.shared.distance.formatDistanceForUnit
import com.pynapli.shared.network.dto.SwipeAction
import com.pynapli.shared.network.dto.SwipeCardDto
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

@Composable
internal fun SwipeCard(
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
    onSwiped: (SwipeCardDto, String) -> Unit,
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
            targetValue =
                Offset(
                    x = direction * widthPx * 1.25f,
                    y = offset.value.y,
                ),
            animationSpec = tween(durationMillis = 210),
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

    val dragModifier =
        if (isTop && enabled && !isProgrammaticFlyOut) {
            Modifier.pointerInput(card.userId, widthPx) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        scope.launch {
                            offset.snapTo(
                                offset.value +
                                    Offset(
                                        x = dragAmount.x,
                                        y = dragAmount.y * 0.28f,
                                    ),
                            )
                        }
                    },
                    onDragEnd = {
                        scope.launch {
                            val action =
                                when {
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
                    },
                )
            }
        } else {
            Modifier
        }

    val decisionColor = if (offset.value.x >= 0f) PynapliColors.Success else PynapliColors.Danger
    val decisionLabel = if (offset.value.x >= 0f) "Like" else "Pass"
    val decisionAlpha = (offset.value.x.absoluteValue / threshold).coerceIn(0f, 1f)
    val distanceLabel =
        formatDistanceForUnit(
            distanceMeters = card.distanceMeters,
            fallbackLabel = card.distanceLabel,
            distanceUnit = distanceUnit,
        )
    val resolvedPhotos =
        remember(card.userId, card.photoUrls, card.photoUrl) {
            card.photoUrls.ifEmpty { listOfNotNull(card.photoUrl) }
        }
    val pagerState = rememberPagerState(initialPage = 0) { resolvedPhotos.size.coerceAtLeast(1) }
    val tapZoneModifier =
        if (isTop && enabled) {
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
                    },
                )
            }
        } else {
            Modifier
        }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(
                    start = horizontalInset,
                    end = horizontalInset,
                    top = verticalOffset,
                ).graphicsLayer {
                    if (isTop) {
                        translationX = offset.value.x
                        translationY = offset.value.y
                        rotationZ = (offset.value.x / 42f).coerceIn(-10f, 10f)
                    }
                }.testTag(if (isTop) SwipeTestTags.TOP_CARD else "${SwipeTestTags.CARD_PREFIX}${card.userId}")
                .then(dragModifier)
                .then(tapZoneModifier)
                .clip(SwipeCardTokens.Shape)
                .background(tone)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), SwipeCardTokens.Shape),
    ) {
        SwipeCardPhotos(
            isTop = isTop,
            resolvedPhotos = resolvedPhotos,
            pagerState = pagerState,
            displayName = card.displayName,
        )
        val linkedPartner = card.linkedPartner
        if (linkedPartner != null && decisionAlpha < 0.12f) {
            SwipeLinkedPartnerBadge(
                thumbnailUrl = linkedPartner.thumbnailUrl,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(18.dp),
                enabled = isTop && enabled,
                onClick =
                    if (isTop) {
                        { onLinkedPartnerTap(card) }
                    } else {
                        null
                    },
            )
        }
        if (isTop && decisionAlpha > 0f) {
            Text(
                text = decisionLabel,
                color = decisionColor,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier =
                    Modifier
                        .align(if (offset.value.x >= 0f) Alignment.TopStart else Alignment.TopEnd)
                        .padding(22.dp)
                        .graphicsLayer { alpha = decisionAlpha }
                        .border(2.dp, decisionColor, RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        SwipeCardFooter(
            card = card,
            distanceLabel = distanceLabel,
        )
    }
}
