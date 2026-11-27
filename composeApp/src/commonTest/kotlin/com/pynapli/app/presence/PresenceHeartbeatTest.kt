package com.pynapli.app.presence

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class PresenceHeartbeatTest {
    private val signedIn = MutableStateFlow(false)
    private val signingUp = MutableStateFlow(false)
    private val visible = MutableStateFlow(true)

    @Test
    fun beatsOnceOnBecomingEligibleThenEveryMinuteAndStopsWhenNotEligible() =
        runTest {
            var beats = 0
            val heartbeat = PresenceHeartbeat({ beats += 1 }, signedIn, signingUp, visible)
            backgroundScope.launch { heartbeat.run() }
            runCurrent()

            advanceTimeBy(5.minutes)
            assertEquals(0, beats, "signed out: never")

            signedIn.value = true
            runCurrent()
            assertEquals(1, beats, "opening the app marks you online at once")
            advanceTimeBy(59.seconds)
            assertEquals(1, beats)
            advanceTimeBy(2.seconds)
            assertEquals(2, beats)

            // The sign-up stepper: no beats.
            signingUp.value = true
            runCurrent()
            advanceTimeBy(5.minutes)
            assertEquals(2, beats)
            signingUp.value = false
            runCurrent()
            assertEquals(3, beats)

            // Hidden browser tab: no beats.
            visible.value = false
            runCurrent()
            advanceTimeBy(5.minutes)
            assertEquals(3, beats)

            visible.value = true
            runCurrent()
            signedIn.value = false
            runCurrent()
            advanceTimeBy(5.minutes)
            assertEquals(4, beats, "one beat on becoming visible, none after sign-out")
        }

    @Test
    fun aFailedBeatIsNotRetriedBeforeTheNextTick() =
        runTest {
            var attempts = 0
            val failing: suspend () -> Unit = {
                attempts += 1
                error("offline")
            }
            val heartbeat = PresenceHeartbeat(failing, signedIn, signingUp, visible)
            signedIn.value = true
            backgroundScope.launch { heartbeat.run() }
            runCurrent()
            assertEquals(1, attempts)
            advanceTimeBy(30.seconds)
            assertEquals(1, attempts, "no retry")
            advanceTimeBy(31.seconds)
            assertEquals(2, attempts, "the next tick still runs")
        }
}
