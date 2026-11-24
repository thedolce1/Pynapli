package com.pynapli.app.notifications

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// TestCoroutineScheduler.timeSource: virtual time, so the dwell is measured on the test clock.
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationAskTimingTest {
    private class Fixture(
        scope: TestScope,
    ) {
        val grid = MutableStateFlow(false)
        val conversation = MutableStateFlow(false)
        val foreground = MutableStateFlow(true)
        val timing =
            NotificationAskTiming(
                gridOnScreen = grid,
                conversationOpen = conversation,
                appInForeground = foreground,
                timeSource = scope.testScheduler.timeSource,
            )
    }

    @Test
    fun signUpStepsNeverReachTheMomentAndTwentySecondsOfGridDo() =
        runTest {
            val fixture = Fixture(this)
            backgroundScope.launch { fixture.timing.run() }
            // Sign-up: signed in on the Location and Profile steps, no Grid yet.
            advanceTimeBy(120.seconds)
            assertFalse(fixture.timing.ready.value)

            fixture.grid.value = true
            advanceTimeBy(19.seconds)
            runCurrent()
            assertFalse(fixture.timing.ready.value)
            advanceTimeBy(2.seconds)
            runCurrent()
            assertTrue(fixture.timing.ready.value)
        }

    @Test
    fun gridTimeAddsUpAcrossTabSwitches() =
        runTest {
            val fixture = Fixture(this)
            backgroundScope.launch { fixture.timing.run() }
            fixture.grid.value = true
            advanceTimeBy(12.seconds)
            fixture.grid.value = false // over to Messages for a while
            advanceTimeBy(60.seconds)
            runCurrent()
            assertFalse(fixture.timing.ready.value)

            fixture.grid.value = true
            advanceTimeBy(9.seconds)
            runCurrent()
            assertTrue(fixture.timing.ready.value)
        }

    @Test
    fun aBackgroundedAppDoesNotCount() =
        runTest {
            val fixture = Fixture(this)
            backgroundScope.launch { fixture.timing.run() }
            fixture.grid.value = true
            fixture.foreground.value = false
            advanceTimeBy(5 * 60.seconds)
            runCurrent()
            assertFalse(fixture.timing.ready.value, "the OS dialog must not wait for a stopped activity")

            fixture.foreground.value = true
            advanceTimeBy(21.seconds)
            runCurrent()
            assertTrue(fixture.timing.ready.value)
        }

    @Test
    fun theFirstConversationIsTheMomentToo() =
        runTest {
            val fixture = Fixture(this)
            backgroundScope.launch { fixture.timing.run() }
            fixture.grid.value = true
            advanceTimeBy(3.seconds)
            fixture.conversation.value = true
            runCurrent()

            assertTrue(fixture.timing.ready.value)
            // Once: it stays reached.
            fixture.conversation.value = false
            fixture.grid.value = false
            runCurrent()
            assertTrue(fixture.timing.ready.value)
        }
}
