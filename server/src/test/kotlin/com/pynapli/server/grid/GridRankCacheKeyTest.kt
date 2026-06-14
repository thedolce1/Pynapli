package com.pynapli.server.grid

import com.pynapli.server.discovery.DiscoveryFilters
import org.junit.Test
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
}
