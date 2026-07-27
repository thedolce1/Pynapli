package com.pynapli.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliTheme
import kotlin.test.Test
import kotlin.test.assertEquals

class PynapliNavigationRailTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun railShowsAllTabsWithBadgesAndSelectsOnClick() =
        runComposeUiTest {
            val selections = mutableListOf<PynapliTab>()

            setContent {
                PynapliTheme(themeMode = "dark") {
                    Box(modifier = Modifier.size(width = 96.dp, height = 844.dp)) {
                        PynapliNavigationRail(
                            selectedTab = PynapliTab.Grid,
                            unreadMessages = 12,
                            activityBadgeCount = 3,
                            onTabSelected = { selections += it },
                        )
                    }
                }
            }

            PynapliTab.entries.forEach { tab ->
                onNodeWithText(tab.label).assertExists()
            }
            // Unread caps at 9+ like the bottom bar; Activity shows the raw count.
            // Badge text merges into the tab node's semantics, so read the unmerged tree.
            onNodeWithText("9+", useUnmergedTree = true).assertExists()
            onNodeWithText("3", useUnmergedTree = true).assertExists()

            onNodeWithText("Messages").performClick()
            assertEquals(listOf(PynapliTab.Messages), selections)
        }
}
