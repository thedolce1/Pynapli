package com.pynapli.shared.distance

/**
 * The only discovery radii the server ever tests (L-09, server CoarseLocation): any requested radius
 * snaps UP to the next step, so a slider moved metre by metre can't binary-search where someone is.
 * Shared so the client's slider offers exactly these steps and labels exactly the radius the server
 * uses (a continuous slider showing "26 km" while the server searched 50 km lied).
 */
object DiscoveryRadiusSteps {
    val METERS: List<Int> = listOf(2_000, 5_000, 10_000, 25_000, 50_000, 100_000)

    /** The step the server uses for [requestedMeters]: the first step at or above it, else the largest. */
    fun snapUp(requestedMeters: Int): Int = METERS.firstOrNull { it >= requestedMeters } ?: METERS.last()

    /** Index of [meters]'s step in [METERS] (after [snapUp]). */
    fun indexOf(meters: Int): Int = METERS.indexOf(snapUp(meters))
}
