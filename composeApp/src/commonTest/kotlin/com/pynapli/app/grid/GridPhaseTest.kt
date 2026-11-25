package com.pynapli.app.grid

import kotlin.test.Test
import kotlin.test.assertEquals

/** Which body the Grid shows; the "No one nearby yet" state only when it is really true. */
class GridPhaseTest {
    private val someone = GridProfileCardUi(id = "a", displayName = "A", online = false, distanceLabel = null, tone = GridCardTone.Warm)

    @Test
    fun noCardsNoFiltersNoErrorIsTheTrueEmptyState() {
        assertEquals(GridPhase.Empty, gridPhase(GridUiState(isRefreshing = false)))
    }

    @Test
    fun anyFilterOrErrorOrLoadIsNotTheEmptyState() {
        assertEquals(GridPhase.EmptyFiltered, gridPhase(GridUiState(filters = GridFilters(fresh = true))))
        assertEquals(GridPhase.Error, gridPhase(GridUiState(errorMessage = "No connection.")))
        assertEquals(GridPhase.LocationRequired, gridPhase(GridUiState(errorCode = "LOCATION_REQUIRED", errorMessage = "Share your location.")))
        assertEquals(GridPhase.Loading, gridPhase(GridUiState(isRefreshing = true)))
        assertEquals(GridPhase.Loading, gridPhase(GridUiState(cacheChecked = false)))
    }

    @Test
    fun aSparseGridIsContentNotEmpty() {
        assertEquals(GridPhase.Content, gridPhase(GridUiState(cards = listOf(someone))))
    }
}
