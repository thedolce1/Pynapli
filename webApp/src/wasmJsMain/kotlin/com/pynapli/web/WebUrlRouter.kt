package com.pynapli.web

import com.pynapli.app.AppDeepLinkStateHolder
import com.pynapli.app.HistoryOp
import com.pynapli.app.PynapliHistoryPlanner
import com.pynapli.app.PynapliRoutes
import com.pynapli.web.platform.historyGo
import com.pynapli.web.platform.historyPushState
import com.pynapli.web.platform.historyReplaceState
import com.pynapli.web.platform.onWindowPopState
import com.pynapli.web.platform.windowLocationPathname

/**
 * Two-way bridge between the browser address bar and the app's navigation state
 * (WEB_PWA_GAMEPLAN.md W1.2). The policy (when to push, replace or rewind) lives in
 * [PynapliHistoryPlanner], which is common-tested; this class only performs its calls.
 *
 * Inbound (initial load, back/forward): paths become deep-link commands, which
 * `PynapliAppEffects` applies once authenticated — so a deep link into a signed-out
 * browser survives the login step. Inside the sign-up stepper, browser back asks the
 * stepper to step back ([signupStepBack]) instead; a URL never moves it forward.
 *
 * Outbound (`PynapliApp.onNavigationRouteChanged`): in-app navigation pushes history
 * entries so browser back mirrors app back; the auth surface and the bare `/` replace.
 * Each sign-up step pushes its own entry (B6).
 */
class WebUrlRouter(
    private val deepLinks: AppDeepLinkStateHolder,
    // Steps the stepper back synchronously and returns its route after (null once it closed).
    private val signupStepBack: () -> String? = { null },
) {
    /** True when the first URL of the session asked for the signup stepper (any `/signup...` path). */
    var initialPathWasSignup: Boolean = false
        private set

    private val planner = PynapliHistoryPlanner()

    fun start() {
        val initialPath = normalize(windowLocationPathname())
        // Any sign-up step link starts at the birthday: the URL never skips the stepper's gates.
        initialPathWasSignup = PynapliRoutes.isSignupPath(initialPath)
        PynapliRoutes.openPath(initialPath, deepLinks)
        onWindowPopState { onPopState(normalize(windowLocationPathname())) }
    }

    fun onRouteChanged(route: String) {
        perform(planner.onRouteChanged(route, normalize(windowLocationPathname())))
    }

    private fun onPopState(path: String) {
        val result = planner.onPopState(path, signupStepBack)
        perform(result.ops)
        if (result.applyDeepLink) PynapliRoutes.openPath(path, deepLinks)
    }

    private fun perform(ops: List<HistoryOp>) {
        for (op in ops) {
            when (op) {
                is HistoryOp.Push -> historyPushState(op.path)
                is HistoryOp.Replace -> historyReplaceState(op.path)
                is HistoryOp.Go -> historyGo(op.delta)
            }
        }
    }

    private fun normalize(path: String): String = path.trim().removeSuffix("/").ifEmpty { "/" }
}
