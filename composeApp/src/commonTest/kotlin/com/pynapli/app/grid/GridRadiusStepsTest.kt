package com.pynapli.app.grid

import com.pynapli.shared.distance.DiscoveryRadiusSteps
import com.pynapli.shared.network.dto.GridQueryRequestDto
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Grid distance slider and its label use exactly the server's radius steps (L-09). Before this the
 * slider was continuous 1-100 km and said "Within 26 km" while the server searched 50 km.
 */
class GridRadiusStepsTest {
    @Test
    fun theStepsAreTheServers() {
        assertEquals(listOf(2_000, 5_000, 10_000, 25_000, 50_000, 100_000), DiscoveryRadiusSteps.METERS)
        assertEquals(DiscoveryRadiusSteps.METERS.first(), GridFilters.MIN_RADIUS_METERS)
        assertEquals(DiscoveryRadiusSteps.METERS.last(), GridFilters.MAX_RADIUS_METERS)
        assertEquals(GridFilters.DEFAULT_RADIUS_METERS, GridFilters.snapRadius(GridFilters.DEFAULT_RADIUS_METERS))
    }

    @Test
    fun theLabelNamesTheRadiusTheServerSearches() {
        assertEquals("Within 50 km", distanceRadiusLabel(26_000))
        assertEquals("Within 25 km", distanceRadiusLabel(25_000))
        assertEquals("Within 2 km", distanceRadiusLabel(1_000))
        assertEquals("Within 100 km", distanceRadiusLabel(250_000))
    }

    @Test
    fun sliderPositionsLandOnSteps() {
        assertEquals(2_000, radiusForSliderPosition(0f))
        assertEquals(10_000, radiusForSliderPosition(2.4f))
        assertEquals(25_000, radiusForSliderPosition(2.6f))
        assertEquals(100_000, radiusForSliderPosition(9f))
    }

    @Test
    fun anOffStepStoredRadiusMapsUpLikeTheServer() {
        assertEquals(50_000, GridFilters.fromGridQueryRequest(GridQueryRequestDto(radiusMeters = 26_000)).radiusMeters)
        assertEquals(2_000, GridFilters.fromGridQueryRequest(GridQueryRequestDto(radiusMeters = 1_000)).radiusMeters)
        // And the request carries the step even if a state slipped through unsnapped.
        assertEquals(10_000, GridFilters(radiusMeters = 7_300).toGridQueryRequest(cursor = null).radiusMeters)
    }
}
