package com.pynapli.desktop

import com.pynapli.app.grid.GridStateHolder
import com.pynapli.app.location.DeviceLocation
import com.pynapli.app.location.GridLocationRefresh
import com.pynapli.app.location.LocationStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Stands in for CoreLocation / FusedLocationProvider / the browser Geolocation API.
 *
 * A desktop JVM has no location provider worth using, so this submits a FIXED coordinate rather
 * than pretending to sense one. It defaults to the same point `scripts/seed-dev-data.ps1` gives the
 * dev viewer (37.4220, -122.0841) so the Grid has nearby cards on first run instead of an empty
 * state that looks like a bug — the desktop equivalent of `-SetAndroidEmulatorLocation`.
 *
 * Override with `-Dpynapli.latitude=` / `-Dpynapli.longitude=` to exercise distance-dependent
 * surfaces (Playing Tonight zones, travel mode) from somewhere else.
 *
 * It still runs the real [LocationStateHolder] path — same submit, same movement-thresholded grid
 * refresh — so what is faked is the sensor, not the state machine the UI reacts to.
 */
class DesktopLocationAdapter(
    private val locationStateHolder: LocationStateHolder,
    private val gridStateHolder: GridStateHolder,
    private val scope: CoroutineScope,
) {
    fun requestCurrentLocation() {
        locationStateHolder.beginUpdate()
        scope.launch {
            val result =
                locationStateHolder.submitLocation(
                    DeviceLocation(
                        latitude = readCoordinate("pynapli.latitude", DEFAULT_LATITUDE),
                        longitude = readCoordinate("pynapli.longitude", DEFAULT_LONGITUDE),
                        accuracyMeters = FIXED_ACCURACY_METERS,
                    ),
                )
            when (result.gridRefresh) {
                GridLocationRefresh.None -> Unit
                GridLocationRefresh.QuietRefresh -> gridStateHolder.load(quiet = true)
                GridLocationRefresh.Refresh -> gridStateHolder.load()
            }
        }
    }

    private fun readCoordinate(
        property: String,
        fallback: Double,
    ): Double = System.getProperty(property)?.trim()?.toDoubleOrNull() ?: fallback

    private companion object {
        // Matches $viewerLatitude / $viewerLongitude in scripts/seed-dev-data.ps1.
        private const val DEFAULT_LATITUDE = 37.4220
        private const val DEFAULT_LONGITUDE = -122.0841

        // A plausible consumer-GPS accuracy. Not 0: the server's location-spoofing signal treats
        // impossibly precise fixes as suspicious, and this shell should not look like an attacker.
        private const val FIXED_ACCURACY_METERS = 20
    }
}
