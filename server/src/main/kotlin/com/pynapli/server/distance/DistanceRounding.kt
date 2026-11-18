package com.pynapli.server.distance

import com.pynapli.shared.distance.formatDistanceForUnit
import com.pynapli.shared.settings.AppSettings
import kotlin.math.floor

/**
 * Public distance bucket. Callers must pass a COARSE distance (cell to cell, see
 * [CoarseLocation]) — never a raw GPS distance — so the reading cannot move when either side
 * shifts a few metres. Mirrors SQL `pynapli_public_distance_m` (V94) exactly:
 * under 2 km reads "1 km"; under 10 km whole km; under 50 km 5 km steps; beyond that 10 km steps.
 */
fun Double.toPublicDistanceMeters(): Int =
    when {
        this < 2_000.0 -> 1_000
        this < 10_000.0 -> (floor(this / 1_000.0) * 1_000).toInt()
        this < 50_000.0 -> (floor(this / 5_000.0) * 5_000).toInt()
        else -> (floor(this / 10_000.0) * 10_000).toInt()
    }

fun Double.toPublicDistanceLabel(): String =
    formatDistanceForUnit(
        distanceMeters = toPublicDistanceMeters(),
        fallbackLabel = null,
        distanceUnit = AppSettings.UNIT_METRIC,
    ).orEmpty()

/**
 * Coarse-location rules shared by every discovery surface (Grid, Swipe deck ordering, Playing
 * Tonight, profile peek). Every observable distance, ordering key and radius membership is a
 * function of (viewer cell, target cell) only.
 */
object CoarseLocation {
    /** Cell size in degrees (both axes). 0.01 deg is ~1.1 km north-south. */
    const val CELL_DEGREES: Double = 0.01

    /**
     * Upper bound on |cell distance - raw distance| for two points: each point is at most half a
     * cell diagonal (~786 m at the equator) from its cell centre. Raw-geom index pre-filters pad the
     * radius by this so the exact cell-to-cell test never misses a candidate.
     */
    const val CELL_PAD_METERS: Double = 1_600.0

    /**
     * Discovery radius steps (L-09). A requested radius is rounded UP to the next step, so moving a
     * radius slider metre by metre can never be used to binary-search someone's distance: the only
     * membership tests an attacker can run are these six circles, and membership itself is a
     * cell-to-cell test.
     */
    val RADIUS_STEPS_METERS: List<Int> = listOf(2_000, 5_000, 10_000, 25_000, 50_000, 100_000)

    fun snapRadiusMeters(requestedMeters: Int): Int = RADIUS_STEPS_METERS.firstOrNull { it >= requestedMeters } ?: RADIUS_STEPS_METERS.last()

    /**
     * Cache / ordering key of a viewer cell: the snapped cell centre in hundredths of a degree. Built
     * from the SQL-snapped coordinates (already multiples of [CELL_DEGREES]) so it is exact.
     */
    fun cellKey(
        snappedLongitude: Double,
        snappedLatitude: Double,
    ): Pair<Int, Int> = Math.round(snappedLongitude / CELL_DEGREES).toInt() to Math.round(snappedLatitude / CELL_DEGREES).toInt()

    /** SQL: the cell of a geography expression. */
    fun cellSql(geography: String): String = "pynapli_location_cell($geography)"

    /** SQL: cell-to-cell distance in metres between two geography expressions. */
    fun coarseDistanceSql(
        a: String,
        b: String,
    ): String = "pynapli_coarse_distance_m($a, $b)"
}
