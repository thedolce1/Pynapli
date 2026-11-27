package com.pynapli.app.grid

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test

/** Plan G11: the New marker, the "active Xh ago" caption and the neutral no-photo silhouette. */
class GridCardLivenessMarksTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun newMarkerActiveAgoAndSilhouetteShowOnlyWhenTheyApply() =
        runComposeUiTest {
            val recent =
                GridProfileCardUi(
                    id = "recent",
                    displayName = "Noa",
                    online = false,
                    distanceLabel = "2 km",
                    fresh = true,
                    activeAgo = GridActiveAgo.HOURS_3,
                )
            val online =
                GridProfileCardUi(
                    id = "online",
                    displayName = "Maya",
                    online = true,
                    distanceLabel = "800 m",
                    activeAgo = GridActiveAgo.ONLINE,
                )

            setContent {
                Column {
                    Box(Modifier.size(200.dp)) { GridProfileTile(card = recent, distanceUnit = "km", onClick = {}) }
                    Box(Modifier.size(200.dp)) { GridProfileTile(card = online, distanceUnit = "km", onClick = {}) }
                }
            }
            waitForIdle()

            onNodeWithTag(GridCardTags.newTag("recent"), useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag(GridCardTags.activeAgoTag("recent"), useUnmergedTree = true).assertTextEquals("Active 3h ago")
            onNodeWithTag(GridCardTags.silhouetteTag("recent"), useUnmergedTree = true).assertIsDisplayed()

            onNodeWithTag(GridCardTags.newTag("online"), useUnmergedTree = true).assertDoesNotExist()
            // Online shows the green dot, never an "active ago" caption.
            onNodeWithTag(GridCardTags.activeAgoTag("online"), useUnmergedTree = true).assertDoesNotExist()
        }
}
