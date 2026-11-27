package com.pynapli.app.grid

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every filter change from the sheet goes back to Nearby, as Apply and the quick chips already did.
 * Before, Clear all and applying a saved preset changed the Nearby filters while Favorites stayed on
 * screen, so nothing visible happened.
 */
class GridFavoritesResetTest {
    @Test
    fun aChangeWhileFavoritesShowsGoesBackToNearbyFirst() {
        val calls = mutableListOf<String>()
        changeNearbyFilters(showingFavorites = true, onShowNearby = { calls += "nearby" }) { calls += "change" }
        assertEquals(listOf("nearby", "change"), calls)
    }

    @Test
    fun aChangeOnNearbyJustChanges() {
        val calls = mutableListOf<String>()
        changeNearbyFilters(showingFavorites = false, onShowNearby = { calls += "nearby" }) { calls += "change" }
        assertEquals(listOf("change"), calls)
    }
}
