package com.pynapli.app.swipe

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliColors
import com.pynapli.app.design.PynapliShape
import com.pynapli.app.design.SwipeCardTokens
import com.pynapli.app.design.rememberPynapliHaptics
import com.pynapli.shared.distance.formatDistanceForUnit
import com.pynapli.shared.network.dto.SwipeAction
import com.pynapli.shared.network.dto.SwipeCardDto
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * One card in the deck. The top card follows the finger: it rotates around a pivot that depends on
 * where it was grabbed, a fast flick or a long drag past the threshold commits (Like right, Pass
 * left, Pineapple up), anything else springs back. A committed card leaves along its fling direction
 * at its fling speed. Every per-frame value is read inside `graphicsLayer`, so a drag never
 * recomposes the card.
 */
@Composable
internal fun SwipeCard(
    card: SwipeCardDto,
    depth: Int,
    isTop: Boolean,
    enabled: Boolean,
    widthPx: Float,
    heightPx: Float,
    dragState: SwipeDragState,
    requestedAction: String?,
    distanceUnit: String,
    exitMemory: SwipeExitMemory,
    onActionConsumed: () -> Unit,
    onOpenProfile: (SwipeCardDto) -> Unit,
    onLinkedPartnerTap: (SwipeCardDto) -> Unit,
    onSwiped: (SwipeCardDto, String) -> Unit,
    onPineappleGesture: (SwipeCardDto) -> Unit,
) {
    val tone = card.userId.toBackgroundBrush()
    val scope = rememberCoroutineScope()
    val haptics = rememberPynapliHaptics()
    val density = LocalDensity.current
    val physics =
        remember(widthPx, heightPx, density) {
            SwipePhysics(
                widthPx = widthPx,
                heightPx = heightPx,
                flingVelocityPx = with(density) { FLING_VELOCITY_DP_PER_SEC.dp.toPx() },
                minFlingDistancePx = with(density) { MIN_FLING_DISTANCE_DP.dp.toPx() },
            )
        }
    var isExiting by remember(card.userId) { mutableStateOf(false) }
    val currentOnSwiped by rememberUpdatedState(onSwiped)
    val currentOnPineappleGesture by rememberUpdatedState(onPineappleGesture)

    // A card coming back (Rewind) starts off screen on the side it left and springs in.
    if (isTop) {
        LaunchedEffect(card.userId) {
            if (dragState.offset.value != Offset.Zero && !isExiting) {
                dragState.offset.animateTo(Offset.Zero, SettleSpring)
            }
        }
    }

    fun exit(
        action: String,
        velocity: Velocity,
    ) {
        if (isExiting) return
        isExiting = true
        scope.launch {
            try {
                val direction = physics.exitDirection(action, dragState.offset.value, velocity)
                exitMemory.record(card.userId, direction)
                val distance = swipeExitDistance(widthPx, heightPx)
                val speed = maxOf(velocity.length(), physics.defaultExitSpeedPx)
                val durationMs = (distance / speed * 1_000f).toInt().coerceIn(MIN_EXIT_MS, MAX_EXIT_MS)
                dragState.offset.animateTo(
                    targetValue = dragState.offset.value + direction * distance,
                    animationSpec = tween(durationMillis = durationMs, easing = LinearEasing),
                )
                currentOnSwiped(card, action)
            } finally {
                isExiting = false
            }
        }
    }

    // The action buttons and arrow keys play the same exit as a fling.
    LaunchedEffect(requestedAction, card.userId) {
        val action = requestedAction ?: return@LaunchedEffect
        onActionConsumed()
        if (!isTop || isExiting) return@LaunchedEffect
        haptics.commit()
        dragState.grabbedTopHalf = true
        exit(action, Velocity.Zero)
    }

    val dragModifier =
        if (isTop && enabled) {
            Modifier.pointerInput(card.userId, physics) {
                val tracker = VelocityTracker()
                var position = Offset.Zero
                var lastDecision: String? = null
                detectDragGestures(
                    onDragStart = { start ->
                        if (isExiting) return@detectDragGestures
                        dragState.grabbedTopHalf = start.y < size.height / 2f
                        tracker.resetTracking()
                        position = dragState.offset.value
                        lastDecision = physics.decisionFor(position)
                    },
                    onDrag = { change, amount ->
                        if (isExiting) return@detectDragGestures
                        change.consume()
                        position += amount
                        tracker.addPosition(change.uptimeMillis, position)
                        val target = position
                        scope.launch { dragState.offset.snapTo(target) }
                        // A light tick each time the drag crosses a threshold, in or out.
                        val decision = physics.decisionFor(position)
                        if (decision != lastDecision) {
                            haptics.thresholdTick()
                            lastDecision = decision
                        }
                    },
                    onDragEnd = {
                        if (isExiting) return@detectDragGestures
                        val velocity = tracker.calculateVelocity()
                        when (val action = physics.releaseDecision(dragState.offset.value, velocity)) {
                            null -> scope.launch { dragState.offset.animateTo(Offset.Zero, SettleSpring, velocity.toOffset()) }
                            SwipeAction.PINEAPPLE_LIKE -> {
                                // Up opens the Pineapple Like sheet (optional note, spends one); Send
                                // there plays the upward exit. The card settles back meanwhile.
                                scope.launch { dragState.offset.animateTo(Offset.Zero, SettleSpring, velocity.toOffset()) }
                                currentOnPineappleGesture(card)
                            }
                            else -> {
                                haptics.commit()
                                exit(action, velocity)
                            }
                        }
                    },
                    onDragCancel = {
                        if (!isExiting) scope.launch { dragState.offset.animateTo(Offset.Zero, SettleSpring) }
                    },
                )
            }
        } else {
            Modifier
        }

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
    // G2-2: the server's stable keys, index for index (absent from an older server: cache by URL).
    val resolvedPhotoKeys =
        remember(card.userId, card.photoUrls, card.photoCacheKeys) {
            card.photoCacheKeys.takeIf { it.size == resolvedPhotos.size }.orEmpty()
        }
    var photoIndex by remember(card.userId) { mutableIntStateOf(0) }
    val tapModifier =
        if (isTop && enabled) {
            Modifier.pointerInput(card.userId, resolvedPhotos.size) {
                detectTapGestures(
                    onTap = { tap ->
                        when {
                            // The info area at the bottom opens the full profile.
                            tap.y > size.height * PROFILE_TAP_ZONE_START -> onOpenProfile(card)
                            // Left / right half steps through the photos, instantly (no slide).
                            tap.x < size.width / 2f -> photoIndex = (photoIndex - 1).coerceAtLeast(0)
                            else -> photoIndex = (photoIndex + 1).coerceAtMost(resolvedPhotos.lastIndex.coerceAtLeast(0))
                        }
                    },
                )
            }
        } else {
            Modifier
        }

    // Gestures sit outside the moving layer, so drag deltas and the grab point are in screen space
    // (not rotated or shifted by the card's own transform).
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .testTag(if (isTop) SwipeTestTags.TOP_CARD else "${SwipeTestTags.CARD_PREFIX}${card.userId}")
                .then(dragModifier)
                .then(tapModifier),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        if (isTop) {
                            val offset = dragState.offset.value
                            translationX = offset.x
                            translationY = offset.y
                            val rotationSign = if (dragState.grabbedTopHalf) 1f else -1f
                            rotationZ =
                                (offset.x / widthPx * MAX_ROTATION_DEGREES * rotationSign)
                                    .coerceIn(-MAX_ROTATION_DEGREES, MAX_ROTATION_DEGREES)
                            transformOrigin = TransformOrigin(0.5f, if (dragState.grabbedTopHalf) 1f else 0f)
                        } else {
                            // The card underneath rises into place as the top card leaves.
                            val effectiveDepth = (depth - dragState.progress(widthPx, heightPx)).coerceAtLeast(0f)
                            val scale = 1f - BACK_CARD_SCALE_STEP * effectiveDepth
                            scaleX = scale
                            scaleY = scale
                            translationY = BACK_CARD_LIFT_DP.dp.toPx() * effectiveDepth
                            alpha = (1f - BACK_CARD_FADE_STEP * effectiveDepth).coerceIn(0f, 1f)
                        }
                    }.clip(SwipeCardTokens.Shape)
                    .background(tone)
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), SwipeCardTokens.Shape),
        ) {
            SwipeCardPhotos(
                isTop = isTop,
                resolvedPhotos = resolvedPhotos,
                photoIndex = photoIndex,
                displayName = card.displayName,
                photoCacheKeys = resolvedPhotoKeys,
                firstPhotoPlaceholderCacheKey = card.photoThumbnailCacheKey,
            )
            val linkedPartner = card.linkedPartner
            if (linkedPartner != null) {
                SwipeLinkedPartnerBadge(
                    thumbnailUrl = linkedPartner.thumbnailUrl,
                    thumbnailCacheKey = linkedPartner.thumbnailCacheKey,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 22.dp, end = 18.dp)
                            // Out of the way while a stamp is showing.
                            .graphicsLayer {
                                alpha = if (isTop) 1f - (dragState.progress(widthPx, heightPx) * 3f).coerceIn(0f, 1f) else 1f
                            },
                    enabled = isTop && enabled,
                    coupleVerified = card.coupleVerified,
                    onClick =
                        if (isTop) {
                            { onLinkedPartnerTap(card) }
                        } else {
                            null
                        },
                )
            }
            if (isTop) {
                SwipeStamps(dragState = dragState, physics = physics)
            }
            SwipeCardFooter(
                card = card,
                distanceLabel = distanceLabel,
                onOpenProfile = if (isTop && enabled) ({ onOpenProfile(card) }) else null,
            )
        }
    }
}

/** LIKE / NOPE / PINEAPPLE stamps, each fading in with the drag towards it. */
@Composable
private fun BoxScope.SwipeStamps(
    dragState: SwipeDragState,
    physics: SwipePhysics,
) {
    SwipeStamp(
        text = "LIKE",
        color = PynapliColors.Success,
        rotation = -14f,
        alpha = { physics.stampAlpha(dragState.offset.value, SwipeAction.LIKE) },
        modifier =
            Modifier
                .align(Alignment.TopStart)
                .padding(start = 24.dp, top = 40.dp)
                .testTag(SwipeTestTags.STAMP_LIKE),
    )
    SwipeStamp(
        text = "NOPE",
        color = PynapliColors.Danger,
        rotation = 14f,
        alpha = { physics.stampAlpha(dragState.offset.value, SwipeAction.PASS) },
        modifier =
            Modifier
                .align(Alignment.TopEnd)
                .padding(end = 24.dp, top = 40.dp)
                .testTag(SwipeTestTags.STAMP_NOPE),
    )
    SwipeStamp(
        text = "PINEAPPLE",
        color = PynapliColors.Accent,
        rotation = -6f,
        alpha = { physics.stampAlpha(dragState.offset.value, SwipeAction.PINEAPPLE_LIKE) },
        modifier =
            Modifier
                .align(Alignment.Center)
                .padding(bottom = 80.dp)
                .testTag(SwipeTestTags.STAMP_PINEAPPLE),
    )
}

@Composable
private fun SwipeStamp(
    text: String,
    color: Color,
    rotation: Float,
    alpha: () -> Float,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        modifier =
            modifier
                // Decoration only, and invisible at rest: a screen reader must not announce it.
                .semantics { hideFromAccessibility() }
                .graphicsLayer {
                    this.alpha = alpha()
                    rotationZ = rotation
                }.border(3.dp, color, PynapliShape.CardShape)
                .padding(horizontal = 14.dp, vertical = 4.dp),
    )
}

/** The decision rules, in pixels for this card size. Pure, so they are unit-testable. */
internal class SwipePhysics(
    widthPx: Float,
    heightPx: Float,
    private val flingVelocityPx: Float,
    private val minFlingDistancePx: Float,
) {
    private val thresholdX = widthPx * SWIPE_COMMIT_FRACTION_X
    private val thresholdUp = heightPx * SWIPE_COMMIT_FRACTION_UP

    /** A button or key press leaves at about this speed (px/s). */
    val defaultExitSpeedPx: Float = widthPx * DEFAULT_EXIT_WIDTHS_PER_SEC

    /** What letting go here, without speed, would do. */
    fun decisionFor(offset: Offset): String? =
        when {
            -offset.y > thresholdUp && -offset.y > offset.x.absoluteValue -> SwipeAction.PINEAPPLE_LIKE
            offset.x > thresholdX -> SwipeAction.LIKE
            offset.x < -thresholdX -> SwipeAction.PASS
            else -> null
        }

    /** A fast short flick commits; a slow drag past the threshold commits; otherwise null (spring back). */
    fun releaseDecision(
        offset: Offset,
        velocity: Velocity,
    ): String? {
        val flungUp =
            velocity.y < -flingVelocityPx &&
                velocity.y.absoluteValue > velocity.x.absoluteValue &&
                offset.y < -minFlingDistancePx
        if (flungUp) return SwipeAction.PINEAPPLE_LIKE
        val flungSideways =
            velocity.x.absoluteValue > flingVelocityPx &&
                velocity.x.absoluteValue > velocity.y.absoluteValue &&
                offset.x.absoluteValue > minFlingDistancePx &&
                velocity.x.sign == offset.x.sign
        if (flungSideways) return if (velocity.x > 0f) SwipeAction.LIKE else SwipeAction.PASS
        return decisionFor(offset)
    }

    /** A unit vector: along the fling when there was one, else the way the card already leans. */
    fun exitDirection(
        action: String,
        offset: Offset,
        velocity: Velocity,
    ): Offset {
        val fallback =
            when (action) {
                SwipeAction.PASS -> Offset(-1f, 0.12f)
                SwipeAction.PINEAPPLE_LIKE -> Offset(0f, -1f)
                else -> Offset(1f, 0.12f)
            }.normalized()
        val candidate =
            when {
                velocity.length() > flingVelocityPx -> Offset(velocity.x, velocity.y)
                offset.getDistance() > minFlingDistancePx -> offset
                else -> fallback
            }
        // Never leave against the decision: a Like always exits right, a Pineapple always upwards.
        val agrees =
            when (action) {
                SwipeAction.LIKE -> candidate.x > 0f
                SwipeAction.PASS -> candidate.x < 0f
                else -> candidate.y < 0f
            }
        return if (agrees) candidate.normalized() else fallback
    }

    fun stampAlpha(
        offset: Offset,
        action: String,
    ): Float =
        when (action) {
            SwipeAction.PINEAPPLE_LIKE ->
                if (-offset.y > offset.x.absoluteValue) ((-offset.y) / thresholdUp).coerceIn(0f, 1f) else 0f
            else -> {
                val towards = if (action == SwipeAction.LIKE) offset.x else -offset.x
                if (towards > 0f && towards >= -offset.y) (towards / thresholdX).coerceIn(0f, 1f) else 0f
            }
        }
}

private fun Offset.normalized(): Offset {
    val length = getDistance()
    return if (length > 0f) this / length else this
}

private fun Velocity.length(): Float = sqrt(x * x + y * y)

private fun Velocity.toOffset(): Offset = Offset(x, y)

/** A natural settle: a little give, no wobble. */
private val SettleSpring = spring<Offset>(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)

private const val MAX_ROTATION_DEGREES = 16f
private const val FLING_VELOCITY_DP_PER_SEC = 900f
private const val MIN_FLING_DISTANCE_DP = 24f
private const val DEFAULT_EXIT_WIDTHS_PER_SEC = 3.2f
private const val MIN_EXIT_MS = 120
private const val MAX_EXIT_MS = 300
private const val BACK_CARD_SCALE_STEP = 0.05f
private const val BACK_CARD_LIFT_DP = 12f
private const val BACK_CARD_FADE_STEP = 0.18f

/** Taps below this share of the card height open the profile; above it they step through photos. */
private const val PROFILE_TAP_ZONE_START = 0.72f
