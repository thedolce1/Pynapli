package com.pynapli.app

import androidx.compose.ui.window.ComposeUIViewController
import com.pynapli.app.media.MediaAlbumKindUi
import com.pynapli.shared.core.config.PynapliConfig
import platform.UIKit.UIViewController

/**
 * The whole iOS entry point, in Kotlin, so the Swift shell stays tiny.
 *
 * Swift's job reduces to: build a [PynapliConfig], supply an install id and a few platform
 * callbacks, and present the returned `UIViewController`. Everything else — the graph, the theme,
 * navigation, all five surfaces — is the shared Compose app that Android and web already run.
 *
 * Deliberately mirrors how `MainActivity` and `Main.kt` call `PynapliApp`: one [PynapliAppGraph] and
 * one [PynapliPlatform]. If those grow, this does not change shape.
 *
 * STATUS: type-checked by `compileKotlinIosSimulatorArm64` on Windows; never linked or run, because
 * producing a framework requires macOS. See docs/IOS_BRINGUP_PLAN.md.
 */
object IosPynapliEntry {
    /**
     * Builds the graph once. Hold the result for the app's lifetime — constructing it twice would
     * open a second SQLDelight connection and a second set of WebSocket clients.
     *
     * @param keychain the shell's Keychain access. Omitting it leaves session tokens in **plaintext**
     *   (see [IosAppGraph]) — acceptable for a first bring-up build, never for one a user installs.
     */
    fun createGraph(
        config: PynapliConfig,
        installId: String,
        keychain: IosKeychainBridge? = null,
    ): IosAppGraph =
        IosAppGraph(
            pynapliConfig = config,
            installId = installId,
            keychain = keychain,
        )

    /**
     * The root view controller.
     *
     * @param onUseCurrentLocation Swift asks CoreLocation for a one-shot fix and feeds it back
     *   through `graph.pynapliAppGraph.locationStateHolder`. One-shot on purpose: the Android policy
     *   check (`scripts/android-location-policy-check.ps1`) forbids background or continuous
     *   updates, and iOS should not diverge from that.
     * @param onPickMediaForAlbum Swift presents `PHPickerViewController` for the given album.
     * @param onPickConversationPhoto Same picker, for the conversation composer.
     * @param storePurchasesAvailable Leave false until StoreKit 2 lands, matching the web shell.
     */
    fun createViewController(
        graph: IosAppGraph,
        onUseCurrentLocation: () -> Unit,
        onPickMediaForAlbum: (MediaAlbumKindUi) -> Unit,
        onPickConversationPhoto: () -> Unit,
        storePurchasesAvailable: Boolean = false,
        testerDiagnosticLabel: String? = null,
    ): UIViewController =
        ComposeUIViewController {
            PynapliApp(
                graph = graph.pynapliAppGraph,
                platform =
                    PynapliPlatform(
                        onUseCurrentLocation = onUseCurrentLocation,
                        onPickMediaForAlbum = onPickMediaForAlbum,
                        onPickConversationPhoto = onPickConversationPhoto,
                        // No launcher-icon disguise on iOS: alternate app icons exist but are a
                        // separate capability from Android's activity-alias swap, so the Profile
                        // toggle stays hidden until it is actually implemented.
                        discreetIconSupported = false,
                        storePurchasesAvailable = storePurchasesAvailable,
                        testerDiagnosticLabel = testerDiagnosticLabel,
                    ),
            )
        }
}
