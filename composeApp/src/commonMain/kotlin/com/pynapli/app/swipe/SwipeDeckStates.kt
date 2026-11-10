package com.pynapli.app.swipe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliColors
import com.pynapli.app.design.PynapliErrorState
import com.pynapli.app.design.handCursor
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.filled.Refresh
import com.pynapli.app.design.icons.filled.Star
import com.pynapli.app.design.icons.filled.Style

@Composable
internal fun EmptyDeck(
    filtersActive: Boolean,
    onRefresh: () -> Unit,
    onOpenFilters: () -> Unit,
    topPicksMode: Boolean = false,
    onBackToForYou: () -> Unit = {},
) {
    // Top Picks is a finite daily set, so its empty state is distinct: confirm they've seen
    // today's picks and route back to the main deck (SWIPE_IMPLEMENTATION_SPEC: picks refresh
    // every 24h and are a shortcut into the swipe engine, not a replacement for it).
    // FILTERS_SPEC L487-489: otherwise, when filters are active and the deck is empty (server
    // already ran the relaxation pass per B3 and still found nothing), nudge the user toward
    // widening filters or switching to Grid. Plain "no profiles" copy remains for the unfiltered case.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = if (topPicksMode) Icons.Filled.Star else Icons.Filled.Style,
            contentDescription = null,
            tint =
                if (topPicksMode) {
                    PynapliColors.Accent.copy(alpha = 0.7f)
                } else {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                },
            modifier = Modifier.size(56.dp),
        )
        Text(
            text =
                when {
                    topPicksMode -> "You've seen today's Top Picks"
                    filtersActive -> "No more recommendations match your filters"
                    else -> "No more profiles nearby"
                },
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text =
                when {
                    topPicksMode -> "Fresh picks arrive daily. Keep swiping the main deck meanwhile."
                    filtersActive -> "Try widening distance or removing a dealbreaker. Grid shows live nearby profiles."
                    else -> "Check back later or expand your radius."
                },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                topPicksMode ->
                    Button(onClick = onBackToForYou, modifier = Modifier.handCursor()) {
                        Text("Back to For You")
                    }
                else -> {
                    if (filtersActive) {
                        OutlinedButton(onClick = onOpenFilters, modifier = Modifier.handCursor()) {
                            Text("Adjust filters")
                        }
                    }
                    Button(onClick = onRefresh, modifier = Modifier.handCursor()) {
                        Icon(imageVector = Icons.Filled.Refresh, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text("Refresh")
                    }
                }
            }
        }
    }
}

@Composable
internal fun SwipeError(
    message: String,
    onRetry: () -> Unit,
) {
    // Friendlier full-deck failure state via the shared error block (icon + title + message +
    // Retry), so the swipe deck fails like the rest of the app rather than showing a bare server
    // string. The outer Box (SwipeScreenContent) centers it.
    PynapliErrorState(
        title = "Couldn't load your deck",
        message = message.ifBlank { "Check your connection and try again." },
        onRetry = onRetry,
    )
}
