package com.pynapli.app.grid

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test

class GridCardTonightIndicatorTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun tonightIndicatorShowsOnlyWhenSessionIsActive() =
        runComposeUiTest {
            val playing =
                GridProfileCardUi(
                    id = "playing",
                    displayName = "Maya",
                    online = true,
                    distanceLabel = "800 m",
                    tone = GridCardTone.Warm,
                    tonightActive = true,
                )
            val quiet =
                GridProfileCardUi(
                    id = "quiet",
                    displayName = "Alex",
                    online = false,
                    distanceLabel = "1.2 km",
                    tone = GridCardTone.Slate,
                    tonightActive = false,
                )

            setContent {
                Column {
                    Box(Modifier.size(200.dp)) {
                        GridProfileTile(card = playing, distanceUnit = "km", onClick = {})
                    }
                    Box(Modifier.size(200.dp)) {
                        GridProfileTile(card = quiet, distanceUnit = "km", onClick = {})
                    }
                }
            }
            waitForIdle()

            onNodeWithTag(GridCardTags.tonightTag("playing"), useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag(GridCardTags.tonightTag("quiet"), useUnmergedTree = true).assertDoesNotExist()
        }
}
