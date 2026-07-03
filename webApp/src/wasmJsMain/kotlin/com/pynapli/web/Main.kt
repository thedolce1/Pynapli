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
import com.pynapli.app.design.PynapliWindowSizeClass
import com.pynapli.app.design.currentPynapliWindowSizeClass
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val graph = WebAppGraph()
    val router = WebUrlRouter(graph.deepLinkStateHolder)
    router.start()

    // A direct /signup link starts the create-account stepper — but only once the
    // session restore settles signed-out; an authenticated visitor lands normally.
    if (router.initialPathWasSignup) {
        MainScope().launch {
            val settled = graph.authStateHolder.state.first { !it.isCheckingSession }
            if (!settled.authenticated) {
                graph.onboardingStateHolder.startCreateAccount()
            }
        }
    }

    ComposeViewport(viewportContainerId = "pynapli-app") {
        Box(modifier = Modifier.fillMaxSize()) {
            WebAppContent(graph, router)
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
) {
    PynapliApp(
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
        // W2.3 wires the browser Geolocation API; until then location is set via seed/dev tools.
        onUseCurrentLocation = {},
        devLoginCredentials = graph.devLoginCredentials,
        testerDiagnosticLabel = graph.testerDiagnosticLabel,
        onNavigationRouteChanged = router::onRouteChanged,
    )
}
