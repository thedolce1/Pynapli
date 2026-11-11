package com.pynapli.app.grid

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.pynapli.app.design.PynapliTheme
import kotlin.test.Test
import kotlin.test.assertTrue

class GridTravelRowTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun saysWhichCityTheGridIsShowingAndTurnsTravelOff() =
        runComposeUiTest {
            var turnedOff = false
            setContent {
                PynapliTheme(themeMode = "dark") {
                    GridTravelRow(city = "Las Vegas", onTurnOff = { turnedOff = true })
                }
            }

            onNodeWithText("Browsing Las Vegas · Travel mode").assertExists()
            onNodeWithTag(GridTravelTags.TURN_OFF).performClick()
            assertTrue(turnedOff)
        }
}
