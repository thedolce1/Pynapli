package com.pynapli.app.grid

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test

class GridCardVerifiedBadgeTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun verifiedAndCoupleBadgesFollowTheCard() =
        runComposeUiTest {
            val couple =
                GridProfileCardUi(
                    id = "couple",
                    displayName = "Maya",
                    online = false,
                    distanceLabel = null,
                    linkedPartner = GridLinkedPartnerUi(userId = "alex", displayName = "Alex"),
                    linkedPartnerTone = LinkedPartnerTone.Gold,
                    verified = true,
                    coupleVerified = true,
                )
            val plain =
                GridProfileCardUi(
                    id = "plain",
                    displayName = "Una",
                    online = false,
                    distanceLabel = null,
                )

            setContent {
                Column {
                    Box(Modifier.size(200.dp)) { GridProfileTile(card = couple, distanceUnit = "km", onClick = {}) }
                    Box(Modifier.size(200.dp)) { GridProfileTile(card = plain, distanceUnit = "km", onClick = {}) }
                }
            }
            waitForIdle()

            onNodeWithTag(GridCardTags.verifiedTag("couple"), useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag(GridCardTags.verifiedTag("plain"), useUnmergedTree = true).assertDoesNotExist()
            onAllNodesWithContentDescription("Verified couple", useUnmergedTree = true).assertCountEquals(1)
        }
}
