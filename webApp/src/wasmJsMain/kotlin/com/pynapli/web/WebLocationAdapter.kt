package com.pynapli.web

import com.pynapli.app.grid.GridStateHolder
import com.pynapli.app.location.DeviceLocation
import com.pynapli.app.location.LocationStateHolder
import com.pynapli.app.location.refreshInBackground
import com.pynapli.app.location.submitAndRefresh
import com.pynapli.web.platform.geolocationAvailable
import com.pynapli.web.platform.requestBrowserGeolocation
import com.pynapli.web.platform.whenGeolocationAlreadyGranted
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
    /**
     * `PynapliAppGraph.silentLocationRefreshAllowed`, given whether the site already holds the
     * permission. Null disables the silent refresh.
     */
    private val silentRefreshAllowed: ((permissionGranted: Boolean) -> Boolean)?,
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

    /**
     * Tab visible again (or the page just loaded signed in): only when the site already holds the
     * permission and [silentRefreshAllowed] says so (signed in, no sign-up stepper, and the last live
     * Grid answer showed the server already holds this account's location). Then a fresh fix is sent
     * silently, only if its cell changed. Never prompts, never shows progress or errors; a refused or
     * failed fix is simply dropped.
     */
    fun refreshIfAlreadyGranted() {
        if (!geolocationAvailable()) return
        val allowed = silentRefreshAllowed ?: return
        whenGeolocationAlreadyGranted {
            if (!allowed(true)) return@whenGeolocationAlreadyGranted
            requestBrowserGeolocation(
                onSuccess = { latitude, longitude, accuracyMeters ->
                    // Checked again: the fix can take seconds, and a sign-up or sign-out may have happened.
                    if (allowed(true)) {
                        scope.launch {
                            locationStateHolder.refreshInBackground(
                                DeviceLocation(
                                    latitude = latitude,
                                    longitude = longitude,
                                    accuracyMeters = accuracyMeters.toInt(),
                                ),
                                gridStateHolder,
                            )
                        }
                    }
                },
                onPermissionDenied = {},
                onUnavailable = {},
            )
        }
    }
}
