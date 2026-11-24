package com.pynapli.app

import com.pynapli.app.HistoryOp.Go
import com.pynapli.app.HistoryOp.Push
import com.pynapli.app.HistoryOp.Replace
import com.pynapli.app.onboarding.OnboardingStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives [PynapliHistoryPlanner] against a tiny model of the browser's history stack, so each test
 * reads as "what the address bar and the back button do".
 */
class PynapliHistoryPlannerTest {
    private val dob = PynapliRoutes.signupPath(OnboardingStep.Dob)
    private val policies = PynapliRoutes.signupPath(OnboardingStep.Policies)
    private val account = PynapliRoutes.signupPath(OnboardingStep.Account)
    private val code = PynapliRoutes.signupPath(OnboardingStep.VerifyEmail)
    private val location = PynapliRoutes.signupPath(OnboardingStep.Location)
    private val profile = PynapliRoutes.signupPath(OnboardingStep.Profile)

    @Test
    fun eachSignUpStepPushesItsOwnEntry() {
        val browser = FakeBrowser(initial = "/")
        browser.report(PynapliRoutes.LOGIN)
        browser.report(dob)
        browser.report(policies)
        browser.report(account)

        assertEquals(listOf(PynapliRoutes.LOGIN, dob, policies, account), browser.entries)
    }

    @Test
    fun browserBackStepsBackThroughTheStepperToTheLanding() {
        val stepper = FakeStepper(listOf(dob, policies, account))
        val browser = FakeBrowser(initial = "/", stepper = stepper)
        browser.report(PynapliRoutes.LOGIN)
        listOf(dob, policies, account).forEach(browser::report)

        browser.back()
        assertEquals(policies, browser.path)
        assertEquals(policies, stepper.route)
        browser.back()
        assertEquals(dob, browser.path)
        browser.back()
        assertEquals(PynapliRoutes.LOGIN, browser.path)
        assertEquals(null, stepper.route, "back from the first step closes the stepper")
        assertEquals(3, stepper.backs)
    }

    @Test
    fun aRefusedBackPutsTheStepsEntryBackSoTheAddressBarMatchesTheScreen() {
        // After the code the account exists: back is consumed on Location.
        val stepper = FakeStepper(listOf(dob, policies, account, code, location), refuseAt = setOf(location))
        val browser = FakeBrowser(initial = "/", stepper = stepper)
        browser.report(PynapliRoutes.LOGIN)
        listOf(dob, policies, account, code, location).forEach(browser::report)

        browser.back()

        assertEquals(location, browser.path)
        assertEquals(location, stepper.route)
        browser.back()
        assertEquals(location, browser.path, "pressing again never reaches the code step")
    }

    @Test
    fun anInAppBackLinkRewindsInsteadOfStackingDuplicates() {
        val browser = FakeBrowser(initial = "/")
        browser.report(PynapliRoutes.LOGIN)
        listOf(dob, policies).forEach(browser::report)

        browser.report(dob) // the Policies step's own Back link

        assertEquals(listOf(PynapliRoutes.LOGIN, dob), browser.entries)
        assertEquals(dob, browser.path)
    }

    @Test
    fun finishingTheStepperLeavesNoStaleStepEntriesBehind() {
        val browser = FakeBrowser(initial = "/")
        browser.report(PynapliRoutes.LOGIN)
        listOf(dob, policies, account, code, location, profile).forEach(browser::report)

        browser.report(PynapliRoutes.GRID)

        assertEquals(listOf(PynapliRoutes.GRID), browser.entries)
        assertEquals(PynapliRoutes.GRID, browser.path)
    }

    @Test
    fun cancellingToSignInRewindsToTheLanding() {
        val browser = FakeBrowser(initial = "/")
        browser.report(PynapliRoutes.LOGIN)
        listOf(dob, policies, account, code).forEach(browser::report)

        browser.report(PynapliRoutes.LOGIN) // "Sign in" on the code step

        assertEquals(listOf(PynapliRoutes.LOGIN), browser.entries)
    }

    @Test
    fun theUrlNeverMovesTheStepperForward() {
        // The stepper is back on the birthday (its own Back link), so that is all it has visited.
        val stepper = FakeStepper(listOf(dob))
        val browser = FakeBrowser(initial = "/", stepper = stepper)
        browser.report(PynapliRoutes.LOGIN)
        listOf(dob, policies).forEach(browser::report)
        browser.report(dob) // in-app Back link: the Policies entry is now forward history

        browser.forwardTo(policies)

        assertEquals(dob, browser.path, "a forward press is replaced with where the app is")
        assertEquals(0, stepper.backs)
    }

    @Test
    fun aSignUpLinkGetsALandingToStepBackTo() {
        val browser = FakeBrowser(initial = account)

        browser.report(dob)

        assertEquals(listOf(PynapliRoutes.LOGIN, dob), browser.entries)
    }

    @Test
    fun outsideTheStepperNavigationIsUnchanged() {
        val browser = FakeBrowser(initial = "/")
        browser.report(PynapliRoutes.GRID)
        browser.report(PynapliRoutes.SWIPE)
        assertEquals(listOf(PynapliRoutes.GRID, PynapliRoutes.SWIPE), browser.entries)

        val popped = browser.back()
        assertTrue(popped.applyDeepLink, "back outside the stepper is applied as a deep link")
    }

    @Test
    fun aStaleStepEntryOutsideTheStepperStaysPut() {
        val planner = PynapliHistoryPlanner()
        planner.onRouteChanged(PynapliRoutes.GRID, "/")

        val result = planner.onPopState(code) { error("not in the stepper") }

        assertEquals(listOf<HistoryOp>(Replace(PynapliRoutes.GRID)), result.ops)
    }

    /** The stepper: a list of steps it has visited; back pops one unless the current step refuses. */
    private class FakeStepper(
        visited: List<String>,
        private val refuseAt: Set<String> = emptySet(),
    ) {
        private val steps = visited.toMutableList()
        var backs = 0
            private set
        val route: String? get() = steps.lastOrNull()

        fun stepBack(): String? {
            backs++
            val current = steps.lastOrNull() ?: return null
            if (current !in refuseAt) steps.removeAt(steps.lastIndex)
            return steps.lastOrNull()
        }
    }

    /** A browser history stack driven only by the planner's ops (and back/forward presses). */
    private class FakeBrowser(
        initial: String,
        private val stepper: FakeStepper? = null,
    ) {
        private val planner = PynapliHistoryPlanner()
        private val stack = mutableListOf(initial)
        private var index = 0

        val entries: List<String> get() = stack.take(index + 1)
        val path: String get() = stack[index]

        fun report(route: String) = perform(planner.onRouteChanged(route, path))

        fun back(): PopStateResult = go(-1)

        fun forwardTo(expected: String): PopStateResult {
            check(stack.getOrNull(index + 1) == expected) { "no forward entry $expected in $stack" }
            return go(1)
        }

        private fun go(delta: Int): PopStateResult {
            index = (index + delta).coerceIn(0, stack.lastIndex)
            val result = planner.onPopState(path) { stepper?.stepBack() }
            perform(result.ops)
            // The app reports its route after any state change, as App.kt's route effect does.
            stepper?.let { s -> perform(planner.onRouteChanged(s.route ?: PynapliRoutes.LOGIN, path)) }
            return result
        }

        private fun perform(ops: List<HistoryOp>) {
            for (op in ops) {
                when (op) {
                    is Push -> {
                        while (stack.lastIndex > index) stack.removeAt(stack.lastIndex)
                        stack += op.path
                        index = stack.lastIndex
                    }

                    is Replace -> stack[index] = op.path
                    is Go -> go(op.delta)
                }
            }
        }
    }
}
