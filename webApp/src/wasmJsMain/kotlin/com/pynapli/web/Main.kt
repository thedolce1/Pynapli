package com.pynapli.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.pynapli.app.PynapliApp

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val graph = WebAppGraph()
    ComposeViewport(viewportContainerId = "pynapli-app") {
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
            onboardingStateHolder = graph.onboardingStateHolder,
            // W2.3 wires the browser Geolocation API; until then location is set via seed/dev tools.
            onUseCurrentLocation = {},
            devLoginCredentials = graph.devLoginCredentials,
            testerDiagnosticLabel = graph.testerDiagnosticLabel,
        )
    }
}
