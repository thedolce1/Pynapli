package com.pynapli.app.swipe

import kotlin.test.Test
import kotlin.test.assertEquals

class SwipeFiltersTest {
    @Test
    fun genderAndOrientationSerializeToTokensAndRoundTrip() {
        val filters =
            SwipeFilters(
                genders = setOf("woman", "non_binary"),
                orientations = setOf("pansexual"),
            )

        val request = filters.toSwipeDeckRequest()
        assertEquals(setOf("woman", "non_binary"), request.genders.toSet())
        assertEquals(setOf("pansexual"), request.orientations.toSet())

        val roundTripped = SwipeFilters.fromSwipeDeckRequest(request)
        assertEquals(setOf("woman", "non_binary"), roundTripped.genders)
        assertEquals(setOf("pansexual"), roundTripped.orientations)
    }
}
