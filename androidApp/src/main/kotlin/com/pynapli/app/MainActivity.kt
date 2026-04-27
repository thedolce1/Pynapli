package com.pynapli.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.pynapli.app.location.DeviceLocation
import com.pynapli.app.media.MediaAlbumKindUi
import com.pynapli.app.media.PickedMediaForUpload
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val appGraph by lazy {
        AndroidAppGraph(applicationContext)
    }
    private val activityScope = MainScope()
    private var pendingLocationSignal: CancellationSignal? = null
    private var pendingMediaAlbumKind: MediaAlbumKindUi? = null
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.any { granted -> granted }) {
            submitCurrentLocation()
        } else {
            appGraph.locationStateHolder.markPermissionDenied()
        }
    }
    private val mediaPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        handlePickedMedia(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PynapliApp(
                authStateHolder = appGraph.authStateHolder,
                gridStateHolder = appGraph.gridStateHolder,
                profileDetailStateHolder = appGraph.profileDetailStateHolder,
                conversationStateHolder = appGraph.conversationStateHolder,
                messagesStateHolder = appGraph.messagesStateHolder,
                activityStateHolder = appGraph.activityStateHolder,
                locationStateHolder = appGraph.locationStateHolder,
                mediaAlbumsStateHolder = appGraph.mediaAlbumsStateHolder,
                onUseCurrentLocation = ::requestCurrentLocation,
                onPickMediaForAlbum = ::pickMediaForAlbum,
                devLoginCredentials = appGraph.devLoginCredentials.takeIf { isDebuggable() }
            )
        }
    }

    override fun onDestroy() {
        pendingLocationSignal?.cancel()
        pendingLocationSignal = null
        activityScope.cancel()
        super.onDestroy()
    }

    private fun pickMediaForAlbum(kind: MediaAlbumKindUi) {
        pendingMediaAlbumKind = kind
        mediaPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private fun handlePickedMedia(uri: Uri?) {
        val kind = pendingMediaAlbumKind ?: return
        pendingMediaAlbumKind = null
        if (uri == null) return

        activityScope.launch {
            val pickedMedia = readPickedImage(uri)
            if (pickedMedia == null) {
                appGraph.mediaAlbumsStateHolder.showError("Could not read the selected image.")
            } else {
                appGraph.mediaAlbumsStateHolder.uploadPickedMedia(kind, pickedMedia)
            }
        }
    }

    private fun requestCurrentLocation() {
        if (hasLocationPermission()) {
            submitCurrentLocation()
        } else {
            appGraph.locationStateHolder.beginUpdate()
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun isDebuggable(): Boolean =
        applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    @SuppressLint("MissingPermission")
    private fun submitCurrentLocation() {
        if (!hasLocationPermission()) {
            appGraph.locationStateHolder.markPermissionDenied()
            return
        }

        appGraph.locationStateHolder.beginUpdate()
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = locationManager.enabledLocationProviders()

        if (providers.isEmpty()) {
            appGraph.locationStateHolder.markUnavailable()
            return
        }

        val lastKnownLocation = providers.mapNotNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
        }.newestOrNull()

        if (lastKnownLocation != null) {
            submitDeviceLocation(lastKnownLocation)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val signal = CancellationSignal()
            pendingLocationSignal?.cancel()
            pendingLocationSignal = signal
            locationManager.getCurrentLocation(providers.first(), signal, mainExecutor) { currentLocation ->
                if (pendingLocationSignal == signal) {
                    pendingLocationSignal = null
                }
                if (currentLocation == null) {
                    appGraph.locationStateHolder.markUnavailable()
                } else {
                    submitDeviceLocation(currentLocation)
                }
            }
            return
        }

        appGraph.locationStateHolder.markUnavailable()
    }

    private fun submitDeviceLocation(currentLocation: Location) {
        activityScope.launch {
            val updated = appGraph.locationStateHolder.submitLocation(
                DeviceLocation(
                    latitude = currentLocation.latitude,
                    longitude = currentLocation.longitude,
                    accuracyMeters = currentLocation.accuracyMetersOrNull()
                )
            )
            if (updated) {
                appGraph.gridStateHolder.load()
            }
        }
    }

    private fun LocationManager.enabledLocationProviders(): List<String> =
        listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { provider ->
                runCatching { isProviderEnabled(provider) }.getOrDefault(false)
            }

    private fun Location.accuracyMetersOrNull(): Int? =
        if (hasAccuracy()) accuracy.toInt() else null

    private fun List<Location>.newestOrNull(): Location? =
        maxWithOrNull(compareBy<Location> { it.time }.thenBy { it.elapsedRealtimeNanos })

    private fun readPickedImage(uri: Uri): PickedMediaForUpload? =
        runCatching {
            val contentType = contentResolver.getType(uri)?.lowercase() ?: "image/jpeg"
            val bytes = contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes()
            } ?: return null
            val bounds = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

            PickedMediaForUpload(
                contentType = contentType,
                byteSize = bytes.size.toLong(),
                pixelWidth = bounds.outWidth.takeIf { it > 0 },
                pixelHeight = bounds.outHeight.takeIf { it > 0 },
                bytes = bytes
            )
        }.getOrNull()
}
