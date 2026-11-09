package com.pynapli.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.edit
import com.pynapli.app.location.DeviceLocation
import com.pynapli.app.location.GridLocationRefresh
import com.pynapli.app.media.MediaAlbumKindUi
import com.pynapli.app.media.PickedMediaForUpload
import com.pynapli.app.notifications.PynapliNotificationDeepLinks
import com.pynapli.shared.core.result.runCatchingCancellable
import com.pynapli.shared.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    // The DI graph (SQLDelight open + 5 Ktor clients + ~25 state holders) is built off the main
    // thread in onCreate so it no longer blocks the first frame; until it is ready a cheap boot
    // splash draws immediately, which lets the system (icon) splash dismiss right away. Stays null
    // until ready; [appGraphState] drives the composition swap, [appGraph] is the activity-side ref.
    private var appGraph: AndroidAppGraph? = null
    private val appGraphState = mutableStateOf<AndroidAppGraph?>(null)
    private val activityScope = MainScope()
    private var isStarted = false
    private var pendingDeepLinkIntent: Intent? = null
    private var pendingLocationSignal: CancellationSignal? = null
    private var presenceStreamJob: Job? = null
    private var activityStreamJob: Job? = null
    private var messageStreamJob: Job? = null
    private var pendingMediaAlbumKind: MediaAlbumKindUi? = null
    private var pendingConversationPhoto: Boolean = false
    private val locationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { permissions ->
            if (permissions.values.any { granted -> granted }) {
                submitCurrentLocation()
            } else {
                appGraph?.locationStateHolder?.markPermissionDenied()
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
        // Edge-to-edge on EVERY API level, not just the ones we happen to test on.
        //
        // targetSdk 36 means Android 15+ (API 35+) draws this app edge-to-edge automatically and the
        // Android 16 opt-out is ignored, so the Compose UI is written for it — statusBarsPadding()
        // and navigationBarsPadding() across ~15 screens. But per Android's edge-to-edge guide, on
        // "Android 14 (API level 34) and lower, your app's UI does not draw underneath the system
        // bars and display cutouts by default"; that only happens if enableEdgeToEdge() asks for it.
        // minSdk is 23, so without this call the whole API 23-34 range renders differently from the
        // range we ship against — and every android-*-qa.ps1 script runs on an android-36 AVD, which
        // is precisely the one API level where the difference cannot show up.
        //
        // Forced dark rather than the default auto(): SystemBarStyle.auto picks system-bar icon
        // colours from the SYSTEM theme, but Pynapli is dark-first and unconditionally paints
        // #0B0B0E behind them. On a device in light mode auto() would choose dark icons over a
        // near-black background. This matches the intent already stated by windowLightStatusBar
        // = false in styles.xml.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        // Build the DI graph off the main thread so onCreate doesn't block the first frame on
        // SQLDelight open + Ktor client creation + ~25 state holders. The boot splash shows until
        // the graph is ready, then the real app swaps in; any cold-launch deep link is replayed.
        pendingDeepLinkIntent = intent
        activityScope.launch {
            val graph = withContext(Dispatchers.Default) { AndroidAppGraph(applicationContext) }
            onAppGraphReady(graph)
        }
        setContent {
            when (val graph = appGraphState.value) {
                null -> PynapliBootSplash()
                else ->
                    PynapliApp(
                        graph =
                            PynapliAppGraph(
                                authStateHolder = graph.authStateHolder,
                                appSettingsStateHolder = graph.appSettingsStateHolder,
                                gridStateHolder = graph.gridStateHolder,
                                profileDetailStateHolder = graph.profileDetailStateHolder,
                                conversationStateHolder = graph.conversationStateHolder,
                                messagesStateHolder = graph.messagesStateHolder,
                                activityStateHolder = graph.activityStateHolder,
                                locationStateHolder = graph.locationStateHolder,
                                mediaAlbumsStateHolder = graph.mediaAlbumsStateHolder,
                                profileHubStateHolder = graph.profileHubStateHolder,
                                profileLinksStateHolder = graph.profileLinksStateHolder,
                                swipeStateHolder = graph.swipeStateHolder,
                                storeStateHolder = graph.storeStateHolder,
                                savedGridFilterStateHolder = graph.savedGridFilterStateHolder,
                                savedSwipeFilterStateHolder = graph.savedSwipeFilterStateHolder,
                                travelLocationStateHolder = graph.travelLocationStateHolder,
                                tonightAvailabilityStateHolder = graph.tonightAvailabilityStateHolder,
                                playingTonightStateHolder = graph.playingTonightStateHolder,
                                psyncRepository = graph.psyncRepository,
                                psyncStreamClient = graph.psyncStreamClient,
                                deepLinkStateHolder = graph.deepLinkStateHolder,
                                onboardingStateHolder = graph.onboardingStateHolder,
                            ),
                        platform =
                            PynapliPlatform(
                                onUseCurrentLocation = ::requestCurrentLocation,
                                onPickMediaForAlbum = ::pickMediaForAlbum,
                                onPickConversationPhoto = ::pickConversationPhoto,
                                onSetDiscreetIcon = ::applyLauncherIcon,
                                devLoginCredentials = graph.devLoginCredentials,
                                testerDiagnosticLabel = graph.testerDiagnosticLabel,
                            ),
                    )
            }
        }
    }

    private fun onAppGraphReady(graph: AndroidAppGraph) {
        appGraph = graph
        graph.attachBillingActivity(this)
        startPushRegistrationSync(graph)
        startNotificationPermissionPromptSync(graph)
        pendingDeepLinkIntent?.let { handleNotificationDeepLink(it, graph) }
        pendingDeepLinkIntent = null
        if (isStarted) startRealtimeStreams(graph)
        // Swap the boot splash for the real app last, once the graph's collaborators are wired.
        appGraphState.value = graph
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val graph = appGraph
        if (graph != null) {
            handleNotificationDeepLink(intent, graph)
        } else {
            pendingDeepLinkIntent = intent
        }
    }

    override fun onStart() {
        super.onStart()
        isStarted = true
        appGraph?.let { startRealtimeStreams(it) }
        // Re-sync push registration on every foreground. The reactive collector in
        // startPushRegistrationSync only re-fires when userId/settings change, so a rotated FCM token
        // (onNewToken clears the stored fingerprint) would otherwise not be re-registered until the
        // process restarts — leaving the server holding a dead token and dropping pushes to a user
        // who has not reopened the app. The sync is fingerprint-guarded, so it is a cheap no-op when
        // nothing has changed.
        activityScope.launch { syncPushRegistrationNow() }
    }

    // runCatchingCancellable, not runCatching: onStop() cancels all three of these jobs, and plain
    // runCatching catches the resulting CancellationException and returns normally — swallowing the
    // very signal that is supposed to unwind the coroutine. These are the sites where it matters
    // most (an actual lifecycle cancellation, twice per app switch), and they were the ones the
    // original sweep missed.
    private fun startRealtimeStreams(graph: AndroidAppGraph) {
        if (presenceStreamJob?.isActive != true) {
            presenceStreamJob =
                activityScope.launch {
                    runCatchingCancellable { graph.gridPresenceStreamCoordinator.run() }
                }
        }
        if (activityStreamJob?.isActive != true) {
            activityStreamJob =
                activityScope.launch {
                    runCatchingCancellable { graph.activityRealtimeCoordinator.run() }
                }
        }
        if (messageStreamJob?.isActive != true) {
            messageStreamJob =
                activityScope.launch {
                    runCatchingCancellable { graph.messagesRealtimeCoordinator.run() }
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
        appGraph?.detachBillingActivity(this)
        activityScope.cancel()
        super.onDestroy()
    }

    private fun startPushRegistrationSync(graph: AndroidAppGraph) {
        activityScope.launch {
            combine(
                graph.authStateHolder.state,
                graph.appSettingsStateHolder.state,
            ) { authState, appSettingsState ->
                PushRegistrationSyncInput(
                    userId = authState.session?.userId,
                    settings = appSettingsState.settings,
                )
            }.distinctUntilChanged()
                .collectLatest { input ->
                    graph.pushRegistrationCoordinator.syncAuthenticatedDevice(
                        userId = input.userId,
                        settings = input.settings,
                    )
                }
        }
    }

    private fun startNotificationPermissionPromptSync(graph: AndroidAppGraph) {
        activityScope.launch {
            combine(
                graph.authStateHolder.state,
                graph.appSettingsStateHolder.state,
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

        preferences.edit { putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true) }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private suspend fun syncPushRegistrationNow() {
        val graph = appGraph ?: return
        val authState = graph.authStateHolder.state.value
        val settings = graph.appSettingsStateHolder.state.value.settings
        graph.pushRegistrationCoordinator.syncAuthenticatedDevice(
            userId = authState.session?.userId,
            settings = settings,
        )
    }

    private fun handleNotificationDeepLink(
        intent: Intent?,
        graph: AndroidAppGraph,
    ) {
        if (intent == null) return
        when (PynapliNotificationDeepLinks.targetFromIntent(intent)) {
            AppDeepLinkStateHolder.TARGET_ACTIVITY -> {
                graph.deepLinkStateHolder.openActivity(
                    tab = PynapliNotificationDeepLinks.activityTabFromIntent(intent),
                    eventId = PynapliNotificationDeepLinks.activityEventIdFromIntent(intent),
                )
            }

            AppDeepLinkStateHolder.TARGET_MESSAGES -> {
                graph.deepLinkStateHolder.openMessages(
                    conversationId = PynapliNotificationDeepLinks.conversationIdFromIntent(intent),
                )
            }
        }
    }

    private fun pickMediaForAlbum(kind: MediaAlbumKindUi) {
        pendingMediaAlbumKind = kind
        pendingConversationPhoto = false
        mediaPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }

    private fun pickConversationPhoto() {
        pendingMediaAlbumKind = null
        pendingConversationPhoto = true
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
        val graph = appGraph ?: return
        val sendToConversation = pendingConversationPhoto
        val kind = pendingMediaAlbumKind
        pendingConversationPhoto = false
        pendingMediaAlbumKind = null
        if (uri == null) return

        activityScope.launch {
            // Off Main: Photo Picker URIs can be cloud-backed, so the read can block long enough to ANR.
            val pickedMedia = withContext(Dispatchers.IO) { readPickedImage(uri) }
            when {
                pickedMedia == null -> graph.mediaAlbumsStateHolder.showError("Could not read the selected image.")
                // Chat photo: upload + send as a per-recipient consent-tap media message.
                sendToConversation -> graph.conversationStateHolder.sendPhoto(pickedMedia)
                // Profile and private album photos both pause at the shared square-crop step.
                kind != null -> graph.mediaAlbumsStateHolder.stagePickedMedia(kind, pickedMedia)
            }
        }
    }

    private fun requestCurrentLocation() {
        if (hasLocationPermission()) {
            submitCurrentLocation()
        } else {
            appGraph?.locationStateHolder?.beginUpdate()
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

    @SuppressLint("MissingPermission")
    private fun submitCurrentLocation() {
        val locationStateHolder = appGraph?.locationStateHolder ?: return
        if (!hasLocationPermission()) {
            locationStateHolder.markPermissionDenied()
            return
        }

        locationStateHolder.beginUpdate()
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = locationManager.enabledLocationProviders()

        if (providers.isEmpty()) {
            locationStateHolder.markUnavailable()
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
                    locationStateHolder.markUnavailable()
                } else {
                    submitDeviceLocation(currentLocation)
                }
            }
            return
        }

        locationStateHolder.markUnavailable()
    }

    private fun submitDeviceLocation(currentLocation: Location) {
        val graph = appGraph ?: return
        activityScope.launch {
            val result =
                graph.locationStateHolder.submitLocation(
                    DeviceLocation(
                        latitude = currentLocation.latitude,
                        longitude = currentLocation.longitude,
                        accuracyMeters = currentLocation.accuracyMetersOrNull(),
                    ),
                )
            when (result.gridRefresh) {
                GridLocationRefresh.None -> Unit
                GridLocationRefresh.QuietRefresh -> graph.gridStateHolder.load(quiet = true)
                GridLocationRefresh.Refresh -> graph.gridStateHolder.load()
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
