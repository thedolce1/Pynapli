package com.pynapli.shared.distance

import com.pynapli.shared.settings.AppSettings
import kotlin.math.roundToInt

fun formatDistanceForUnit(
    distanceMeters: Int?,
    fallbackLabel: String?,
    distanceUnit: String
): String? {
    val meters = distanceMeters ?: return fallbackLabel
    return when (distanceUnit) {
        AppSettings.UNIT_IMPERIAL -> meters.toImperialDistanceLabel()
        else -> meters.toMetricDistanceLabel()
    }
}

private fun Int.toMetricDistanceLabel(): String {
    if (this < METERS_PER_KILOMETER) {
        val roundedMeters = ((this / 100) * 100).coerceAtLeast(100)
        return "$roundedMeters m"
    }

    val tenths = this / 100
    if (tenths < 100) {
        return "${tenths / 10}.${tenths % 10} km"
    }

    return "${(this / METERS_PER_KILOMETER.toDouble()).roundToInt()} km"
}

private fun Int.toImperialDistanceLabel(): String {
    val feet = this * FEET_PER_METER
    if (this < METERS_PER_KILOMETER) {
        val roundedFeet = ((feet / 100).toInt() * 100).coerceAtLeast(100)
        return "$roundedFeet ft"
    }

    if (this >= 10_000) {
        return "${(this / METERS_PER_MILE).roundToInt()} mi"
    }

    val tenths = (this / METERS_PER_MILE * 10.0).roundToInt()
    return "${tenths / 10}.${tenths % 10} mi"
}

private const val METERS_PER_KILOMETER = 1_000
private const val FEET_PER_METER = 3.28084
private const val FEET_PER_MILE = 5_280.0
private const val METERS_PER_MILE = 1_609.344
