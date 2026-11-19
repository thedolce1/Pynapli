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
import com.pynapli.app.design.icons.filled.Style
import com.pynapli.app.design.icons.filled.Tune

/**
 * The end of the deck is a destination, not a dead end: it says why it is empty and offers the next
 * move. The server has already run its relaxation pass (FILTERS_SPEC L487-489), so the useful
 * moves are widening filters or swiping somewhere else with Travel mode.
 */
@Composable
internal fun EmptyDeck(
    filtersActive: Boolean,
    onRefresh: () -> Unit,
    onOpenFilters: () -> Unit,
    onOpenTravel: () -> Unit = {},
    verifiedCouplesOnly: Boolean = false,
) {
    val copy = emptyDeckCopy(filtersActive = filtersActive, verifiedCouplesOnly = verifiedCouplesOnly)
    SwipeDeckStatus(
        icon = Icons.Filled.Style,
        iconTint = MaterialTheme.colorScheme.primary,
        title = copy.title,
        message = copy.message,
        modifier = Modifier.testTag(SwipeTestTags.EMPTY_DECK),
    ) {
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

internal data class EmptyDeckCopy(
    val title: String,
    val message: String,
)

/**
 * Empty-deck words. "Verified couples only" is never relaxed by the server, so with it on the deck
 * says plainly that no verified couples are nearby, rather than hinting at dealbreakers.
 */
internal fun emptyDeckCopy(
    filtersActive: Boolean,
    verifiedCouplesOnly: Boolean,
): EmptyDeckCopy =
    when {
        verifiedCouplesOnly ->
            EmptyDeckCopy(
                title = "No verified couples nearby yet",
                message =
                    "Only couples where both partners are selfie-verified show here. Widen distance or " +
                        "change filters, or swipe another city with Travel mode.",
            )
        filtersActive ->
            EmptyDeckCopy(
                title = "No one else matches your filters",
                message = "Widen distance or drop a dealbreaker to see more people, or swipe another city with Travel mode.",
            )
        else ->
            EmptyDeckCopy(
                title = "You've seen everyone nearby",
                message = "New people join every day. Widen your search, or swipe another city with Travel mode.",
            )
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
