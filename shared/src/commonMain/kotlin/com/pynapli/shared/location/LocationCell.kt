package com.pynapli.shared.location

import kotlin.math.round

/**
 * The coarse location cell every Pynapli surface works in: a point snapped to a 0.01-degree grid
 * (~1.1 km north-south, narrower east-west away from the equator).
 *
 * This is the Kotlin twin of the server's SQL `pynapli_location_cell` (V94, pinned to explicit
 * arithmetic in V119). Both compute, per axis, `rint(value / 0.01) * 0.01 + 0.0` in IEEE doubles:
 * round to the NEAREST grid line, ties to even (`kotlin.math.round` is ties-to-even on every
 * platform, like C `rint`), and the trailing `+ 0.0` turns a negative zero into a positive one
 * exactly as PostGIS's `+ ipx` does. `LocationCellSqlParityTest` in `:server` runs this helper and
 * the SQL function over the same points (boundaries, half-way values, negatives) so the two cannot
 * drift.
 *
 * Clients snap a device fix with [snapToLocationCell] before it leaves the device; the server snaps
 * again on write (snapping is idempotent), so the guarantee never depends on the client version.
 *
 * Pure maths: no platform calls.
 */
object LocationCell {
    /** Cell size in degrees, both axes. */
    const val CELL_DEGREES: Double = 0.01

    /** One coordinate snapped to the nearest grid line, bit-for-bit as the SQL function. */
    fun snapCoordinate(value: Double): Double = round(value / CELL_DEGREES) * CELL_DEGREES + 0.0

    /**
     * The grid line index of a coordinate (the snapped value in whole hundredths of a degree). Exact:
     * the same rounding as [snapCoordinate], before the multiplication back.
     */
    fun hundredths(value: Double): Int = round(value / CELL_DEGREES).toInt()
}

/** A latitude/longitude pair that sits on the cell grid. */
data class SnappedCoordinates(
    val latitude: Double,
    val longitude: Double,
)

/** Snaps a precise fix to its cell centre; the precise values are not retained in the result. */
fun snapToLocationCell(
    latitude: Double,
    longitude: Double,
): SnappedCoordinates =
    SnappedCoordinates(
        latitude = LocationCell.snapCoordinate(latitude),
        longitude = LocationCell.snapCoordinate(longitude),
    )
