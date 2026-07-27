package com.pynapli.app.swipe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliTheme
import com.pynapli.shared.network.dto.SwipeDealbreakerKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SwipeFiltersSheetInteractionTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun togglingChipAndApplyInvokesOnApplyWithUpdatedFilters() =
        runComposeUiTest {
            var applied: SwipeFilters? = null
            setSheetBodyContent(initial = SwipeFilters.defaults, onApply = { applied = it })

            // FILTERS_SPEC L289-291 Who → singles toggle. Clicking the chip flips draft.singles.
            onNodeWithText("Singles").performScrollTo().performClick()
            waitForIdle()
            onNodeWithTag(SwipeFilterTags.APPLY).performScrollTo().performClick()
            waitForIdle()

            val result = assertNotNull(applied)
            assertTrue(result.singles, "Apply must carry the toggled singles=true")
            assertFalse(result.partnered)
            assertFalse(result.linked)
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun lockingAgeDealbreakerSendsKeyOnApply() =
        runComposeUiTest {
            // Spec L354-371: locking a filter must mark it as a dealbreaker key on the
            // outgoing request. The age dealbreaker chip is only eligible when an age
            // bound is set, so we seed the initial filters with a range.
            var applied: SwipeFilters? = null
            setSheetBodyContent(
                initial = SwipeFilters.defaults.copy(minimumAge = 30, maximumAge = 45),
                onApply = { applied = it },
            )

            onNodeWithTag(SwipeFilterTags.dealbreakerTag(SwipeDealbreakerKey.AGE))
                .performScrollTo()
                .performClick()
            waitForIdle()
            onNodeWithTag(SwipeFilterTags.APPLY).performScrollTo().performClick()
            waitForIdle()

            val result = assertNotNull(applied)
            assertTrue(SwipeDealbreakerKey.AGE in result.dealbreakers)
            assertEquals(30, result.minimumAge)
            assertEquals(45, result.maximumAge)
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun clearingFilterDropsItsDealbreakerAutomatically() =
        runComposeUiTest {
            // Spec L369: dealbreakers must be reversible. If the user clears the
            // underlying filter, the lock must drop too — no orphaned hard gates.
            var applied: SwipeFilters? = null
            setSheetBodyContent(
                initial =
                    SwipeFilters.defaults.copy(
                        discoveryStyles = setOf(com.pynapli.app.grid.DiscoveryStyle.Solo),
                        dealbreakers = setOf(SwipeDealbreakerKey.DISCOVERY_STYLES),
                    ),
                onApply = { applied = it },
            )

            // Deselect the Solo style chip — this should drop DISCOVERY_STYLES from
            // dealbreakers automatically.
            onNodeWithText("Solo").performScrollTo().performClick()
            waitForIdle()
            onNodeWithTag(SwipeFilterTags.APPLY).performScrollTo().performClick()
            waitForIdle()

            val result = assertNotNull(applied)
            assertTrue(result.discoveryStyles.isEmpty())
            assertFalse(
                SwipeDealbreakerKey.DISCOVERY_STYLES in result.dealbreakers,
                "Clearing the underlying filter must drop its dealbreaker lock",
            )
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun advancedPaywallBannerShowsOnlyWhenGatedTagSelected() =
        runComposeUiTest {
            // Spec L373-391: advanced lifestyle filters are gated. The pre-emptive paywall
            // banner (C5) appears the moment the draft enters the gated surface, so the
            // user can subscribe before paying an ENTITLEMENT_REQUIRED round-trip.
            setSheetBodyContent(initial = SwipeFilters.defaults)

            // With defaults, the banner is not part of the composition at all.
            assertEquals(
                0,
                onAllNodesWithTag(SwipeFilterTags.ADVANCED_PAYWALL).fetchSemanticsNodes().size,
                "Paywall banner must not render until a gated tag is selected",
            )

            onNodeWithText("Soft swap").performScrollTo().performClick()
            waitForIdle()

            onNodeWithTag(SwipeFilterTags.ADVANCED_PAYWALL).performScrollTo().assertIsDisplayed()
            onNodeWithTag(SwipeFilterTags.ADVANCED_PAYWALL_SUBSCRIBE).assertIsDisplayed()
        }
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.setSheetBodyContent(
    initial: SwipeFilters,
    onApply: (SwipeFilters) -> Unit = {},
    onClearAll: () -> Unit = {},
    onDismiss: () -> Unit = {},
    onOpenStore: () -> Unit = {},
) {
    setContent {
        PynapliTheme(themeMode = "dark") {
            // Fixed-size container so the verticalScroll inside the body has a stable
            // viewport. The ModalBottomSheet shell is intentionally bypassed — the
            // desktop Compose test harness does not surface its popup content into
            // the same node tree, so we drive SwipeFiltersSheetBody directly.
            Box(modifier = Modifier.size(width = 420.dp, height = 1_400.dp)) {
                SwipeFiltersSheetBody(
                    initial = initial,
                    onDismiss = onDismiss,
                    onApply = onApply,
                    onClearAll = onClearAll,
                    onOpenStore = onOpenStore,
                )
            }
        }
    }
    waitForIdle()
}
