package com.pynapli.app.swipe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun withPhotosSerializesAndRoundTripsAndCounts() {
        val request = SwipeFilters(withPhotos = true).toSwipeDeckRequest()
        assertEquals(true, request.withPhotosOnly)

        val roundTripped = SwipeFilters.fromSwipeDeckRequest(request)
        assertEquals(true, roundTripped.withPhotos)
        assertTrue(roundTripped.isAnyActive)
        assertEquals(0, SwipeFilters().activeCount)
        assertEquals(1, SwipeFilters(withPhotos = true).activeCount)
    }
}
