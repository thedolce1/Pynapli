package com.pynapli.app.swipe

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliColors
import com.pynapli.app.design.PynapliContentWidth
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.automirrored.filled.ArrowBack
import com.pynapli.app.design.icons.automirrored.filled.ArrowForward
import com.pynapli.app.design.icons.filled.ArrowUpward
import com.pynapli.shared.network.dto.SwipeCardDto

@Composable
internal fun SwipeScreenContent(
    state: SwipeUiState,
    travelCityName: String?,
    requestedAction: String?,
    distanceUnit: String,
    onOpenFilters: () -> Unit,
    onOpenTravel: () -> Unit,
    onOpenStore: () -> Unit,
    onOpenPsync: (() -> Unit)? = null,
    onFilterSelected: (SwipeFilterType) -> Unit,
    onRetryDeck: () -> Unit,
    onActionConsumed: () -> Unit,
    onOpenProfile: (SwipeCardDto) -> Unit,
    onLinkedPartnerTap: (SwipeCardDto) -> Unit,
    onSwiped: (SwipeCardDto, String) -> Unit,
    onRetryPendingActions: () -> Unit,
    onRewind: () -> Unit,
    onPass: () -> Unit,
    onPineappleLike: () -> Unit,
    onLike: () -> Unit,
    modifier: Modifier = Modifier,
    // Something covers the deck (the match moment, a sheet): keys stay with it, and the stage takes
    // focus back when it closes so the arrow keys keep working without a click.
    deckCovered: Boolean = false,
) {
    // Desktop stage (WEB_PWA_GAMEPLAN.md W4): wide panes center the deck at a card-
    // comfortable width instead of stretching one hero card across a monitor, and the
    // keyboard drives it — ← pass, ↑ pineapple, → like. Phone-width panes unchanged.
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .testTag(SwipeTestTags.ROOT)
                .background(MaterialTheme.colorScheme.background),
    ) {
        val wideStage = maxWidth >= WideStageMinPaneWidth
        val keyActionsEnabled = state.hasMore && !state.actionQueueFull && !state.isSubmitting && !deckCovered
        val stageFocus = remember { FocusRequester() }
        // Which side each card left by, so Rewind brings it back in from there.
        val exitMemory = remember { SwipeExitMemory() }
        // Keys need focus; grab it whenever the wide stage (re)appears so arrows work
        // immediately. Compact never requests — the phone flow has no keyboard.
        LaunchedEffect(wideStage, deckCovered) {
            if (wideStage && !deckCovered) runCatching { stageFocus.requestFocus() }
        }

        Column(
            modifier =
                Modifier
                    .then(
                        if (wideStage) {
                            Modifier
                                .align(Alignment.TopCenter)
                                .widthIn(max = PynapliContentWidth.Stage)
                                .fillMaxHeight()
                        } else {
                            Modifier.fillMaxSize()
                        },
                    ).statusBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 12.dp)
                    .focusRequester(stageFocus)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (!wideStage || event.type != KeyEventType.KeyDown || !keyActionsEnabled) {
                            return@onPreviewKeyEvent false
                        }
                        when (event.key) {
                            Key.DirectionLeft -> {
                                onPass()
                                true
                            }

                            Key.DirectionRight -> {
                                onLike()
                                true
                            }

                            Key.DirectionUp -> {
                                onPineappleLike()
                                true
                            }

                            else -> false
                        }
                    }
                    // Space / Enter open the top card's profile. onKeyEvent, not preview, so a focused
                    // button (a filter chip, an action) still gets its own Space / Enter first.
                    .onKeyEvent { event ->
                        if (!wideStage || deckCovered || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        val top = state.topCard ?: return@onKeyEvent false
                        when (event.key) {
                            Key.Spacebar, Key.Enter, Key.NumPadEnter -> {
                                onOpenProfile(top)
                                true
                            }
                            else -> false
                        }
                    },
        ) {
            SwipeHeader(
                activeFilterCount = state.swipeFilters.activeCount,
                travelCityName = travelCityName,
                onOpenFilters = onOpenFilters,
                onOpenTravel = onOpenTravel,
                onOpenPsync = onOpenPsync,
            )
            Spacer(Modifier.height(10.dp))
            SwipeFilterRow(
                filters = state.filters,
                onFilterSelected = onFilterSelected,
            )
            state.paywallMessage?.let { message ->
                SwipePaywallRow(
                    message = message,
                    onOpenStore = onOpenStore,
                )
            }
            if (state.topPicksMode) {
                // Make the curated mode legible on the deck itself (the chip alone is easy to miss)
                // and set the expectation that the set is finite + refreshes daily.
                Text(
                    text = "Today's Top Picks · refreshes daily",
                    color = PynapliColors.Accent,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 6.dp, start = 2.dp),
                )
            }
            Spacer(Modifier.height(10.dp))

            SwipeDeckArea(
                state = state,
                requestedAction = requestedAction,
                distanceUnit = distanceUnit,
                onRetryDeck = onRetryDeck,
                onOpenFilters = onOpenFilters,
                onBackToForYou = { onFilterSelected(SwipeFilterType.ForYou) },
                onOpenTravel = onOpenTravel,
                onPineappleGesture = { onPineappleLike() },
                exitMemory = exitMemory,
                onActionConsumed = onActionConsumed,
                onOpenProfile = onOpenProfile,
                onLinkedPartnerTap = onLinkedPartnerTap,
                onSwiped = onSwiped,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
            )

            Spacer(Modifier.height(16.dp))
            if (state.errorMessage != null && state.cards.isNotEmpty()) {
                SwipeInlineError(message = state.errorMessage)
                Spacer(Modifier.height(8.dp))
            }
            if (state.pendingActionCount > 0) {
                SwipePendingSync(
                    count = state.pendingActionCount,
                    onRetry = onRetryPendingActions,
                )
                Spacer(Modifier.height(8.dp))
            }

            SwipeActions(
                actionEnabled = state.hasMore && !state.actionQueueFull,
                rewindEnabled = !state.isSubmitting && !state.isRewinding,
                isSubmitting = false,
                onRewind = onRewind,
                onPass = onPass,
                onPineappleLike = onPineappleLike,
                onLike = onLike,
            )

            if (wideStage) {
                SwipeKeyboardHints(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                )
            }
        }
    }
}

private val WideStageMinPaneWidth = 600.dp

@Composable
private fun SwipeKeyboardHints(modifier: Modifier = Modifier) {
    // Icon glyphs, not arrow characters — the bundled font has no ←/↑/→.
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SwipeKeyHint(icon = Icons.AutoMirrored.Filled.ArrowBack, label = "Pass")
        SwipeKeyHint(icon = Icons.Filled.ArrowUpward, label = "Pineapple")
        SwipeKeyHint(icon = Icons.AutoMirrored.Filled.ArrowForward, label = "Like")
    }
}

@Composable
private fun SwipeKeyHint(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = PynapliColors.TextTertiary,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = label,
            color = PynapliColors.TextTertiary,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun SwipeDeckArea(
    state: SwipeUiState,
    requestedAction: String?,
    distanceUnit: String,
    onRetryDeck: () -> Unit,
    onOpenFilters: () -> Unit,
    onBackToForYou: () -> Unit,
    onOpenTravel: () -> Unit,
    onPineappleGesture: (SwipeCardDto) -> Unit,
    exitMemory: SwipeExitMemory,
    onActionConsumed: () -> Unit,
    onOpenProfile: (SwipeCardDto) -> Unit,
    onLinkedPartnerTap: (SwipeCardDto) -> Unit,
    onSwiped: (SwipeCardDto, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        when {
            state.isLoading && state.cards.isEmpty() -> {
                SwipeDeckLoading()
            }
            state.errorMessage != null && state.cards.isEmpty() && state.nextCursor == null -> {
                SwipeError(
                    message = state.errorMessage,
                    onRetry = onRetryDeck,
                    errorCode = state.errorCode,
                )
            }
            state.cards.isEmpty() && state.nextCursor != null -> {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            !state.hasMore -> {
                EmptyDeck(
                    filtersActive = state.swipeFilters.isAnyActive,
                    onRefresh = onRetryDeck,
                    onOpenFilters = onOpenFilters,
                    topPicksMode = state.topPicksMode,
                    onBackToForYou = onBackToForYou,
                    onOpenTravel = onOpenTravel,
                )
            }
            else -> {
                SwipeCardStack(
                    cards = state.cards,
                    enabled = !state.actionQueueFull,
                    requestedAction = requestedAction,
                    distanceUnit = distanceUnit,
                    onActionConsumed = onActionConsumed,
                    onOpenProfile = onOpenProfile,
                    onLinkedPartnerTap = onLinkedPartnerTap,
                    onSwiped = onSwiped,
                    onPineappleGesture = onPineappleGesture,
                    exitMemory = exitMemory,
                )
            }
        }
    }
}
