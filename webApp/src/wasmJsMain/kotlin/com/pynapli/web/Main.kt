package com.pynapli.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import com.pynapli.app.PynapliApp
import com.pynapli.app.PynapliAppGraph
import com.pynapli.app.PynapliPlatform
import com.pynapli.app.design.PynapliWindowSizeClass
import com.pynapli.app.design.currentPynapliWindowSizeClass
import com.pynapli.shared.core.result.runCatchingCancellable
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
            locationStateHolder = graph.locationStateHolder,
            gridStateHolder = graph.gridStateHolder,
            scope = appScope,
        )
    val mediaPicker =
        WebMediaPicker(
            conversationStateHolder = graph.conversationStateHolder,
            mediaAlbumsStateHolder = graph.mediaAlbumsStateHolder,
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
    appScope.launch { runCatchingCancellable { graph.gridPresenceStreamCoordinator.run() } }
    appScope.launch { runCatchingCancellable { graph.activityRealtimeCoordinator.run() } }
    appScope.launch { runCatchingCancellable { graph.messagesRealtimeCoordinator.run() } }

    // Tab wake: HTTP stays canonical, so returning to a backgrounded tab refetches
    // state the throttled socket may have missed (the stream clients reconnect on
    // their own backoff).
    onDocumentVisible {
        if (graph.authStateHolder.state.value.authenticated) {
            appScope.launch { graph.messagesStateHolder.refreshSilently() }
            appScope.launch { graph.activityStateHolder.refreshSummarySilently() }
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
        Box(modifier = Modifier.fillMaxSize()) {
            WebAppContent(graph, router, locationAdapter, mediaPicker)
            // Clears the compact bottom bar; on wide windows it sits in the corner.
            val compact = currentPynapliWindowSizeClass() == PynapliWindowSizeClass.Compact
            WebInstallAffordance(
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = if (compact) 96.dp else 24.dp),
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun WebAppContent(
    graph: WebAppGraph,
    router: WebUrlRouter,
    locationAdapter: WebLocationAdapter,
    mediaPicker: WebMediaPicker,
) {
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
                deepLinkStateHolder = graph.deepLinkStateHolder,
                onboardingStateHolder = graph.onboardingStateHolder,
            ),
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
