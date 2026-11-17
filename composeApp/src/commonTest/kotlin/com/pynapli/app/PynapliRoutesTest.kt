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
                "/tonight" to AppDeepLinkStateHolder.TARGET_TONIGHT,
                "/psync" to AppDeepLinkStateHolder.TARGET_PSYNC,
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
        // The store is a sheet over whatever is showing (no Store tab), and it draws above the overlays.
        assertEquals(
            PynapliRoutes.STORE,
            pynapliRouteFor(
                authenticated = true,
                onboardingActive = false,
                tab = PynapliTab.Swipe,
                profileSurface = ProfileSurface.Hub,
                conversationId = null,
                psyncOpen = true,
                storeOpen = true,
            ),
        )
    }

    @Test
    fun thereAreFourTabsAndNoStoreTab() {
        assertEquals(
            listOf(PynapliTab.Grid, PynapliTab.Swipe, PynapliTab.Activity, PynapliTab.Messages),
            PynapliTab.entries.toList(),
        )
    }

    @Test
    fun openOverlaysWinOverTheTabAndProfileSurface() {
        assertEquals(
            PynapliRoutes.TONIGHT,
            pynapliRouteFor(
                authenticated = true,
                onboardingActive = false,
                tab = PynapliTab.Grid,
                profileSurface = ProfileSurface.Hub,
                conversationId = null,
                tonightOpen = true,
            ),
        )
        assertEquals(
            PynapliRoutes.PSYNC,
            pynapliRouteFor(
                authenticated = true,
                onboardingActive = false,
                tab = PynapliTab.Swipe,
                profileSurface = ProfileSurface.None,
                conversationId = null,
                tonightOpen = true,
                psyncOpen = true,
            ),
        )
        // Signed out, an overlay flag left over from the last session must not leak into the URL.
        assertEquals(
            PynapliRoutes.LOGIN,
            pynapliRouteFor(
                authenticated = false,
                onboardingActive = false,
                tab = PynapliTab.Grid,
                profileSurface = ProfileSurface.None,
                conversationId = null,
                tonightOpen = true,
            ),
        )
    }
}
