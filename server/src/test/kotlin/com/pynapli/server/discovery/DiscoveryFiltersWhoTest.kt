package com.pynapli.server.discovery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FILTERS_SPEC "Who": the Singles / Partnered tokens fold onto linkedOnly (the visible link), and the
 * free-text relationship_context is never matched. Before the fold the tokens went through untouched
 * and were compared with the free text.
 */
class DiscoveryFiltersWhoTest {
    private fun parse(
        contexts: List<String>,
        linkedOnly: Boolean? = null,
    ): DiscoveryFilters = discoveryFiltersFrom(minimumAge = null, maximumAge = null, relationshipContexts = contexts, linkedOnly = linkedOnly) { IllegalArgumentException(it) }

    @Test
    fun singlesMeansNoVisibleLink() {
        val filters = parse(listOf("single"))
        assertEquals(false, filters.linkedOnly)
        assertTrue(filters.relationshipContexts.isEmpty())
    }

    @Test
    fun partneredAndLinkedMeanAVisibleLink() {
        assertEquals(true, parse(listOf("partnered")).linkedOnly)
        assertEquals(true, parse(listOf(" Linked ")).linkedOnly)
        assertEquals(true, parse(emptyList(), linkedOnly = true).linkedOnly)
    }

    @Test
    fun bothStatesAreTheUnionEveryone() {
        assertEquals(null, parse(listOf("single", "partnered")).linkedOnly)
        assertEquals(null, parse(listOf("single"), linkedOnly = true).linkedOnly)
        assertEquals(null, parse(listOf("partnered"), linkedOnly = false).linkedOnly)
    }

    @Test
    fun otherFreeTextFiltersNothing() {
        val filters = parse(listOf("married, open"))
        assertEquals(null, filters.linkedOnly)
        assertTrue(filters.relationshipContexts.isEmpty())
        // The explicit flag alone is unchanged.
        assertEquals(false, parse(emptyList(), linkedOnly = false).linkedOnly)
    }
}
