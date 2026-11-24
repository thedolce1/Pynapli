package com.pynapli.app.location

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The silent resume refresh follows live inputs only; nothing about it is stored on the device. */
class SilentLocationRefreshRuleTest {
    private fun allowed(
        signedIn: Boolean = true,
        onboardingActive: Boolean = false,
        osPermissionGranted: Boolean = true,
        serverHasLocation: Boolean? = true,
    ) = silentLocationRefreshAllowed(signedIn, onboardingActive, osPermissionGranted, serverHasLocation)

    @Test
    fun aSignedInMemberWhoseLocationTheServerHoldsIsKeptFresh() {
        assertTrue(allowed())
    }

    @Test
    fun neverWhileTheSignUpStepperIsOnScreen() {
        // The Location step is answered by the member's own Share or Skip, never by a resume.
        assertFalse(allowed(onboardingActive = true))
    }

    @Test
    fun neverWithoutThePermissionSoItCanNeverPrompt() {
        assertFalse(allowed(osPermissionGranted = false))
    }

    @Test
    fun neverSignedOut() {
        assertFalse(allowed(signedIn = false))
    }

    @Test
    fun aMemberWhoSkippedHasNoServerLocationSoAnotherAccountsGrantNeverSendsTheirs() {
        // Account B signed up on A's device and skipped: B's Grid answers LOCATION_REQUIRED.
        assertFalse(allowed(serverHasLocation = false))
    }

    @Test
    fun noLiveGridAnswerYetBlocks() {
        // Cached cards, or just signed in: unknown is not consent.
        assertFalse(allowed(serverHasLocation = null))
    }
}
