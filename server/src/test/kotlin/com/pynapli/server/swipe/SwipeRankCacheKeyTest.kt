package com.pynapli.server.swipe

import com.pynapli.server.discovery.DiscoveryFilters
import kotlin.test.Test
import kotlin.test.assertNotEquals

/**
 * The swipe deck rank cache shares a ranked candidate window across viewers in the same
 * filter class (`DiscoveryFilters.rankCacheKey()`). Every HARD predicate in the deck SQL
 * must therefore be part of the key — otherwise two viewers whose only difference is that
 * predicate would collide on one window and see each other's (wrongly filtered) results.
 * These guards cover the identity filters (previously omitted) and the Has-photos gate.
 */
class SwipeRankCacheKeyTest {
    @Test
    fun gendersChangeTheRankCacheKey() {
        assertNotEquals(
            DiscoveryFilters().rankCacheKey(),
            DiscoveryFilters(genders = setOf("woman")).rankCacheKey(),
        )
        assertNotEquals(
            DiscoveryFilters(genders = setOf("woman")).rankCacheKey(),
            DiscoveryFilters(genders = setOf("man")).rankCacheKey(),
        )
    }

    @Test
    fun orientationsChangeTheRankCacheKey() {
        assertNotEquals(
            DiscoveryFilters().rankCacheKey(),
            DiscoveryFilters(orientations = setOf("pansexual")).rankCacheKey(),
        )
    }

    @Test
    fun experienceLevelAndBoundariesChangeTheRankCacheKey() {
        assertNotEquals(
            DiscoveryFilters().rankCacheKey(),
            DiscoveryFilters(lifestyleTags = setOf("new_to_lifestyle")).rankCacheKey(),
        )
        assertNotEquals(
            DiscoveryFilters().rankCacheKey(),
            DiscoveryFilters(boundaryItems = setOf("home_meets")).rankCacheKey(),
        )
    }

    @Test
    fun withPhotosOnlyChangesTheRankCacheKey() {
        assertNotEquals(
            DiscoveryFilters().rankCacheKey(),
            DiscoveryFilters(withPhotosOnly = true).rankCacheKey(),
        )
    }
}
