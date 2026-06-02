package com.pynapli.app.grid

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.pynapli.app.design.PynapliColors

/**
 * Pull-to-refresh wrapper for the Grid body (GRID_IMPLEMENTATION_SPEC: the grid is a live index the
 * user can refresh). Uses the Material3 [PullToRefreshBox]; the [onRefresh] callback re-fetches the
 * current filters via the state holder, preserving scroll position and filters (stable card keys
 * animate the diff — the grid is never blanked). The token-styled indicator keeps the dark/gold
 * Pynapli look.
 */
@Composable
internal fun GridPullToRefresh(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val pullState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = modifier.fillMaxSize().testTag(GridTestTags.PULL_TO_REFRESH),
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pullState,
                isRefreshing = refreshing,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = PynapliColors.SurfaceRaised,
                color = PynapliColors.Accent,
            )
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}

internal object GridTestTags {
    const val PULL_TO_REFRESH = "grid-pull-to-refresh"
}
