package com.pynapli.app.swipe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliTheme
import com.pynapli.shared.network.dto.SwipeCardDto
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SwipeStageKeyTest {
    private fun sampleCard(id: String) =
        SwipeCardDto(
            userId = id,
            displayName = "Sam",
            displayAge = 31,
            distanceLabel = "1.2 km",
            distanceMeters = 1200,
            photoUrl = null,
            linkedPartner = null,
            rankToken = "swipe:v1:$id",
        )

    @Test
    fun wideStageArrowKeysDriveTheDeck() =
        runComposeUiTest {
            var passes = 0
            var likes = 0
            var pineapples = 0

            setContent {
                PynapliTheme(themeMode = "dark") {
                    Box(modifier = Modifier.size(width = 900.dp, height = 800.dp)) {
                        SwipeScreenContent(
                            state = SwipeUiState(cards = listOf(sampleCard("a"), sampleCard("b"))),
                            travelCityName = null,
                            requestedAction = null,
                            distanceUnit = "metric",
                            onOpenFilters = {},
                            onOpenTravel = {},
                            onOpenStore = {},
                            onFilterSelected = {},
                            onRetryDeck = {},
                            onActionConsumed = {},
                            onOpenProfile = {},
                            onLinkedPartnerTap = {},
                            onSwiped = { _, _ -> },
                            onRetryPendingActions = {},
                            onRewind = {},
                            onPass = { passes += 1 },
                            onIntroNote = {},
                            onPineappleLike = { pineapples += 1 },
                            onLike = { likes += 1 },
                        )
                    }
                }
            }

            onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
            onRoot().performKeyInput { pressKey(Key.DirectionRight) }
            onRoot().performKeyInput { pressKey(Key.DirectionUp) }

            assertEquals(1, passes, "Left arrow passes")
            assertEquals(1, likes, "Right arrow likes")
            assertEquals(1, pineapples, "Up arrow pineapples")
        }

    @Test
    fun compactPaneIgnoresArrowKeys() =
        runComposeUiTest {
            var actions = 0

            setContent {
                PynapliTheme(themeMode = "dark") {
                    Box(modifier = Modifier.size(width = 390.dp, height = 844.dp)) {
                        SwipeScreenContent(
                            state = SwipeUiState(cards = listOf(sampleCard("a"))),
                            travelCityName = null,
                            requestedAction = null,
                            distanceUnit = "metric",
                            onOpenFilters = {},
                            onOpenTravel = {},
                            onOpenStore = {},
                            onFilterSelected = {},
                            onRetryDeck = {},
                            onActionConsumed = {},
                            onOpenProfile = {},
                            onLinkedPartnerTap = {},
                            onSwiped = { _, _ -> },
                            onRetryPendingActions = {},
                            onRewind = {},
                            onPass = { actions += 1 },
                            onIntroNote = {},
                            onPineappleLike = { actions += 1 },
                            onLike = { actions += 1 },
                        )
                    }
                }
            }

            onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
            onRoot().performKeyInput { pressKey(Key.DirectionRight) }
            assertEquals(0, actions, "Phone-width panes leave arrow keys alone")
        }
}
