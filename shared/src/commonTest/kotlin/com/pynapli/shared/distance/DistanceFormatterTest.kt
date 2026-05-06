package com.pynapli.shared.distance

import com.pynapli.shared.settings.AppSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class DistanceFormatterTest {
    @Test
    fun formatsMetricDistances() {
        assertEquals("800 m", formatDistanceForUnit(850, null, AppSettings.UNIT_METRIC))
        assertEquals("1.2 km", formatDistanceForUnit(1_240, null, AppSettings.UNIT_METRIC))
        assertEquals("12 km", formatDistanceForUnit(12_400, null, AppSettings.UNIT_METRIC))
    }

    @Test
    fun formatsImperialDistances() {
        assertEquals("2600 ft", formatDistanceForUnit(800, null, AppSettings.UNIT_IMPERIAL))
        assertEquals("0.7 mi", formatDistanceForUnit(1_200, null, AppSettings.UNIT_IMPERIAL))
        assertEquals("8 mi", formatDistanceForUnit(12_400, null, AppSettings.UNIT_IMPERIAL))
    }

    @Test
    fun fallsBackWhenServerDidNotExposeMeters() {
        assertEquals("hidden", formatDistanceForUnit(null, "hidden", AppSettings.UNIT_IMPERIAL))
    }
}
