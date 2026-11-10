package com.pynapli.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import com.pynapli.app.PynapliApp
import com.pynapli.app.PynapliPlatform
import com.pynapli.app.design.PynapliWindowSizeClass
import com.pynapli.app.design.currentPynapliWindowSizeClass
import com.pynapli.app.design.pynapliTextScaleForBrowserFont
import com.pynapli.app.media.PickedMediaRouter
import com.pynapli.shared.core.result.runCatchingCancellable
import com.pynapli.web.platform.browserDefaultFontSizePx
import com.pynapli.web.platform.onDocumentVisible
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val graph = WebAppGraph()
    val router = WebUrlRouter(graph.deepLinkStateHolder)
    router.start()
    val appScope = MainScope()
    val locationAdapter =
        WebLocationAdapter(
            locationStateHolder = graph.pynapliAppGraph.locationStateHolder,
            gridStateHolder = graph.pynapliAppGraph.gridStateHolder,
            scope = appScope,
        )
    val mediaPicker =
        WebMediaPicker(
            router = PickedMediaRouter(graph.pynapliAppGraph.mediaAlbumsStateHolder, graph.pynapliAppGraph.conversationStateHolder),
            scope = appScope,
        )

    // Realtime convenience deltas (WEB_PWA_GAMEPLAN.md W3.2). The clients wait for a
    // session token with backoff, so starting at boot is safe pre-login.
    //
    // Unlike Android and iOS, these are never stopped: a hidden browser tab keeps its socket, and
    // the shell has no teardown hook. That is a deliberate difference, not a gap — but it does mean
    // a backgrounded tab still holds a presence connection, so revisit it if presence counts or
    // socket load look inflated on web. runCatchingCancellable for the same reason as the other two
    // shells: appScope is app-lifetime today, and a swallowed cancellation would be a silent trap
    // the moment that stops being true.
    graph.startRealtime(appScope)

    // Tab wake: HTTP stays canonical, so returning to a backgrounded tab refetches
    // state the throttled socket may have missed (the stream clients reconnect on
    // their own backoff).
    onDocumentVisible {
        if (graph.authStateHolder.state.value.authenticated) {
            appScope.launch { graph.pynapliAppGraph.messagesStateHolder.refreshSilently() }
            appScope.launch { graph.pynapliAppGraph.activityStateHolder.refreshSummarySilently() }
        }
    }

    // A direct /signup link starts the create-account stepper — but only once the
    // session restore settles signed-out; an authenticated visitor lands normally.
    if (router.initialPathWasSignup) {
        appScope.launch {
            val settled = graph.authStateHolder.state.first { !it.isCheckingSession }
            if (!settled.authenticated) {
                graph.onboardingStateHolder.startCreateAccount()
            }
        }
    }

    ComposeViewport(viewportContainerId = "pynapli-app") {
        WithBrowserTextScale {
            Box(modifier = Modifier.fillMaxSize()) {
                WebAppContent(graph, router, locationAdapter, mediaPicker)
            /*
             * Floats clear of the compact bottom bar; on wide windows it sits in the corner.
             *
             * 96dp cleared the nav bar but NOT the controls sitting directly above it, so on a
             * phone browser the pill landed on top of Swipe's Like/Pineapple buttons and the
             * conversation composer's send button. Verified by tapping exactly where Like is drawn:
             * the pill swallowed the tap and the card did not advance — a first-time visitor's
             * first Like silently did nothing, on the app's primary action.
             *
             * Cleared unconditionally at Compact rather than per-route. A "surfaces that have a
             * bottom action bar" list is the same shape as the bug that made the Grid skeleton
             * hardcode 3 columns: correct when written, silently wrong the moment a surface grows
             * one. One clearance tall enough for the tallest bottom control cannot drift, and the
             * cost is only that a dismissible promo floats slightly higher on surfaces that did
             * not need it.
             */
                val compact = currentPynapliWindowSizeClass() == PynapliWindowSizeClass.Compact
                WebInstallAffordance(
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(
                                end = 16.dp,
                                bottom = if (compact) CompactInstallBottomInset else 24.dp,
                            ),
                )
            }
        }
    }
}

/**
 * Honors the browser's font-size setting (see pynapliTextScaleForBrowserFont) for everything on the
 * page, the install pill included. Read once: a browser applies a changed setting on reload anyway.
 */
@androidx.compose.runtime.Composable
private fun WithBrowserTextScale(content: @androidx.compose.runtime.Composable () -> Unit) {
    val textScale = remember { pynapliTextScaleForBrowserFont(browserDefaultFontSizePx()) }
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * textScale), content = content)
}

/**
 * Bottom inset for the install pill at Compact.
 *
 * Measured at 390x844: the bottom nav occupies the last ~70dp and Swipe's action row sits directly
 * above it, spanning roughly 96–144dp from the bottom edge. 160dp clears both with margin. Keep it
 * above the tallest bottom-anchored control on any surface — if one ever grows past this, the
 * symptom is a swallowed tap, not a visual glitch, so it will not be obvious.
 */
private val CompactInstallBottomInset = 160.dp

@androidx.compose.runtime.Composable
private fun WebAppContent(
    graph: WebAppGraph,
    router: WebUrlRouter,
    locationAdapter: WebLocationAdapter,
    mediaPicker: WebMediaPicker,
) {
    PynapliApp(
        graph = graph.pynapliAppGraph,
        platform =
            PynapliPlatform(
                onUseCurrentLocation = locationAdapter::requestCurrentLocation,
                onPickMediaForAlbum = mediaPicker::pickForAlbum,
                onPickConversationPhoto = mediaPicker::pickConversationPhoto,
                // Founder-locked web behavior: Store is read-only; browsers have no launcher icon.
                storePurchasesAvailable = false,
                discreetIconSupported = false,
                devLoginCredentials = graph.devLoginCredentials,
                testerDiagnosticLabel = graph.testerDiagnosticLabel,
                onNavigationRouteChanged = router::onRouteChanged,
            ),
    )
}
