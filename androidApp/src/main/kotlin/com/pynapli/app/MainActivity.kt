package com.pynapli.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.pynapli.app.location.DeviceLocation
import com.pynapli.app.location.GridLocationRefresh
import com.pynapli.app.media.MediaAlbumKindUi
import com.pynapli.app.media.PickedMediaForUpload
import com.pynapli.app.notifications.PynapliNotificationDeepLinks
import com.pynapli.shared.settings.AppSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val appGraph by lazy {
        AndroidAppGraph(applicationContext)
    }
    private val activityScope = MainScope()
    private var pendingLocationSignal: CancellationSignal? = null
    private var presenceStreamJob: Job? = null
    private var activityStreamJob: Job? = null
    private var messageStreamJob: Job? = null
    private var pendingMediaAlbumKind: MediaAlbumKindUi? = null
    private val locationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { permissions ->
            if (permissions.values.any { granted -> granted }) {
                submitCurrentLocation()
            } else {
                appGraph.locationStateHolder.markPermissionDenied()
            }
        }
    private val mediaPickerLauncher =
        registerForActivityResult(
            ActivityResultContracts.PickVisualMedia(),
        ) { uri ->
            handlePickedMedia(uri)
        }
    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) {
            activityScope.launch {
                syncPushRegistrationNow()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appGraph.attachBillingActivity(this)
        startPushRegistrationSync()
        startNotificationPermissionPromptSync()
        setContent {
            PynapliApp(
                authStateHolder = appGraph.authStateHolder,
                appSettingsStateHolder = appGraph.appSettingsStateHolder,
                gridStateHolder = appGraph.gridStateHolder,
                profileDetailStateHolder = appGraph.profileDetailStateHolder,
                conversationStateHolder = appGraph.conversationStateHolder,
                messagesStateHolder = appGraph.messagesStateHolder,
                activityStateHolder = appGraph.activityStateHolder,
                locationStateHolder = appGraph.locationStateHolder,
                mediaAlbumsStateHolder = appGraph.mediaAlbumsStateHolder,
                profileHubStateHolder = appGraph.profileHubStateHolder,
                profileLinksStateHolder = appGraph.profileLinksStateHolder,
                swipeStateHolder = appGraph.swipeStateHolder,
                storeStateHolder = appGraph.storeStateHolder,
                savedGridFilterStateHolder = appGraph.savedGridFilterStateHolder,
                savedSwipeFilterStateHolder = appGraph.savedSwipeFilterStateHolder,
                topPicksStateHolder = appGraph.topPicksStateHolder,
                travelLocationStateHolder = appGraph.travelLocationStateHolder,
                tonightAvailabilityStateHolder = appGraph.tonightAvailabilityStateHolder,
                deepLinkStateHolder = appGraph.deepLinkStateHolder,
                onUseCurrentLocation = ::requestCurrentLocation,
                onPickMediaForAlbum = ::pickMediaForAlbum,
                onSetDiscreetIcon = ::applyLauncherIcon,
                devLoginCredentials = appGraph.devLoginCredentials.takeIf { isDebuggable() },
            )
        }
        handleNotificationDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationDeepLink(intent)
    }

    override fun onStart() {
        super.onStart()
        if (presenceStreamJob?.isActive != true) {
            presenceStreamJob =
                activityScope.launch {
                    runCatching { appGraph.gridPresenceStreamCoordinator.run() }
                }
        }
        if (activityStreamJob?.isActive != true) {
            activityStreamJob =
                activityScope.launch {
                    runCatching { appGraph.activityRealtimeCoordinator.run() }
                }
        }
        if (messageStreamJob?.isActive != true) {
            messageStreamJob =
                activityScope.launch {
                    runCatching { appGraph.messagesRealtimeCoordinator.run() }
                }
        }
    }

    override fun onStop() {
        pendingLocationSignal?.cancel()
        pendingLocationSignal = null
        presenceStreamJob?.cancel()
        presenceStreamJob = null
        activityStreamJob?.cancel()
        activityStreamJob = null
        messageStreamJob?.cancel()
        messageStreamJob = null
        super.onStop()
    }

    override fun onDestroy() {
        pendingLocationSignal?.cancel()
        pendingLocationSignal = null
        appGraph.detachBillingActivity(this)
        activityScope.cancel()
        super.onDestroy()
    }

    private fun startPushRegistrationSync() {
        activityScope.launch {
            combine(
                appGraph.authStateHolder.state,
                appGraph.appSettingsStateHolder.state,
            ) { authState, appSettingsState ->
                PushRegistrationSyncInput(
                    userId = authState.session?.userId,
                    settings = appSettingsState.settings,
                )
            }.distinctUntilChanged()
                .collectLatest { input ->
                    appGraph.pushRegistrationCoordinator.syncAuthenticatedDevice(
                        userId = input.userId,
                        settings = input.settings,
                    )
                }
        }
    }

    private fun startNotificationPermissionPromptSync() {
        activityScope.launch {
            combine(
                appGraph.authStateHolder.state,
                appGraph.appSettingsStateHolder.state,
            ) { authState, appSettingsState ->
                authState.authenticated && appSettingsState.settings.pushNotificationsEnabled()
            }.distinctUntilChanged()
                .collectLatest { shouldPrompt ->
                    if (shouldPrompt) {
                        requestNotificationPermissionIfUseful()
                    }
                }
        }
    }

    private fun requestNotificationPermissionIfUseful() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val preferences = getSharedPreferences(NOTIFICATION_PERMISSION_PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)) return

        preferences.edit().putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true).apply()
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private suspend fun syncPushRegistrationNow() {
        val authState = appGraph.authStateHolder.state.value
        val settings = appGraph.appSettingsStateHolder.state.value.settings
        appGraph.pushRegistrationCoordinator.syncAuthenticatedDevice(
            userId = authState.session?.userId,
            settings = settings,
        )
    }

    private fun handleNotificationDeepLink(intent: Intent?) {
        if (intent == null) return
        when (PynapliNotificationDeepLinks.targetFromIntent(intent)) {
            AppDeepLinkStateHolder.TARGET_ACTIVITY -> {
                appGraph.deepLinkStateHolder.openActivity(
                    tab = PynapliNotificationDeepLinks.activityTabFromIntent(intent),
                    eventId = PynapliNotificationDeepLinks.activityEventIdFromIntent(intent),
                )
            }

            AppDeepLinkStateHolder.TARGET_MESSAGES -> {
                appGraph.deepLinkStateHolder.openMessages(
                    conversationId = PynapliNotificationDeepLinks.conversationIdFromIntent(intent),
                )
            }
        }
    }

    private fun pickMediaForAlbum(kind: MediaAlbumKindUi) {
        pendingMediaAlbumKind = kind
        mediaPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }

    private fun applyLauncherIcon(discreet: Boolean) {
        runCatching {
            val defaultAlias = ComponentName(this, "$packageName.DefaultLauncherAlias")
            val discreetAlias = ComponentName(this, "$packageName.DiscreetLauncherAlias")
            // Enable the target alias FIRST, then disable the other, so exactly one
            // launcher alias is enabled at all times and the icon never disappears.
            val enabledAlias = if (discreet) discreetAlias else defaultAlias
            val disabledAlias = if (discreet) defaultAlias else discreetAlias
            packageManager.setComponentEnabledSetting(
                enabledAlias,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            packageManager.setComponentEnabledSetting(
                disabledAlias,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to apply discreet launcher icon (discreet=$discreet)", error)
        }
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
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun isDebuggable(): Boolean = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

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

        val lastKnownLocation =
            providers
                .mapNotNull { provider ->
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
            val result =
                appGraph.locationStateHolder.submitLocation(
                    DeviceLocation(
                        latitude = currentLocation.latitude,
                        longitude = currentLocation.longitude,
                        accuracyMeters = currentLocation.accuracyMetersOrNull(),
                    ),
                )
            when (result.gridRefresh) {
                GridLocationRefresh.None -> Unit
                GridLocationRefresh.QuietRefresh -> appGraph.gridStateHolder.load(quiet = true)
                GridLocationRefresh.Refresh -> appGraph.gridStateHolder.load()
            }
        }
    }

    private fun LocationManager.enabledLocationProviders(): List<String> =
        listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { provider ->
                runCatching { isProviderEnabled(provider) }.getOrDefault(false)
            }

    private fun Location.accuracyMetersOrNull(): Int? = if (hasAccuracy()) accuracy.toInt() else null

    private fun List<Location>.newestOrNull(): Location? = maxWithOrNull(compareBy<Location> { it.time }.thenBy { it.elapsedRealtimeNanos })

    private fun readPickedImage(uri: Uri): PickedMediaForUpload? =
        runCatching {
            val contentType = contentResolver.getType(uri)?.lowercase() ?: "image/jpeg"
            val bytes =
                contentResolver.openInputStream(uri)?.use { input ->
                    input.readBytes()
                } ?: return null
            val bounds =
                BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

            PickedMediaForUpload(
                contentType = contentType,
                byteSize = bytes.size.toLong(),
                pixelWidth = bounds.outWidth.takeIf { it > 0 },
                pixelHeight = bounds.outHeight.takeIf { it > 0 },
                bytes = bytes,
            )
        }.getOrNull()
}

private data class PushRegistrationSyncInput(
    val userId: String?,
    val settings: AppSettings,
)

private fun AppSettings.pushNotificationsEnabled(): Boolean = messageNotifications || matchNotifications || activityNotifications

private const val NOTIFICATION_PERMISSION_PREFERENCES = "pynapli_notification_permission"
private const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "requested"
private const val TAG = "MainActivity"
