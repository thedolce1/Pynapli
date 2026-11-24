package com.pynapli.app

/** One browser-history call the web shell makes on the planner's behalf. */
sealed interface HistoryOp {
    data class Push(
        val path: String,
    ) : HistoryOp

    data class Replace(
        val path: String,
    ) : HistoryOp

    /** `history.go(delta)`; always negative here. Its popstate comes back through [PynapliHistoryPlanner.onPopState]. */
    data class Go(
        val delta: Int,
    ) : HistoryOp
}

/** What the shell does with a popstate besides the [ops]: [applyDeepLink] hands the path to [PynapliRoutes.openPath]. */
data class PopStateResult(
    val ops: List<HistoryOp> = emptyList(),
    val applyDeepLink: Boolean = false,
)

/**
 * Browser-history policy for the web shell (WEB_PWA_GAMEPLAN.md W1.2), kept free of browser calls so
 * it runs in common tests. The shell feeds it the app's route changes and the browser's popstates and
 * performs the [HistoryOp]s it returns.
 *
 * Outside the sign-up stepper (unchanged): in-app navigation pushes, so browser back mirrors app back;
 * the auth surface and the bare `/` replace, keeping pre-auth screens out of the back stack.
 *
 * Inside the stepper (B6): each step pushes its own entry ([PynapliRoutes.signupPath]) on top of the
 * landing it started from, so browser back steps back through the stepper instead of leaving the
 * site. The planner mirrors those entries in [signupEntries]:
 * - Browser back onto the previous entry asks the stepper to step back ([onPopState]'s `stepBack`).
 *   If the stepper refuses (the post-code steps consume back), the entry is pushed again, so the
 *   address bar never disagrees with the screen. The URL never moves the stepper forward: a forward
 *   press or a stale entry is replaced with where the app really is.
 * - An in-app move back to an earlier step (the Back links) rewinds history to that entry instead of
 *   pushing a duplicate.
 * - Leaving the stepper (finished, or cancelled to the landing) rewinds to the entry the stepper
 *   started from and replaces it with the new route, so no stale step entries are left to land on.
 */
class PynapliHistoryPlanner {
    private var lastReported: String? = null

    // The history entries the stepper owns, oldest first. [0] is the entry it started from (the
    // landing); the last one is the entry the browser is on.
    private val signupEntries = mutableListOf<String>()

    // Set while a Go(-n) is in flight: when its popstate lands, that entry becomes this route.
    private var rewindTarget: String? = null

    val lastReportedRoute: String? get() = lastReported

    fun onRouteChanged(
        route: String,
        currentPath: String,
    ): List<HistoryOp> {
        val previous = lastReported
        lastReported = route
        if (rewindTarget != null) {
            // A rewind is still travelling; land it on the newest route instead.
            rewindTarget = route
            return emptyList()
        }
        if (currentPath == route) return emptyList()

        if (PynapliRoutes.isSignupPath(route)) {
            if (previous == null) {
                // First route of the page (a /signup link, a reload, or a resumed sign-up): give the
                // stepper a landing to step back to.
                signupEntries.clear()
                signupEntries += PynapliRoutes.LOGIN
                signupEntries += route
                return listOf(HistoryOp.Replace(PynapliRoutes.LOGIN), HistoryOp.Push(route))
            }
            val earlier = signupEntries.dropLast(1).lastIndexOf(route)
            if (earlier >= 0) {
                // An in-app Back link: rewind to that entry rather than piling up duplicates.
                val delta = earlier - (signupEntries.size - 1)
                while (signupEntries.size > earlier + 1) signupEntries.removeAt(signupEntries.lastIndex)
                rewindTarget = route
                return listOf(HistoryOp.Go(delta))
            }
            if (signupEntries.isEmpty() || signupEntries.last() != currentPath) {
                signupEntries.clear()
                signupEntries += currentPath
            }
            signupEntries += route
            return listOf(HistoryOp.Push(route))
        }

        if (signupEntries.size > 1) {
            // Leaving the stepper: back to the entry it started from, which becomes [route].
            val delta = -(signupEntries.size - 1)
            signupEntries.clear()
            rewindTarget = route
            return listOf(HistoryOp.Go(delta))
        }
        signupEntries.clear()

        val replacing =
            route == PynapliRoutes.LOGIN ||
                PynapliRoutes.isSignupPath(route) ||
                currentPath == PynapliRoutes.LOGIN ||
                PynapliRoutes.isSignupPath(currentPath) ||
                currentPath == "/"
        return listOf(if (replacing) HistoryOp.Replace(route) else HistoryOp.Push(route))
    }

    /**
     * The browser moved to [path] (back, forward, or the end of a rewind). [stepBack] asks the stepper
     * to step back and returns its route afterwards: its step's path, or null when it closed (back to
     * the landing). It must change the state synchronously ([com.pynapli.app.onboarding.OnboardingStateHolder.stepBack]).
     */
    fun onPopState(
        path: String,
        stepBack: () -> String?,
    ): PopStateResult {
        rewindTarget?.let { target ->
            rewindTarget = null
            if (signupEntries.isNotEmpty()) signupEntries[signupEntries.lastIndex] = target
            return PopStateResult(ops = if (path == target) emptyList() else listOf(HistoryOp.Replace(target)))
        }
        val current = lastReported
        if (path == current) return PopStateResult()

        if (current != null && PynapliRoutes.isSignupPath(current)) {
            val previousEntry = signupEntries.getOrNull(signupEntries.size - 2)
            if (previousEntry != null && path == previousEntry) {
                signupEntries.removeAt(signupEntries.lastIndex)
                val after = stepBack() ?: PynapliRoutes.LOGIN
                lastReported = after
                if (after == path) {
                    if (!PynapliRoutes.isSignupPath(after)) signupEntries.clear()
                    return PopStateResult()
                }
                // Back was refused (or landed elsewhere): put the screen's entry back on top.
                signupEntries += after
                return PopStateResult(ops = listOf(HistoryOp.Push(after)))
            }
            // Forward, or an entry the stepper no longer owns: the URL never moves the stepper.
            return PopStateResult(ops = listOf(HistoryOp.Replace(current)))
        }

        if (PynapliRoutes.isSignupPath(path)) {
            // A stale stepper entry outside the stepper (it finished or was left): stay put.
            return PopStateResult(ops = listOf(HistoryOp.Replace(current ?: PynapliRoutes.LOGIN)))
        }
        return PopStateResult(applyDeepLink = true)
    }
}
