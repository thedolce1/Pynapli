package com.pynapli.app.swipe

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliColors
import com.pynapli.app.design.PynapliErrorState
import com.pynapli.app.design.SwipeCardTokens
import com.pynapli.app.design.handCursor
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.filled.CloudOff
import com.pynapli.app.design.icons.filled.Place
import com.pynapli.app.design.icons.filled.Refresh
import com.pynapli.app.design.icons.filled.Star
import com.pynapli.app.design.icons.filled.Style
import com.pynapli.app.design.icons.filled.Tune

/**
 * The end of the deck is a destination, not a dead end: it says why it is empty and offers the next
 * move. Top Picks is a finite daily set, so it routes back to the main deck (SWIPE_IMPLEMENTATION_SPEC:
 * picks refresh every 24h). Otherwise the server has already run its relaxation pass (FILTERS_SPEC
 * L487-489), so the useful moves are widening filters or swiping somewhere else with Travel mode.
 */
@Composable
internal fun EmptyDeck(
    filtersActive: Boolean,
    onRefresh: () -> Unit,
    onOpenFilters: () -> Unit,
    topPicksMode: Boolean = false,
    onBackToForYou: () -> Unit = {},
    onOpenTravel: () -> Unit = {},
) {
    SwipeDeckStatus(
        icon = if (topPicksMode) Icons.Filled.Star else Icons.Filled.Style,
        iconTint = if (topPicksMode) PynapliColors.Accent else MaterialTheme.colorScheme.primary,
        title =
            when {
                topPicksMode -> "You've seen today's Top Picks"
                filtersActive -> "No one else matches your filters"
                else -> "You've seen everyone nearby"
            },
        message =
            when {
                topPicksMode -> "Fresh picks arrive daily. Keep swiping the main deck meanwhile."
                filtersActive -> "Widen distance or drop a dealbreaker to see more people, or swipe another city with Travel mode."
                else -> "New people join every day. Widen your search, or swipe another city with Travel mode."
            },
        modifier = Modifier.testTag(SwipeTestTags.EMPTY_DECK),
    ) {
        if (topPicksMode) {
            Button(onClick = onBackToForYou, modifier = Modifier.fillMaxWidth().handCursor()) {
                Text("Back to For You")
            }
            return@SwipeDeckStatus
        }
        Button(
            onClick = onOpenFilters,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .handCursor()
                    .testTag(SwipeTestTags.EMPTY_WIDEN_FILTERS),
        ) {
            Icon(imageVector = Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(if (filtersActive) "Widen filters" else "Widen your search")
        }
        OutlinedButton(
            onClick = onOpenTravel,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .handCursor()
                    .testTag(SwipeTestTags.EMPTY_TRAVEL),
        ) {
            Icon(imageVector = Icons.Filled.Place, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("Try Travel mode")
        }
        TextButton(onClick = onRefresh, modifier = Modifier.handCursor()) {
            Icon(imageVector = Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text("Check again")
        }
    }
}

/**
 * A full-deck load failure. Offline gets its own words: saved swipes are not lost (the crash-safe
 * queue sends them on reconnect), so the person is told so rather than shown a server string.
 */
@Composable
internal fun SwipeError(
    message: String,
    onRetry: () -> Unit,
    errorCode: String? = null,
) {
    if (errorCode in OFFLINE_ERROR_CODES) {
        SwipeDeckStatus(
            icon = Icons.Filled.CloudOff,
            iconTint = PynapliColors.TextSecondary,
            title = "You're offline",
            message = "Reconnect to load more people. Swipes you make are saved and send when you're back.",
            modifier = Modifier.testTag(SwipeTestTags.OFFLINE_DECK),
        ) {
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().handCursor()) {
                Icon(imageVector = Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Try again")
            }
        }
        return
    }
    PynapliErrorState(
        title = "Couldn't load your deck",
        message = message.ifBlank { "Check your connection and try again." },
        onRetry = onRetry,
    )
}

/** The first deck load: a quiet card-shaped placeholder where the card will be, not a bare spinner. */
@Composable
internal fun SwipeDeckLoading() {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .clip(SwipeCardTokens.Shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), SwipeCardTokens.Shape)
                .testTag(SwipeTestTags.LOADING_DECK),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SwipeDeckStatus(
    icon: ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit,
) {
    Column(
        modifier =
            modifier
                .widthIn(max = 360.dp)
                .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(30.dp))
        }
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(6.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            actions()
        }
    }
}

private val OFFLINE_ERROR_CODES = setOf("NETWORK_ERROR", "NETWORK_TIMEOUT")
