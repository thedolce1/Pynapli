package com.pynapli.app.swipe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import com.pynapli.shared.network.dto.SwipeCardDto

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
) {
    val visibleStack = remember(cards) { cards.take(3) }
    val renderStack = remember(visibleStack) { visibleStack.asReversed() }
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            renderStack.forEachIndexed { reverseIndex, card ->
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
                    onSwiped = onSwiped,
                )
            }
        }
    }
}
