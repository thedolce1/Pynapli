package com.pynapli.app.swipe

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import com.pynapli.shared.network.dto.SwipeCardDto
import kotlin.math.absoluteValue

/**
 * Where the top card is and how it was grabbed. Hoisted to the stack so the card underneath can
 * read the drag progress (inside `graphicsLayer`, so a drag frame never recomposes anything).
 */
@Stable
internal class SwipeDragState(
    initialOffset: Offset,
) {
    val offset = Animatable(initialOffset, Offset.VectorConverter)

    /** Grabbed in the top half: the card pivots around its bottom; bottom half pivots around the top. */
    var grabbedTopHalf by mutableStateOf(true)

    /** 0 at rest, 1 once the card is past a decision threshold (either side, or up). */
    fun progress(
        widthPx: Float,
        heightPx: Float,
    ): Float {
        val value = offset.value
        val horizontal = value.x.absoluteValue / (widthPx * SWIPE_COMMIT_FRACTION_X)
        val up = (-value.y).coerceAtLeast(0f) / (heightPx * SWIPE_COMMIT_FRACTION_UP)
        return maxOf(horizontal, up).coerceIn(0f, 1f)
    }
}

/**
 * The side each card left by, so Rewind can bring it back in from there. Lives above the deck so it
 * survives the deck emptying (the empty state replaces the stack).
 */
@Stable
internal class SwipeExitMemory {
    private val directions = mutableMapOf<String, Offset>()

    fun record(
        userId: String,
        direction: Offset,
    ) {
        directions[userId] = direction
    }

    fun take(userId: String): Offset? = directions.remove(userId)
}

@Composable
internal fun SwipeCardStack(
    cards: List<SwipeCardDto>,
    enabled: Boolean,
    requestedAction: String?,
    distanceUnit: String,
    onActionConsumed: () -> Unit,
    onOpenProfile: (SwipeCardDto) -> Unit,
    onLinkedPartnerTap: (SwipeCardDto) -> Unit,
    onSwiped: (SwipeCardDto, String) -> Unit,
    onPineappleGesture: (SwipeCardDto) -> Unit = {},
    exitMemory: SwipeExitMemory = remember { SwipeExitMemory() },
) {
    val visibleStack = remember(cards) { cards.take(VISIBLE_STACK_SIZE) }
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val topId = visibleStack.firstOrNull()?.userId
        // A card that comes back to the top after leaving (Rewind, or a refused action) starts off
        // screen on the side it left and springs back in.
        val dragState =
            remember(topId) {
                val returning = topId?.let(exitMemory::take)
                SwipeDragState(returning?.let { it * swipeExitDistance(widthPx, heightPx) } ?: Offset.Zero)
            }
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            visibleStack.asReversed().forEach { card ->
                // Keyed by person, so the card underneath keeps its state (and loaded photo) when
                // it is promoted to the top.
                key(card.userId) {
                    val depth = visibleStack.indexOfFirst { it.userId == card.userId }
                    SwipeCard(
                        card = card,
                        depth = depth,
                        isTop = depth == 0,
                        enabled = enabled,
                        widthPx = widthPx,
                        heightPx = heightPx,
                        dragState = dragState,
                        requestedAction = if (depth == 0) requestedAction else null,
                        distanceUnit = distanceUnit,
                        exitMemory = exitMemory,
                        onActionConsumed = onActionConsumed,
                        onOpenProfile = onOpenProfile,
                        onLinkedPartnerTap = onLinkedPartnerTap,
                        onSwiped = onSwiped,
                        onPineappleGesture = onPineappleGesture,
                    )
                }
            }
        }
    }
}

/** How far a card travels to be fully off screen from anywhere near the middle. */
internal fun swipeExitDistance(
    widthPx: Float,
    heightPx: Float,
): Float = (widthPx + heightPx) * 0.9f

private const val VISIBLE_STACK_SIZE = 3

/** Past this share of the width a horizontal drag decides Like / Pass on release. */
internal const val SWIPE_COMMIT_FRACTION_X = 0.28f

/** Past this share of the height an upward drag decides Pineapple Like on release. */
internal const val SWIPE_COMMIT_FRACTION_UP = 0.18f
