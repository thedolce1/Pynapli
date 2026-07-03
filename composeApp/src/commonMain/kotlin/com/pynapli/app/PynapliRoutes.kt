package com.pynapli.app

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
    const val STORE = "/store"

    fun messagesThread(conversationId: String): String = "$MESSAGES/$conversationId"

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
): String =
    when {
        onboardingActive -> PynapliRoutes.SIGNUP
        !authenticated -> PynapliRoutes.LOGIN
        profileSurface != ProfileSurface.None -> PynapliRoutes.PROFILE
        else ->
            when (tab) {
                PynapliTab.Grid -> PynapliRoutes.GRID
                PynapliTab.Swipe -> PynapliRoutes.SWIPE
                PynapliTab.Activity -> PynapliRoutes.ACTIVITY
                PynapliTab.Messages ->
                    conversationId?.let(PynapliRoutes::messagesThread)
                        ?: PynapliRoutes.MESSAGES
                PynapliTab.Store -> PynapliRoutes.STORE
            }
    }
