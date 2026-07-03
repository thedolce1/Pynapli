package com.pynapli.app

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliColors
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
                    PynapliTabIconWithBadge(
                        tab = tab,
                        isSelected = isSelected,
                        badgeCount = tab.badgeCount(unreadMessages, activityBadgeCount),
                    )
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

/**
 * Side navigation for Medium+ windows (WEB_PWA_GAMEPLAN.md "Window size classes"):
 * the same five tabs and badges as [PynapliBottomNavigation], stacked in a rail with
 * a hairline end divider. Compact windows keep the bottom bar untouched.
 */
@Composable
internal fun PynapliNavigationRail(
    selectedTab: PynapliTab,
    unreadMessages: Int,
    activityBadgeCount: Int,
    onTabSelected: (PynapliTab) -> Unit,
) {
    Row {
        NavigationRail(
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Spacer(modifier = Modifier.weight(1f))
            PynapliTab.entries.forEach { tab ->
                val isSelected = selectedTab == tab
                NavigationRailItem(
                    selected = isSelected,
                    onClick = { onTabSelected(tab) },
                    icon = {
                        PynapliTabIconWithBadge(
                            tab = tab,
                            isSelected = isSelected,
                            badgeCount = tab.badgeCount(unreadMessages, activityBadgeCount),
                        )
                    },
                    label = { Text(text = tab.label) },
                    colors =
                        NavigationRailItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
        }
        VerticalDivider(thickness = 1.dp, color = PynapliColors.BorderSoft)
    }
}

private fun PynapliTab.badgeCount(
    unreadMessages: Int,
    activityBadgeCount: Int,
): Int =
    when (this) {
        PynapliTab.Messages -> unreadMessages
        PynapliTab.Activity -> activityBadgeCount
        else -> 0
    }

@Composable
private fun PynapliTabIconWithBadge(
    tab: PynapliTab,
    isSelected: Boolean,
    badgeCount: Int,
) {
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
}

private fun Int.toBadgeText(): String = if (this > 9) "9+" else toString()
