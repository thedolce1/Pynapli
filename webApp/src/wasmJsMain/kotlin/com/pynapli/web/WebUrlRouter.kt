package com.pynapli.web

import com.pynapli.app.AppDeepLinkStateHolder
import com.pynapli.app.PynapliRoutes
import com.pynapli.web.platform.historyPushState
import com.pynapli.web.platform.historyReplaceState
import com.pynapli.web.platform.onWindowPopState
import com.pynapli.web.platform.windowLocationPathname

/**
 * Two-way bridge between the browser address bar and the app's navigation state
 * (WEB_PWA_GAMEPLAN.md W1.2).
 *
 * Inbound (initial load, back/forward): paths become deep-link commands, which
 * `PynapliAppEffects` applies once authenticated — so a deep link into a signed-out
 * browser survives the login step.
 *
 * Outbound (`PynapliApp.onNavigationRouteChanged`): in-app navigation pushes history
 * entries so browser back mirrors app back; auth surfaces (login/signup) and the
 * bare `/` replace instead, keeping pre-auth screens out of the back stack.
 */
class WebUrlRouter(
    private val deepLinks: AppDeepLinkStateHolder,
) {
    /** True when the first URL of the session asked for the signup stepper. */
    var initialPathWasSignup: Boolean = false
        private set

    private var lastReportedRoute: String? = null

    fun start() {
        val initialPath = normalize(windowLocationPathname())
        initialPathWasSignup = initialPath == PynapliRoutes.SIGNUP
        PynapliRoutes.openPath(initialPath, deepLinks)
        onWindowPopState { applyPath(normalize(windowLocationPathname())) }
    }

    fun onRouteChanged(route: String) {
        lastReportedRoute = route
        val current = normalize(windowLocationPathname())
        if (current == route) return
        val replacing =
            route == PynapliRoutes.LOGIN ||
                route == PynapliRoutes.SIGNUP ||
                current == PynapliRoutes.LOGIN ||
                current == PynapliRoutes.SIGNUP ||
                current == "/"
        if (replacing) {
            historyReplaceState(route)
        } else {
            historyPushState(route)
        }
    }

    private fun applyPath(path: String) {
        // The app already sits on this route (it navigated first and pushed) — nothing to apply.
        if (path == lastReportedRoute) return
        PynapliRoutes.openPath(path, deepLinks)
    }

    private fun normalize(path: String): String = path.trim().removeSuffix("/").ifEmpty { "/" }
}
