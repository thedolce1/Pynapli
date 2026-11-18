package com.pynapli.app.swipe

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import com.pynapli.shared.network.dto.SwipeAction
import kotlin.math.absoluteValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwipePhysicsTest {
    // A 400 x 600 px card: Like/Pass threshold 112 px, Pineapple threshold 108 px up.
    private val physics = SwipePhysics(widthPx = 400f, heightPx = 600f, flingVelocityPx = 1_000f, minFlingDistancePx = 30f)

    @Test
    fun aFastShortFlickCommits() {
        assertEquals(SwipeAction.LIKE, physics.releaseDecision(Offset(50f, 5f), Velocity(2_500f, 100f)))
        assertEquals(SwipeAction.PASS, physics.releaseDecision(Offset(-50f, 0f), Velocity(-2_500f, 0f)))
        assertEquals(SwipeAction.PINEAPPLE_LIKE, physics.releaseDecision(Offset(5f, -50f), Velocity(0f, -2_500f)))
    }

    @Test
    fun aSlowLongDragPastTheThresholdCommits() {
        assertEquals(SwipeAction.LIKE, physics.releaseDecision(Offset(150f, 20f), Velocity(10f, 0f)))
        assertEquals(SwipeAction.PASS, physics.releaseDecision(Offset(-150f, 20f), Velocity.Zero))
        assertEquals(SwipeAction.PINEAPPLE_LIKE, physics.releaseDecision(Offset(20f, -150f), Velocity.Zero))
    }

    @Test
    fun aSlowShortDragOrAFlickBackSpringsBack() {
        assertNull(physics.releaseDecision(Offset(60f, 0f), Velocity(100f, 0f)))
        // Flung back towards the middle: not a pass, even though it is fast.
        assertNull(physics.releaseDecision(Offset(60f, 0f), Velocity(-2_500f, 0f)))
        // A tiny twitch is not a flick.
        assertNull(physics.releaseDecision(Offset(10f, 0f), Velocity(2_500f, 0f)))
    }

    @Test
    fun theCardLeavesAlongItsFlingButNeverAgainstTheDecision() {
        val along = physics.exitDirection(SwipeAction.LIKE, Offset(80f, 0f), Velocity(2_000f, -1_000f))
        assertTrue(along.x > 0f && along.y < 0f, "follows the fling: $along")
        assertEquals(1f, along.getDistance(), 0.001f)

        val button = physics.exitDirection(SwipeAction.PASS, Offset.Zero, Velocity.Zero)
        assertTrue(button.x < 0f, "a Pass button exits left: $button")

        val up = physics.exitDirection(SwipeAction.PINEAPPLE_LIKE, Offset(0f, 40f), Velocity.Zero)
        assertTrue(up.y < 0f && up.x.absoluteValue < 0.01f, "a Pineapple always leaves upwards: $up")
    }

    @Test
    fun stampsFadeInWithTheDragTowardsThem() {
        assertEquals(0f, physics.stampAlpha(Offset.Zero, SwipeAction.LIKE))
        assertEquals(0.5f, physics.stampAlpha(Offset(56f, 0f), SwipeAction.LIKE), 0.01f)
        assertEquals(1f, physics.stampAlpha(Offset(300f, 0f), SwipeAction.LIKE))
        assertEquals(0f, physics.stampAlpha(Offset(300f, 0f), SwipeAction.PASS))
        assertEquals(1f, physics.stampAlpha(Offset(0f, -200f), SwipeAction.PINEAPPLE_LIKE))
        assertEquals(0f, physics.stampAlpha(Offset(0f, -200f), SwipeAction.LIKE))
    }

    @Test
    fun crossingTheThresholdChangesTheDecisionBothWays() {
        assertNull(physics.decisionFor(Offset(100f, 0f)))
        assertEquals(SwipeAction.LIKE, physics.decisionFor(Offset(120f, 0f)))
        assertNull(physics.decisionFor(Offset(100f, 0f)))
    }
}
