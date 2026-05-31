package com.pynapli.server.distance

import com.pynapli.shared.distance.formatDistanceForUnit
import com.pynapli.shared.settings.AppSettings

fun Double.toPublicDistanceMeters(): Int {
    if (this < 1_000.0) {
        return ((this / 100.0).toInt() * 100).coerceAtLeast(100)
    }

    if (this < 10_000.0) {
        return ((this / 100.0).toInt() * 100).coerceAtLeast(1_000)
    }

    return ((this / 1_000.0).toInt() * 1_000).coerceAtLeast(10_000)
}

fun Double.toPublicDistanceLabel(): String =
    formatDistanceForUnit(
        distanceMeters = toPublicDistanceMeters(),
        fallbackLabel = null,
        distanceUnit = AppSettings.UNIT_METRIC,
    ).orEmpty()
