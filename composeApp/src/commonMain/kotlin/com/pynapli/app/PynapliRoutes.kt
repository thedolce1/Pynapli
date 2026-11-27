package com.pynapli.app

import com.pynapli.app.onboarding.OnboardingStep

/**
 * Canonical browser paths for the app's navigation states (WEB_PWA_GAMEPLAN.md W1.2).
 * The web shell keeps the address bar in sync via `PynapliApp`'s
 * `onNavigationRouteChanged` callback and applies inbound paths (initial load,
 * back/forward) through [openPath]. Lives in `:composeApp` so the tab/surface
 * mapping sits next to the navigation state it describes; only route *strings*
 * cross the module boundary (`PynapliTab`/`ProfileSurface` stay internal).
 */
object PynapliRoutes {
    const val LOGIN = "/login"
    const val SIGNUP = "/signup"
    const val GRID = "/grid"
    const val SWIPE = "/swipe"
    const val ACTIVITY = "/activity"
    const val MESSAGES = "/messages"
    const val PROFILE = "/profile"

    /** Full-screen overlays: real paths so browser back closes them and they can be bookmarked. */
    const val TONIGHT = "/tonight"
    const val PSYNC = "/psync"

    /** The plans/store sheet (no Store tab since 2026-11-17); browser back closes it. */
    const val STORE = "/store"

    fun messagesThread(conversationId: String): String = "$MESSAGES/$conversationId"

    /**
     * One path per sign-up step, so the web shell can push a history entry for each and browser back
     * steps back through the stepper (B6). The first step keeps the bare [SIGNUP]. These are outbound
     * only: an inbound `/signup/...` never jumps into the middle of the stepper (it starts at the
     * birthday, see the web shell), and browser back only ever asks the stepper to step back.
     */
    fun signupPath(step: OnboardingStep): String =
        when (step) {
            OnboardingStep.Dob -> SIGNUP
            OnboardingStep.UnderageStop -> "$SIGNUP/age"
            OnboardingStep.Policies -> "$SIGNUP/policies"
            OnboardingStep.Account -> "$SIGNUP/account"
            OnboardingStep.VerifyEmail -> "$SIGNUP/code"
            OnboardingStep.Location -> "$SIGNUP/location"
            OnboardingStep.Profile -> "$SIGNUP/profile"
        }

    fun isSignupPath(path: String): Boolean {
        val normalized = path.trim().removeSuffix("/")
        return normalized == SIGNUP || normalized.startsWith("$SIGNUP/")
    }

    /**
     * Issues the deep-link command for [path]. Returns false when the path is not
     * a known app route (callers fall back to the default surface). The command
     * queues in [AppDeepLinkStateHolder] and is applied once authenticated, so a
     * deep link into a signed-out browser survives the login step.
     */
    fun openPath(
        path: String,
        deepLinks: AppDeepLinkStateHolder,
    ): Boolean {
        val normalized = path.trim().removeSuffix("/").ifEmpty { "/" }
        return when {
            normalized == "/" || normalized == GRID -> {
                deepLinks.openGrid()
                true
            }

            normalized == SWIPE -> {
                deepLinks.openSwipe()
                true
            }

            normalized == ACTIVITY -> {
                deepLinks.openActivity()
                true
            }

            normalized == MESSAGES -> {
                deepLinks.openMessages()
                true
            }

            normalized.startsWith("$MESSAGES/") -> {
                val conversationId = normalized.removePrefix("$MESSAGES/")
                if (conversationId.isEmpty() || conversationId.contains('/')) {
                    false
                } else {
                    deepLinks.openMessages(conversationId)
                    true
                }
            }

            normalized == PROFILE -> {
                deepLinks.openProfile()
                true
            }

            normalized == STORE -> {
                deepLinks.openStore()
                true
            }

            normalized == TONIGHT -> {
                deepLinks.openTonight()
                true
            }

            normalized == PSYNC -> {
                deepLinks.openPsync()
                true
            }

            else -> false
        }
    }
}

/** The canonical path for the shell's current navigation state (outbound URL sync). */
internal fun pynapliRouteFor(
    authenticated: Boolean,
    onboardingActive: Boolean,
    tab: PynapliTab,
    profileSurface: ProfileSurface,
    conversationId: String?,
    tonightOpen: Boolean = false,
    psyncOpen: Boolean = false,
    storeOpen: Boolean = false,
    onboardingStep: OnboardingStep = OnboardingStep.Dob,
): String =
    when {
        onboardingActive -> PynapliRoutes.signupPath(onboardingStep)
        !authenticated -> PynapliRoutes.LOGIN
        // The overlays draw above every tab and the profile surface, so they win; the store sheet
        // draws above the other overlays.
        storeOpen -> PynapliRoutes.STORE
        psyncOpen -> PynapliRoutes.PSYNC
        tonightOpen -> PynapliRoutes.TONIGHT
        profileSurface != ProfileSurface.None -> PynapliRoutes.PROFILE
        else ->
            when (tab) {
                PynapliTab.Grid -> PynapliRoutes.GRID
                PynapliTab.Swipe -> PynapliRoutes.SWIPE
                PynapliTab.Activity -> PynapliRoutes.ACTIVITY
                PynapliTab.Messages ->
                    conversationId?.let(PynapliRoutes::messagesThread)
                        ?: PynapliRoutes.MESSAGES
            }
    }
