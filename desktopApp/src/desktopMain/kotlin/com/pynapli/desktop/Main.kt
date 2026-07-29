package com.pynapli.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.pynapli.app.PynapliApp
import com.pynapli.app.PynapliPlatform
import com.pynapli.shared.core.result.runCatchingCancellable
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Desktop entry point — a Windows/macOS/Linux window hosting the same `:composeApp` UI that
 * Android, iOS and web render.
 *
 * WHY THIS EXISTS: iterating on Compose UI through the Android emulator costs an install +
 * cold start per change, and the iOS shell cannot be built at all without a Mac. This target
 * renders the identical shared Compose tree on the JVM with ordinary Gradle rebuild times, so
 * layout, navigation and state work can be done on a Windows host and only genuinely
 * platform-specific behaviour needs a device.
 *
 * WHAT IT DOES NOT PROVE (use a real device/emulator for these): touch gesture feel, iOS safe-area
 * and notch insets, native back-gesture behaviour, IME/keyboard quirks, StoreKit or Play Billing,
 * push delivery, and anything Skia renders differently between backends. Green here is not a
 * substitute for `scripts/android-*-qa.ps1`.
 *
 * Run it with the local Ktor server up:
 *
 * ```powershell
 * .\gradlew.bat :desktopApp:run
 * ```
 */
fun main() {
    val graph = DesktopAppGraph()
    val appScope = MainScope()
    val locationAdapter =
        DesktopLocationAdapter(
            locationStateHolder = graph.locationStateHolder,
            gridStateHolder = graph.gridStateHolder,
            scope = appScope,
        )
    val mediaPicker =
        DesktopMediaPicker(
            conversationStateHolder = graph.conversationStateHolder,
            mediaAlbumsStateHolder = graph.mediaAlbumsStateHolder,
            scope = appScope,
        )

    // Realtime convenience deltas. The stream clients wait for a session token with backoff, so
    // starting them pre-login is safe. runCatchingCancellable for the reason the other shells give:
    // appScope is app-lifetime here, and a swallowed cancellation would become a silent trap the
    // moment that stops being true.
    appScope.launch { runCatchingCancellable { graph.gridPresenceStreamCoordinator.run() } }
    appScope.launch { runCatchingCancellable { graph.activityRealtimeCoordinator.run() } }
    appScope.launch { runCatchingCancellable { graph.messagesRealtimeCoordinator.run() } }

    application {
        Window(
            // Cancelling the scope tears down the three coordinators and their sockets. Android
            // does this from `MainActivity.onStop` and iOS from `stopRealtime()`; a desktop window
            // close is the same event, and leaving sockets reconnecting after the last window shuts
            // would keep the process alive.
            onCloseRequest = {
                appScope.cancel()
                exitApplication()
            },
            title = "Pynapli (desktop dev shell)",
            // Roughly a tall phone, so the compact layouts the mobile shells use are what renders
            // by default. Resize freely — the window size classes are the point of testing here.
            state = rememberWindowState(size = DpSize(420.dp, 900.dp)),
        ) {
            DesktopAppContent(graph, locationAdapter, mediaPicker)
        }
    }
}

@Composable
private fun DesktopAppContent(
    graph: DesktopAppGraph,
    locationAdapter: DesktopLocationAdapter,
    mediaPicker: DesktopMediaPicker,
) {
    PynapliApp(
        graph = graph.pynapliAppGraph,
        platform =
            PynapliPlatform(
                onUseCurrentLocation = locationAdapter::requestCurrentLocation,
                onPickMediaForAlbum = mediaPicker::pickForAlbum,
                onPickConversationPhoto = mediaPicker::pickConversationPhoto,
                // No billing and no launcher icon on the desktop dev shell, same as web.
                storePurchasesAvailable = false,
                discreetIconSupported = false,
                devLoginCredentials = graph.devLoginCredentials,
                testerDiagnosticLabel = DesktopRuntimeConfig.testerDiagnosticLabel,
            ),
    )
}
