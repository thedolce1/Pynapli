package com.pynapli.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PynapliRoutesTest {
    @Test
    fun knownPathsIssueTheMatchingDeepLinkCommand() {
        val cases =
            mapOf(
                "/" to AppDeepLinkStateHolder.TARGET_GRID,
                "/grid" to AppDeepLinkStateHolder.TARGET_GRID,
                "/grid/" to AppDeepLinkStateHolder.TARGET_GRID,
                "/swipe" to AppDeepLinkStateHolder.TARGET_SWIPE,
                "/activity" to AppDeepLinkStateHolder.TARGET_ACTIVITY,
                "/messages" to AppDeepLinkStateHolder.TARGET_MESSAGES,
                "/profile" to AppDeepLinkStateHolder.TARGET_PROFILE,
                "/store" to AppDeepLinkStateHolder.TARGET_STORE,
            )
        cases.forEach { (path, expectedTarget) ->
            val deepLinks = AppDeepLinkStateHolder()
            assertTrue(PynapliRoutes.openPath(path, deepLinks), "expected $path to be routable")
            assertEquals(expectedTarget, deepLinks.state.value?.target, "path $path")
        }
    }

    @Test
    fun messagesThreadPathCarriesTheConversationId() {
        val deepLinks = AppDeepLinkStateHolder()
        assertTrue(PynapliRoutes.openPath("/messages/conv-42", deepLinks))
        assertEquals(AppDeepLinkStateHolder.TARGET_MESSAGES, deepLinks.state.value?.target)
        assertEquals("conv-42", deepLinks.state.value?.conversationId)
    }

    @Test
    fun unknownPathsAreRejectedWithoutIssuingCommands() {
        val deepLinks = AppDeepLinkStateHolder()
        assertFalse(PynapliRoutes.openPath("/nope", deepLinks))
        assertFalse(PynapliRoutes.openPath("/messages/a/b", deepLinks))
        assertFalse(PynapliRoutes.openPath("/messages//", deepLinks))
        assertNull(deepLinks.state.value)
    }

    @Test
    fun routeForMapsNavigationStateToCanonicalPaths() {
        assertEquals(
            PynapliRoutes.LOGIN,
            pynapliRouteFor(
                authenticated = false,
                onboardingActive = false,
                tab = PynapliTab.Grid,
                profileSurface = ProfileSurface.None,
                conversationId = null,
            ),
        )
        assertEquals(
            PynapliRoutes.SIGNUP,
            pynapliRouteFor(
                authenticated = false,
                onboardingActive = true,
                tab = PynapliTab.Grid,
                profileSurface = ProfileSurface.None,
                conversationId = null,
            ),
        )
        assertEquals(
            PynapliRoutes.PROFILE,
            pynapliRouteFor(
                authenticated = true,
                onboardingActive = false,
                tab = PynapliTab.Grid,
                profileSurface = ProfileSurface.Hub,
                conversationId = null,
            ),
        )
        assertEquals(
            "/messages/conv-42",
            pynapliRouteFor(
                authenticated = true,
                onboardingActive = false,
                tab = PynapliTab.Messages,
                profileSurface = ProfileSurface.None,
                conversationId = "conv-42",
            ),
        )
        assertEquals(
            PynapliRoutes.STORE,
            pynapliRouteFor(
                authenticated = true,
                onboardingActive = false,
                tab = PynapliTab.Store,
                profileSurface = ProfileSurface.None,
                conversationId = null,
            ),
        )
    }
}
