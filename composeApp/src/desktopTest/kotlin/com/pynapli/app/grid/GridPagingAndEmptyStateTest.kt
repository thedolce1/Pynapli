package com.pynapli.app.grid

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliTheme
import com.pynapli.app.location.LocationUiState
import kotlin.test.Test
import kotlin.test.assertEquals

class GridPagingAndEmptyStateTest {
    private fun cards(count: Int) =
        (1..count).map { index ->
            GridProfileCardUi(id = "c$index", displayName = "P$index", online = false, distanceLabel = "1 km")
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aFailedPageStopsAutoPagingAndOffersRetry() =
        runComposeUiTest {
            var loadMoreCalls = 0
            setContent {
                PynapliTheme(themeMode = "dark") {
                    Box(modifier = Modifier.size(width = 390.dp, height = 844.dp)) {
                        ProfileGrid(
                            cards = cards(3),
                            distanceUnit = "metric",
                            gridState = rememberLazyGridState(),
                            onProfileSelected = {},
                            onLoadMore = { loadMoreCalls += 1 },
                            canLoadMore = true,
                            isLoadingMore = false,
                            loadMoreFailed = true,
                        )
                    }
                }
            }

            waitForIdle()
            assertEquals(0, loadMoreCalls, "a failed page must not be retried automatically")
            onNodeWithTag(GRID_LOAD_MORE_RETRY_TAG).performClick()
            assertEquals(1, loadMoreCalls)
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun anEmptyUnfilteredGridSaysSoInsteadOfStayingBlank() =
        runComposeUiTest {
            var retries = 0
            setContent {
                PynapliTheme(themeMode = "dark") {
                    Box(modifier = Modifier.size(width = 390.dp, height = 844.dp)) {
                        GridBody(
                            state = GridUiState(isRefreshing = false),
                            locationState = LocationUiState(),
                            distanceUnit = "metric",
                            gridState = rememberLazyGridState(),
                            onProfileSelected = {},
                            onLoadMore = {},
                            onUseCurrentLocation = {},
                            onRetry = { retries += 1 },
                            onClearFilters = {},
                        )
                    }
                }
            }

            onNodeWithText(GRID_EMPTY_TITLE).assertExists()
            onNodeWithText("Refresh").performClick()
            assertEquals(1, retries)
        }
}
