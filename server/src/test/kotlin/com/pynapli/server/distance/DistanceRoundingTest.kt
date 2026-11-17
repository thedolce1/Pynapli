package com.pynapli.server.distance

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AUDIT-10: public distance buckets are coarse. Under 2 km everything reads "1 km", so the
 * label never resolves a 100 m boundary an attacker could walk across.
 */
class DistanceRoundingTest {
    @Test
    fun bucketsAreCoarse() {
        assertEquals(1_000, 0.0.toPublicDistanceMeters())
        assertEquals(1_000, 950.0.toPublicDistanceMeters())
        assertEquals(1_000, 1_999.0.toPublicDistanceMeters())
        assertEquals(2_000, 2_500.0.toPublicDistanceMeters())
        assertEquals(9_000, 9_999.0.toPublicDistanceMeters())
        assertEquals(10_000, 12_345.0.toPublicDistanceMeters())
        assertEquals(45_000, 49_999.0.toPublicDistanceMeters())
        assertEquals(60_000, 63_000.0.toPublicDistanceMeters())
    }
}
