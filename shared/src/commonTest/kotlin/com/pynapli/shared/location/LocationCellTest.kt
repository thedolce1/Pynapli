package com.pynapli.shared.location

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocationCellTest {
    @Test
    fun snapsToTheNearestHundredthNotTheFloor() {
        assertEquals(4550, LocationCell.hundredths(45.5017))
        assertEquals(4551, LocationCell.hundredths(45.5067))
        assertEquals(-7357, LocationCell.hundredths(-73.5673))
        assertEquals(-7356, LocationCell.hundredths(-73.5649))
    }

    @Test
    fun negativeCoordinatesRoundTowardTheNearestLineNotTowardZero() {
        assertEquals(-1, LocationCell.hundredths(-0.0071))
        assertEquals(0, LocationCell.hundredths(-0.0049))
        assertEquals(-12208, LocationCell.hundredths(-122.0841))
    }

    @Test
    fun snappedValuesSitOnTheGridAndCarryNoFinerDigits() {
        val snapped = snapToLocationCell(latitude = 37.42199, longitude = -122.08413)
        assertEquals(3742, LocationCell.hundredths(snapped.latitude))
        assertEquals(-12208, LocationCell.hundredths(snapped.longitude))
        assertTrue(abs(snapped.latitude - 37.42) < 1e-9, "latitude ${snapped.latitude}")
        assertTrue(abs(snapped.longitude - -122.08) < 1e-9, "longitude ${snapped.longitude}")
    }

    @Test
    fun snappingIsIdempotent() {
        listOf(0.0, 0.004, -0.004, 45.5017, -73.5673, 89.996, -179.996, 180.0, -90.0, 2.305, 48.905).forEach { value ->
            val once = LocationCell.snapCoordinate(value)
            assertEquals(once, LocationCell.snapCoordinate(once), "not idempotent at $value")
        }
    }

    @Test
    fun zeroIsNeverNegative() {
        // -0.003 rounds to zero; the result must be +0.0 exactly as the SQL function returns it.
        assertEquals(0.0, LocationCell.snapCoordinate(-0.003))
        assertEquals(0.0, LocationCell.snapCoordinate(-0.0))
    }

    @Test
    fun everyPointOfOneCellSnapsToTheSameCentre() {
        val centre = snapToLocationCell(latitude = 45.50, longitude = -73.57)
        listOf(
            45.4951 to -73.5749,
            45.5049 to -73.5651,
            45.5000 to -73.5700,
            45.4999 to -73.5699,
        ).forEach { (latitude, longitude) ->
            assertEquals(centre, snapToLocationCell(latitude, longitude), "($latitude, $longitude)")
        }
    }
}
