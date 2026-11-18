package com.pynapli.app.match

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.pynapli.app.design.PynapliTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class MatchCelebrationScreenTest {
    private val maya =
        MatchCelebrationUi(
            matchIds = listOf("m-1"),
            matchedUserId = "maya",
            matchedDisplayName = "Maya",
            matchedPhotoUrl = null,
            viewerPhotoUrl = null,
        )

    @Test
    fun showsTheMatchAndBothActions() =
        runComposeUiTest {
            var messaged = 0
            var kept = 0
            setContent {
                PynapliTheme(themeMode = "dark") {
                    MatchCelebrationScreen(celebration = maya, showNames = true, onSendMessage = { messaged++ }, onKeepSwiping = { kept++ })
                }
            }

            onNodeWithTag(MatchCelebrationTags.TITLE).assertTextEquals("It's a match")
            onNodeWithTag(MatchCelebrationTags.SUBTITLE).assertTextEquals("You and Maya liked each other.")
            onNodeWithTag(MatchCelebrationTags.MESSAGE).performClick()
            onNodeWithTag(MatchCelebrationTags.KEEP_SWIPING).performClick()

            assertEquals(1, messaged)
            assertEquals(1, kept)
        }

    @Test
    fun aPsyncPairReadsAsTheCoupleTogether() =
        runComposeUiTest {
            setContent {
                PynapliTheme(themeMode = "dark") {
                    MatchCelebrationScreen(
                        celebration = maya.copy(matchIds = listOf("m-1", "m-2"), pairUserId = "alex", pairDisplayName = "Alex"),
                        showNames = true,
                        onSendMessage = {},
                        onKeepSwiping = {},
                    )
                }
            }

            onNodeWithTag(MatchCelebrationTags.SUBTITLE).assertTextEquals("Maya & Alex liked you together.")
            onNodeWithTag(MatchCelebrationTags.PAIR_PHOTO).assertExists()
        }

    @Test
    fun discreetDetailKeepsNamesOff() =
        runComposeUiTest {
            setContent {
                PynapliTheme(themeMode = "dark") {
                    MatchCelebrationScreen(celebration = maya, showNames = false, onSendMessage = {}, onKeepSwiping = {})
                }
            }

            onNodeWithTag(MatchCelebrationTags.SUBTITLE).assertTextEquals("You both liked each other.")
        }
}
