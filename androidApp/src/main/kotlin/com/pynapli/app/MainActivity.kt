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
import android.os.SystemClock
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
import com.pynapli.app.location.LocationSettingsAccess
import com.pynapli.app.location.LocationSharingGate
import com.pynapli.app.location.ProvideLocationSettingsAccess
import com.pynapli.app.location.refreshInBackground
import com.pynapli.app.location.shareAndRefresh
import com.pynapli.app.media.MediaAlbumKindUi
import com.pynapli.app.media.MediaPickTarget
import com.pynapli.app.media.PickedMediaForUpload
import com.pynapli.app.notifications.PynapliNotificationDeepLinks
import com.pynapli.shared.core.result.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    private var realtimeJobs: List<Job> = emptyList()
    private var pendingPickTarget: MediaPickTarget? = null
    private val locationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { permissions ->
            if (permissions.values.any { granted -> granted }) {
                submitCurrentLocation()
            } else {
                // Read after the dialog: no rationale to show for either permission now means Android
                // will not ask again ("Don't allow" twice, or the dialog was skipped entirely because
                // it already refused), so the screen offers Settings instead of a dead button. It is
                // also false before the first ask, which is why it is never read up front.
                val blockedForGood =
                    !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) &&
                        !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)
                appGraph?.pynapliAppGraph?.locationStateHolder?.markPermissionDenied(permanently = blockedForGood)
            }
        }
    private val locationSettingsAccess by lazy {
        LocationSettingsAccess(opener = AndroidLocationSettingsOpener(this))
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
            activityScope.launch { appGraph?.pushRegistrationSync?.syncNow() }
            // A purchase that completed while the app was in the background is confirmed on return.
            activityScope.launch { appGraph?.syncUnfinishedPurchases() }
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
                    ProvideLocationSettingsAccess(locationSettingsAccess) {
                        PynapliApp(
                            graph = graph.pynapliAppGraph,
                            platform =
                                PynapliPlatform(
                                    onUseCurrentLocation = ::requestCurrentLocation,
                                    onPickMediaForAlbum = ::pickMediaForAlbum,
                                    onPickConversationPhoto = ::pickConversationPhoto,
                                    onPickVerificationSelfie = ::pickVerificationSelfie,
                                    onSetDiscreetIcon = ::applyLauncherIcon,
                                    devLoginCredentials = graph.devLoginCredentials,
                                    testerDiagnosticLabel = graph.testerDiagnosticLabel,
                                ),
                        )
                    }
            }
        }
    }

    private fun onAppGraphReady(graph: AndroidAppGraph) {
        appGraph = graph
        graph.attachBillingActivity(this)
        activityScope.launch { graph.pushRegistrationSync.followSessionAndSettings() }
        // The notification ask waits for the first Grid (a short dwell) or the first conversation,
        // so it never lands on the sign-up Location step; Grid time counts only while started.
        graph.appInForeground.value = isStarted
        activityScope.launch { graph.notificationAskTiming.run() }
        activityScope.launch {
            graph.pushRegistrationSync.notificationPermissionWanted.collectLatest { wanted ->
                if (wanted) requestNotificationPermissionIfUseful()
            }
        }
        pendingDeepLinkIntent?.let { handleNotificationDeepLink(it, graph) }
        pendingDeepLinkIntent = null
        if (isStarted) startRealtimeStreams(graph)
        // Cold start counts as a return: onStart ran before the graph existed, so once the stored
        // session has settled, send a fresh fix the same silent way (only if it changed cell). Waits
        // (bounded) for the first live Grid answer, which is what tells a member who shared before
        // this device kept a record apart from one who never did (LocationSharingGate rule (b)).
        activityScope.launch {
            val settled = graph.authStateHolder.state.first { !it.isCheckingSession }
            if (!settled.authenticated) return@launch
            withTimeoutOrNull(COLD_START_GRID_ANSWER_WAIT_MILLIS) {
                graph.pynapliAppGraph.gridStateHolder.serverHasLocation
                    .first { it != null }
            }
            refreshLocationOnResume()
        }
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
        appGraph?.appInForeground?.value = true
        appGraph?.let { startRealtimeStreams(it) }
        // Re-sync push on every foreground so a rotated FCM token is re-registered (see
        // PushRegistrationSync.syncNow); fingerprint-guarded, so a no-op when nothing changed.
        activityScope.launch { appGraph?.pushRegistrationSync?.syncNow() }
        // The sockets were closed while stopped, so nothing that happened meanwhile arrived:
        // refetch the inbox, open chat, Activity, links, hub and an open Tonight feed.
        activityScope.launch { appGraph?.onAppForegrounded() }
        // Moved while away? A fresh fix, sent only when its cell changed (also how coming back from
        // the Settings screen the Location step opened picks up a newly granted permission).
        refreshLocationOnResume()
    }

    // runCatchingCancellable, not runCatching: onStop() cancels all three of these jobs, and plain
    // runCatching catches the resulting CancellationException and returns normally — swallowing the
    // very signal that is supposed to unwind the coroutine. These are the sites where it matters
    // most (an actual lifecycle cancellation, twice per app switch), and they were the ones the
    // original sweep missed.
    private fun startRealtimeStreams(graph: AndroidAppGraph) {
        // Already running: keep the live sockets. If any stream stopped, restart the set.
        if (realtimeJobs.isNotEmpty() && realtimeJobs.all { it.isActive }) return
        realtimeJobs.forEach { it.cancel() }
        realtimeJobs = graph.startRealtime(activityScope)
    }

    override fun onStop() {
        isStarted = false
        appGraph?.appInForeground?.value = false
        pendingLocationSignal?.cancel()
        pendingLocationSignal = null
        realtimeJobs.forEach { it.cancel() }
        realtimeJobs = emptyList()
        super.onStop()
    }

    override fun onDestroy() {
        pendingLocationSignal?.cancel()
        pendingLocationSignal = null
        appGraph?.detachBillingActivity(this)
        activityScope.cancel()
        super.onDestroy()
    }

    private fun requestNotificationPermissionIfUseful() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val preferences = getSharedPreferences(NOTIFICATION_PERMISSION_PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)) return

        preferences.edit { putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true) }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
                    openActorUserId = PynapliNotificationDeepLinks.actorProfileToOpen(intent),
                )
            }

            AppDeepLinkStateHolder.TARGET_MESSAGES -> {
                graph.deepLinkStateHolder.openMessages(
                    conversationId = PynapliNotificationDeepLinks.conversationIdFromIntent(intent),
                )
            }

            // Partner invites open the screen where they are answered, not the Activity list.
            AppDeepLinkStateHolder.TARGET_TONIGHT -> graph.deepLinkStateHolder.openTonight()

            AppDeepLinkStateHolder.TARGET_PSYNC -> graph.deepLinkStateHolder.openPsync()

            AppDeepLinkStateHolder.TARGET_PARTNER -> graph.deepLinkStateHolder.openPartner()
        }
    }

    private fun pickMediaForAlbum(kind: MediaAlbumKindUi) = launchPhotoPicker(MediaPickTarget.Album(kind))

    private fun pickConversationPhoto() = launchPhotoPicker(MediaPickTarget.Conversation)

    // The existing system photo picker (no camera library): the person picks the selfie they just took.
    private fun pickVerificationSelfie() = launchPhotoPicker(MediaPickTarget.VerificationSelfie)

    private fun launchPhotoPicker(target: MediaPickTarget) {
        pendingPickTarget = target
        mediaPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
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
        val target = pendingPickTarget ?: return
        pendingPickTarget = null
        if (uri == null) return

        activityScope.launch {
            // Off Main: Photo Picker URIs can be cloud-backed, so the read can block long enough to ANR.
            val pickedMedia = withContext(Dispatchers.IO) { readPickedImage(uri) }
            graph.pickedMediaRouter.deliver(target, pickedMedia)
        }
    }

    private fun requestCurrentLocation() {
        if (hasLocationPermission()) {
            submitCurrentLocation()
        } else {
            appGraph?.pynapliAppGraph?.locationStateHolder?.beginUpdate()
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
        val locationStateHolder = appGraph?.pynapliAppGraph?.locationStateHolder ?: return
        if (!hasLocationPermission()) {
            locationStateHolder.markPermissionDenied()
            return
        }

        locationStateHolder.beginUpdate()
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = locationManager.enabledLocationProviders()

        if (providers.isEmpty()) {
            // Neither network nor GPS is enabled: location services are off for the whole device.
            locationStateHolder.markServicesOff()
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
            // The member's own share: also what later allows the silent resume refresh.
            graph.pynapliAppGraph.locationStateHolder.shareAndRefresh(
                DeviceLocation(
                    latitude = currentLocation.latitude,
                    longitude = currentLocation.longitude,
                    accuracyMeters = currentLocation.accuracyMetersOrNull(),
                ),
                graph.pynapliAppGraph.gridStateHolder,
                graph.pynapliAppGraph.locationSharingGate,
            )
        }
    }

    /**
     * Resume refresh (GRID_IMPLEMENTATION_SPEC, Location Strategy). Does nothing unless: signed in;
     * no sign-up stepper on screen (its Location step asks, and nothing answers it silently); this
     * account shared its location in the app on this device and has not skipped since
     * ([com.pynapli.app.location.LocationSharingGate]: an OS grant is per install, so it says nothing
     * about what this account chose); permission already granted; services on; no fix pending.
     * Never prompts, never shows progress or errors, never logs the fix.
     */
    private fun refreshLocationOnResume() {
        val graph = appGraph ?: return
        if (!isStarted || pendingLocationSignal != null) return
        if (!graph.authStateHolder.state.value.authenticated || !hasLocationPermission()) return
        val gate = graph.pynapliAppGraph.locationSharingGate ?: return
        activityScope.launch {
            if (!silentRefreshAllowed(graph, gate)) return@launch
            takeResumeFix()
        }
    }

    private suspend fun silentRefreshAllowed(
        graph: AndroidAppGraph,
        gate: LocationSharingGate,
    ): Boolean =
        gate.allowsSilentRefresh(
            onboardingActive = onboardingActive(graph),
            osPermissionGranted = hasLocationPermission(),
            // A grid answered from Travel mode says nothing about a shared current location.
            serverHasLocation =
                graph.pynapliAppGraph.gridStateHolder.serverHasLocation.value == true &&
                    graph.pynapliAppGraph.travelLocationStateHolder
                        ?.state
                        ?.value
                        ?.current == null,
        )

    private fun onboardingActive(graph: AndroidAppGraph): Boolean =
        graph.pynapliAppGraph.onboardingStateHolder
            ?.state
            ?.value
            ?.active == true

    /** A recent last-known fix as is; otherwise one cancellable one-shot request (onStop cancels it). */
    @SuppressLint("MissingPermission")
    private fun takeResumeFix() {
        if (!isStarted || pendingLocationSignal != null || !hasLocationPermission()) return
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = locationManager.enabledLocationProviders()
        if (providers.isEmpty()) return

        val lastKnownLocation =
            providers
                .mapNotNull { provider ->
                    runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
                }.newestOrNull()
        val lastKnownAgeNanos = lastKnownLocation?.let { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos }
        if (lastKnownLocation != null && lastKnownAgeNanos != null && lastKnownAgeNanos < RESUME_FIX_MAX_AGE_NANOS) {
            refreshDeviceLocationSilently(lastKnownLocation)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val signal = CancellationSignal()
            pendingLocationSignal = signal
            locationManager.getCurrentLocation(providers.first(), signal, mainExecutor) { currentLocation ->
                if (pendingLocationSignal == signal) {
                    pendingLocationSignal = null
                }
                currentLocation?.let(::refreshDeviceLocationSilently)
            }
            return
        }
        lastKnownLocation?.let(::refreshDeviceLocationSilently)
    }

    private fun refreshDeviceLocationSilently(currentLocation: Location) {
        val graph = appGraph ?: return
        val gate = graph.pynapliAppGraph.locationSharingGate ?: return
        activityScope.launch {
            // Checked again: a one-shot fix can take seconds, and a sign-up may have started.
            if (!graph.authStateHolder.state.value.authenticated) return@launch
            if (!silentRefreshAllowed(graph, gate)) return@launch
            graph.pynapliAppGraph.locationStateHolder.refreshInBackground(
                DeviceLocation(
                    latitude = currentLocation.latitude,
                    longitude = currentLocation.longitude,
                    accuracyMeters = currentLocation.accuracyMetersOrNull(),
                ),
                graph.pynapliAppGraph.gridStateHolder,
            )
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

private const val NOTIFICATION_PERMISSION_PREFERENCES = "pynapli_notification_permission"
private const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "requested"
private const val TAG = "MainActivity"

// A last-known fix younger than this is fresh enough for the resume refresh (no new GPS work).
private const val RESUME_FIX_MAX_AGE_NANOS = 2L * 60 * 1_000_000_000

// How long a cold start waits for the first live Grid answer before deciding on the resume refresh.
private const val COLD_START_GRID_ANSWER_WAIT_MILLIS = 20_000L
