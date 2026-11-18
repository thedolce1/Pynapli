package com.pynapli.app.swipe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.LocalPynapliHaptics
import com.pynapli.app.design.PynapliHaptics
import com.pynapli.app.design.PynapliTheme
import com.pynapli.shared.network.dto.SwipeAction
import com.pynapli.shared.network.dto.SwipeCardDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SwipeCardGestureTest {
    private val cards =
        listOf("a", "b", "c").map { id ->
            SwipeCardDto(userId = id, displayName = "Sam $id", displayAge = 30, rankToken = "t-$id")
        }

    private class Recorder {
        val swiped = mutableListOf<Pair<String, String>>()
        val pineappleGestures = mutableListOf<String>()
        val opened = mutableListOf<String>()
        val haptics = mutableListOf<String>()
    }

    private fun ComposeUiTest.setStack(
        recorder: Recorder,
        requested: () -> String? = { null },
        onActionConsumed: () -> Unit = {},
    ) {
        setContent {
            var deck by remember { mutableStateOf(cards) }
            CompositionLocalProvider(
                LocalPynapliHaptics provides
                    object : PynapliHaptics {
                        override fun thresholdTick() {
                            recorder.haptics += "tick"
                        }

                        override fun commit() {
                            recorder.haptics += "commit"
                        }

                        override fun matchSuccess() {
                            recorder.haptics += "match"
                        }
                    },
            ) {
                PynapliTheme(themeMode = "dark") {
                    Box(modifier = Modifier.size(width = 360.dp, height = 560.dp)) {
                        SwipeCardStack(
                            cards = deck,
                            enabled = true,
                            requestedAction = requested(),
                            distanceUnit = "metric",
                            onActionConsumed = onActionConsumed,
                            onOpenProfile = { recorder.opened += it.userId },
                            onLinkedPartnerTap = {},
                            onSwiped = { card, action ->
                                recorder.swiped += card.userId to action
                                deck = deck.filterNot { it.userId == card.userId }
                            },
                            onPineappleGesture = { recorder.pineappleGestures += it.userId },
                        )
                    }
                }
            }
        }
    }

    @Test
    fun aFastFlickRightLikesAndTicksThenCommits() =
        runComposeUiTest {
            val recorder = Recorder()
            setStack(recorder)

            onNodeWithTag(SwipeTestTags.TOP_CARD).performTouchInput {
                swipe(start = center, end = Offset(width * 0.95f, centerY), durationMillis = 120)
            }
            mainClock.advanceTimeBy(600)
            waitForIdle()

            assertEquals(listOf("a" to SwipeAction.LIKE), recorder.swiped)
            assertTrue(recorder.haptics.contains("tick"), "crossing the threshold ticks: ${recorder.haptics}")
            assertEquals("commit", recorder.haptics.last())
        }

    @Test
    fun aShortSlowDragSpringsBack() =
        runComposeUiTest {
            val recorder = Recorder()
            setStack(recorder)

            onNodeWithTag(SwipeTestTags.TOP_CARD).performTouchInput {
                swipe(start = center, end = Offset(centerX + 40f, centerY), durationMillis = 900)
            }
            mainClock.advanceTimeBy(1_000)
            waitForIdle()

            assertTrue(recorder.swiped.isEmpty())
            assertTrue(recorder.haptics.none { it == "commit" })
        }

    @Test
    fun aSwipeUpAsksForThePineappleLikeInsteadOfSpendingIt() =
        runComposeUiTest {
            val recorder = Recorder()
            setStack(recorder)

            onNodeWithTag(SwipeTestTags.TOP_CARD).performTouchInput {
                swipe(start = center, end = Offset(centerX, height * 0.05f), durationMillis = 120)
            }
            mainClock.advanceTimeBy(600)
            waitForIdle()

            assertEquals(listOf("a"), recorder.pineappleGestures)
            assertTrue(recorder.swiped.isEmpty())
        }

    @Test
    fun theActionButtonsPlayTheSameExit() =
        runComposeUiTest {
            val recorder = Recorder()
            var requested by mutableStateOf<String?>(null)
            setStack(recorder, requested = { requested }, onActionConsumed = { requested = null })

            requested = SwipeAction.PASS
            mainClock.advanceTimeBy(600)
            waitForIdle()

            assertEquals(listOf("a" to SwipeAction.PASS), recorder.swiped)
            assertEquals(listOf("commit"), recorder.haptics)
        }

    @Test
    fun tappingTheInfoAreaOpensTheProfile() =
        runComposeUiTest {
            val recorder = Recorder()
            setStack(recorder)

            onNodeWithTag(SwipeTestTags.TOP_CARD).performTouchInput { click(Offset(centerX * 0.5f, height * 0.85f)) }
            onNodeWithTag(SwipeTestTags.OPEN_PROFILE).performClick()
            // The upper half steps through photos instead.
            onNodeWithTag(SwipeTestTags.TOP_CARD).performTouchInput { click(Offset(width * 0.8f, height * 0.3f)) }

            assertEquals(listOf("a", "a"), recorder.opened)
        }
}
