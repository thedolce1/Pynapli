package com.pynapli.app.swipe

import com.pynapli.shared.network.dto.SwipeDealbreakerKey
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun verifiedCouplesSerializesCountsAndRoundTripsWithoutSettingLinked() {
        val request = SwipeFilters(verifiedCouples = true).toSwipeDeckRequest()
        assertEquals(true, request.verifiedCouplesOnly)
        assertEquals(null, request.linkedOnly)

        val roundTripped = SwipeFilters.fromSwipeDeckRequest(request)
        assertEquals(true, roundTripped.verifiedCouples)
        assertEquals(1, roundTripped.activeCount)
        // A payload saved before the field existed loads with it off.
        assertEquals(false, SwipeFilters.fromSwipeDeckRequest(SwipeDeckRequestDto(verifiedOnly = true)).verifiedCouples)
    }

    @Test
    fun verifiedCouplesSharesTheVerifiedLock() {
        val locked =
            SwipeFilters(verifiedCouples = false, dealbreakers = setOf(SwipeDealbreakerKey.VERIFIED))
                .withVerifiedCouplesToggled()
        assertTrue(locked.verifiedCouples)
        assertTrue(SwipeDealbreakerKey.VERIFIED in locked.dealbreakers)

        // Turning it off while "Verified only" is off drops the lock; with "Verified only" on it stays.
        assertFalse(SwipeDealbreakerKey.VERIFIED in locked.withVerifiedCouplesToggled().dealbreakers)
        val stillVerifiedOnly = locked.copy(verifiedOnly = true).withVerifiedCouplesToggled()
        assertTrue(SwipeDealbreakerKey.VERIFIED in stillVerifiedOnly.dealbreakers)
    }

    @Test
    fun hasPrivateAlbumSerializesAndRoundTrips() {
        val request = SwipeFilters(hasPrivateAlbum = true).toSwipeDeckRequest()
        assertEquals(true, request.hasPrivateAlbumOnly)

        val roundTripped = SwipeFilters.fromSwipeDeckRequest(request)
        assertEquals(true, roundTripped.hasPrivateAlbum)
        assertTrue(roundTripped.isAnyActive)
    }
}
