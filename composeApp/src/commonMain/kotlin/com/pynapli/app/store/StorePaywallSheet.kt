package com.pynapli.app.store

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.pynapli.app.design.PynapliBottomSheet
import com.pynapli.app.design.PynapliSheetTokens
import com.pynapli.app.design.closeOnEscape
import com.pynapli.shared.network.dto.PremiumInventoryType
import com.pynapli.shared.network.dto.SubscriptionTier

/**
 * Where a paywall opens the Store. There is no Store tab (founder decision 2026-11-17): every upsell
 * opens the same plans/store sheet, scrolled to what it sells.
 */
enum class StorePaywallFocus {
    /** The plan list: locked filters, Likes You reveal, read receipts, Psync limits, the profile menu. */
    Plans,

    /** The Social plan: the Grid's locked cards when Social unlocks them. */
    Social,

    /** The Select plan: Travel mode, and the Grid's locked cards when only Select unlocks them. */
    Select,

    /** Pineapple Likes: out of Pineapple Likes. */
    PineappleLikes,
}

/**
 * The Store list row [focus] scrolls to, mirroring the item order in [StoreScreen]'s list, or null
 * when the store hasn't loaded what it should land on (then the sheet opens at the top).
 */
internal fun storeListIndexFor(
    focus: StorePaywallFocus,
    state: StoreUiState,
    purchasesAvailable: Boolean,
): Int? {
    if (state.tiers.isEmpty()) return null
    // current-plan, then manage-on-mobile on read-only clients.
    val partnerBenefit = if (purchasesAvailable) 1 else 2
    // The plans section opens with the linked-partner benefit, then the Plans title.
    val firstTier = partnerBenefit + 2
    // After the tiers, the products title.
    val firstProduct = firstTier + state.tiers.size + 1
    return when (focus) {
        StorePaywallFocus.Plans -> partnerBenefit
        StorePaywallFocus.Social -> tierIndex(state, SubscriptionTier.SOCIAL)?.let { firstTier + it }
        StorePaywallFocus.Select -> tierIndex(state, SubscriptionTier.SELECT)?.let { firstTier + it }
        StorePaywallFocus.PineappleLikes -> productIndex(state, PremiumInventoryType.PINEAPPLE_LIKE)?.let { firstProduct + it }
    }
}

private fun tierIndex(
    state: StoreUiState,
    tier: String,
): Int? = state.tiers.indexOfFirst { it.tier == tier }.takeIf { it >= 0 }

/**
 * The plans sheet focus for the Grid's locked cards, from the server's `unlockTier`: Social or
 * Select's row, or the plan list when the server named neither.
 */
fun storePaywallFocusForUnlockTier(unlockTier: String?): StorePaywallFocus =
    when (unlockTier) {
        // A free viewer lands on the plan list, where "one subscription covers you both" is in view:
        // the 2-for-1 benefit is the strongest reason a couple upgrades. Social members (who already
        // have it) are sent straight to Select.
        SubscriptionTier.SOCIAL -> StorePaywallFocus.Plans
        SubscriptionTier.SELECT -> StorePaywallFocus.Select
        else -> StorePaywallFocus.Plans
    }

private fun productIndex(
    state: StoreUiState,
    inventoryType: String,
): Int? = state.premiumProducts.indexOfFirst { it.inventoryType == inventoryType }.takeIf { it >= 0 }

/**
 * The full plans/store content as a near-full-height sheet, opened from any paywall or the profile
 * menu. Same Store content as before (purchase lock, restore and pending-purchase recovery live in
 * [StoreStateHolder] and the platform gateway, so they behave exactly as they did in the tab).
 */
@Composable
fun StorePaywallSheet(
    stateHolder: StoreStateHolder,
    focus: StorePaywallFocus,
    onDismiss: () -> Unit,
    purchasesAvailable: Boolean = true,
    partnerLink: StorePartnerLink = StorePartnerLink.Unknown,
) {
    PynapliBottomSheet(
        onDismiss = onDismiss,
        modifier = Modifier.testTag(StoreTestTags.PAYWALL_SHEET),
    ) {
        // StoreScreen scrolls its own lazy list, so it gets a fixed-height host rather than the
        // scrolling sheet scaffold.
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(PynapliSheetTokens.TALL_HEIGHT_FRACTION)
                    .closeOnEscape(onDismiss),
        ) {
            StoreScreen(
                stateHolder = stateHolder,
                purchasesAvailable = purchasesAvailable,
                focus = focus,
                onClose = onDismiss,
                partnerLink = partnerLink,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
