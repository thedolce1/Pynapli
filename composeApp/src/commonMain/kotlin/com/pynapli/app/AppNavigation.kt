package com.pynapli.app

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliMotion
import com.pynapli.app.design.PynapliTabIcon
import com.pynapli.app.design.PynapliTabIcons

internal enum class PynapliTab(
    val label: String,
    val icons: PynapliTabIcon,
) {
    Grid("Grid", PynapliTabIcons.Grid),
    Swipe("Swipe", PynapliTabIcons.Swipe),
    Activity("Activity", PynapliTabIcons.Activity),
    Messages("Messages", PynapliTabIcons.Messages),
    Store("Store", PynapliTabIcons.Store),
}

@Composable
internal fun PynapliBottomNavigation(
    selectedTab: PynapliTab,
    unreadMessages: Int,
    activityBadgeCount: Int,
    onTabSelected: (PynapliTab) -> Unit,
) {
    NavigationBar(
        modifier = Modifier.navigationBarsPadding(),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
    ) {
        PynapliTab.entries.forEach { tab ->
            val enabled =
                tab == PynapliTab.Grid ||
                    tab == PynapliTab.Swipe ||
                    tab == PynapliTab.Activity ||
                    tab == PynapliTab.Messages ||
                    tab == PynapliTab.Store
            val isSelected = selectedTab == tab
            NavigationBarItem(
                enabled = enabled,
                selected = isSelected,
                onClick = { if (enabled) onTabSelected(tab) },
                icon = {
                    val badgeCount =
                        when (tab) {
                            PynapliTab.Messages -> unreadMessages
                            PynapliTab.Activity -> activityBadgeCount
                            else -> 0
                        }
                    val iconVector = if (isSelected) tab.icons.selected else tab.icons.unselected
                    if (badgeCount > 0) {
                        // Pop the badge when it appears or its count increments.
                        val badgePop = remember { Animatable(1f) }
                        LaunchedEffect(badgeCount) {
                            badgePop.snapTo(1.3f)
                            badgePop.animateTo(targetValue = 1f, animationSpec = PynapliMotion.popSpringFloat())
                        }
                        BadgedBox(
                            badge = {
                                Badge(
                                    modifier =
                                        Modifier.graphicsLayer {
                                            scaleX = badgePop.value
                                            scaleY = badgePop.value
                                        },
                                ) {
                                    Text(text = badgeCount.toBadgeText())
                                }
                            },
                        ) {
                            Icon(
                                imageVector = iconVector,
                                contentDescription = tab.label,
                            )
                        }
                    } else {
                        Icon(
                            imageVector = iconVector,
                            contentDescription = tab.label,
                        )
                    }
                },
                label = { Text(text = tab.label) },
                colors =
                    NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
            )
        }
    }
}

private fun Int.toBadgeText(): String = if (this > 9) "9+" else toString()
