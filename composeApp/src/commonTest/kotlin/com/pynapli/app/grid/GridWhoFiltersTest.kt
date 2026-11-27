package com.pynapli.app.grid

import com.pynapli.shared.network.dto.GridQueryRequestDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** FILTERS_SPEC "Who" on the Grid client: Singles and Linked only; "Partnered" merged into Linked. */
class GridWhoFiltersTest {
    @Test
    fun singlesAndLinkedSerializeToTheWhoTokenAndLinkedOnly() {
        val singles = GridFilters(singles = true).toGridQueryRequest(cursor = null)
        assertEquals(listOf(GridFilters.SINGLES_CONTEXT), singles.relationshipContexts)
        assertEquals(null, singles.linkedOnly)

        val linked = GridFilters(linked = true).toGridQueryRequest(cursor = null)
        assertEquals(emptyList<String>(), linked.relationshipContexts)
        assertEquals(true, linked.linkedOnly)
    }

    @Test
    fun bothOnRoundTripsAsBoth() {
        val both = GridFilters(singles = true, linked = true)
        assertEquals(both, GridFilters.fromGridQueryRequest(both.toGridQueryRequest(cursor = null)))
    }

    @Test
    fun aPresetCarryingTheRetiredPartneredTokenRestoresAsLinked() {
        val restored = GridFilters.fromGridQueryRequest(GridQueryRequestDto(relationshipContexts = listOf(GridFilters.PARTNERED_CONTEXT)))
        assertTrue(restored.linked)
        assertFalse(restored.singles)
        assertEquals(1, restored.activeCount)
    }

    @Test
    fun aStoredNotLinkedFlagRestoresAsSingles() {
        val restored = GridFilters.fromGridQueryRequest(GridQueryRequestDto(linkedOnly = false))
        assertTrue(restored.singles)
        assertFalse(restored.linked)
    }
}
