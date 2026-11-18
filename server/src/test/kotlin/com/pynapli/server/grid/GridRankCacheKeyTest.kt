package com.pynapli.server.grid

import com.pynapli.server.discovery.DiscoveryFilters
import kotlin.test.Test
import kotlin.test.assertNotEquals

/**
 * The Grid candidate-rank cache shares a ranked window across viewers in the same filter
 * class (`DiscoveryFilters.gridRankCacheKey()`). Every HARD predicate in the rank SQL must
 * be part of the key. Guards the Has-photos gate added alongside the existing dimensions.
 */
class GridRankCacheKeyTest {
    @Test
    fun withPhotosOnlyChangesTheRankCacheKey() {
        assertNotEquals(
            DiscoveryFilters().gridRankCacheKey(),
            DiscoveryFilters(withPhotosOnly = true).gridRankCacheKey(),
        )
    }

    @Test
    fun experienceLevelAndBoundariesChangeTheRankCacheKey() {
        assertNotEquals(
            DiscoveryFilters().gridRankCacheKey(),
            DiscoveryFilters(lifestyleTags = setOf("some_experience")).gridRankCacheKey(),
        )
        assertNotEquals(
            DiscoveryFilters().gridRankCacheKey(),
            DiscoveryFilters(boundaryItems = setOf("photos")).gridRankCacheKey(),
        )
        // Lifestyle tokens and boundary items must not collide in the key.
        assertNotEquals(
            DiscoveryFilters(lifestyleTags = setOf("enm")).gridRankCacheKey(),
            DiscoveryFilters(boundaryItems = setOf("groups")).gridRankCacheKey(),
        )
    }
}
