package com.pynapli.web

import com.pynapli.app.grid.GridStateHolder
import com.pynapli.app.location.DeviceLocation
import com.pynapli.app.location.LocationStateHolder
import com.pynapli.app.location.submitAndRefresh
import com.pynapli.web.platform.geolocationAvailable
import com.pynapli.web.platform.requestBrowserGeolocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Browser Geolocation adapter (WEB_PWA_GAMEPLAN.md W2.3): the web twin of
 * `MainActivity.requestCurrentLocation` — one-shot fix on explicit user action,
 * same `LocationStateHolder` state machine (denied/unavailable copy included),
 * same movement-thresholded grid refresh. The browser shows its own permission
 * prompt; nothing here stores raw coordinates client-side, and the state holder sends only the
 * fix's 0.01-degree cell (no accuracy) to the server.
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
                    locationStateHolder.submitAndRefresh(
                        DeviceLocation(
                            latitude = latitude,
                            longitude = longitude,
                            accuracyMeters = accuracyMeters.toInt(),
                        ),
                        gridStateHolder,
                    )
                }
            },
            // A browser remembers a refusal for the site and will not prompt again from a click, so
            // the screen explains where its switch is (LocationSettingsAccess.blockedHint).
            onPermissionDenied = { locationStateHolder.markPermissionDenied(permanently = true) },
            onUnavailable = { locationStateHolder.markUnavailable() },
        )
    }
}
