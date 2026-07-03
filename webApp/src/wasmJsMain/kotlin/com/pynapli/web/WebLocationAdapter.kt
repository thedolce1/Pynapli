package com.pynapli.web

import com.pynapli.app.grid.GridStateHolder
import com.pynapli.app.location.DeviceLocation
import com.pynapli.app.location.GridLocationRefresh
import com.pynapli.app.location.LocationStateHolder
import com.pynapli.web.platform.geolocationAvailable
import com.pynapli.web.platform.requestBrowserGeolocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Browser Geolocation adapter (WEB_PWA_GAMEPLAN.md W2.3): the web twin of
 * `MainActivity.requestCurrentLocation` — one-shot fix on explicit user action,
 * same `LocationStateHolder` state machine (denied/unavailable copy included),
 * same movement-thresholded grid refresh. The browser shows its own permission
 * prompt; nothing here stores raw coordinates client-side.
 */
class WebLocationAdapter(
    private val locationStateHolder: LocationStateHolder,
    private val gridStateHolder: GridStateHolder,
    private val scope: CoroutineScope,
) {
    fun requestCurrentLocation() {
        if (!geolocationAvailable()) {
            locationStateHolder.markUnavailable()
            return
        }
        locationStateHolder.beginUpdate()
        requestBrowserGeolocation(
            onSuccess = { latitude, longitude, accuracyMeters ->
                scope.launch {
                    val result =
                        locationStateHolder.submitLocation(
                            DeviceLocation(
                                latitude = latitude,
                                longitude = longitude,
                                accuracyMeters = accuracyMeters.toInt(),
                            ),
                        )
                    when (result.gridRefresh) {
                        GridLocationRefresh.None -> Unit
                        GridLocationRefresh.QuietRefresh -> gridStateHolder.load(quiet = true)
                        GridLocationRefresh.Refresh -> gridStateHolder.load()
                    }
                }
            },
            onPermissionDenied = { locationStateHolder.markPermissionDenied() },
            onUnavailable = { locationStateHolder.markUnavailable() },
        )
    }
}
