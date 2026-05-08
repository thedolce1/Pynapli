package com.pynapli.app.location

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class DeviceLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Int?
)

internal fun DeviceLocation.distanceMetersTo(other: DeviceLocation): Double {
    val lat1 = latitude.toRadians()
    val lat2 = other.latitude.toRadians()
    val deltaLat = (other.latitude - latitude).toRadians()
    val deltaLon = (other.longitude - longitude).toRadians()
    val a = sin(deltaLat / 2) * sin(deltaLat / 2) +
        cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2)
    return EARTH_RADIUS_METERS * 2 * atan2(sqrt(a), sqrt(1 - a))
}

private fun Double.toRadians(): Double = this * kotlin.math.PI / 180.0

private const val EARTH_RADIUS_METERS = 6_371_000.0
