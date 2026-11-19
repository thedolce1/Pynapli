package com.pynapli.app.swipe

import androidx.compose.runtime.Composable
import com.pynapli.shared.network.dto.SwipeDealbreakerKey

@Composable
internal fun SwipeIntentAndVerifiedFilters(
    draft: SwipeFilters,
    onDraftChange: (SwipeFilters) -> Unit,
) {
    SectionTitle("Intent")
    SubsectionHeader(
        title = "Style",
        dealbreakerKey = SwipeDealbreakerKey.DISCOVERY_STYLES,
        eligible = draft.discoveryStyles.isNotEmpty(),
        locked = SwipeDealbreakerKey.DISCOVERY_STYLES in draft.dealbreakers,
        onLockChange = { locked ->
            onDraftChange(
                draft.copy(
                    dealbreakers =
                        toggleDealbreaker(
                            draft.dealbreakers,
                            SwipeDealbreakerKey.DISCOVERY_STYLES,
                            locked,
                        ),
                ),
            )
        },
    )
    IntentStylesSection(
        selected = draft.discoveryStyles,
        onChange = { styles ->
            val dealbreakers =
                if (styles.isEmpty()) {
                    draft.dealbreakers - SwipeDealbreakerKey.DISCOVERY_STYLES
                } else {
                    draft.dealbreakers
                }
            onDraftChange(draft.copy(discoveryStyles = styles, dealbreakers = dealbreakers))
        },
    )
    SubsectionHeader(
        title = "Looking for",
        dealbreakerKey = SwipeDealbreakerKey.INTENTS,
        eligible = draft.intents.isNotEmpty(),
        locked = SwipeDealbreakerKey.INTENTS in draft.dealbreakers,
        onLockChange = { locked ->
            onDraftChange(
                draft.copy(
                    dealbreakers =
                        toggleDealbreaker(
                            draft.dealbreakers,
                            SwipeDealbreakerKey.INTENTS,
                            locked,
                        ),
                ),
            )
        },
    )
    IntentExtendedSection(
        selected = draft.intents,
        onChange = { intents ->
            val dealbreakers =
                if (intents.isEmpty()) {
                    draft.dealbreakers - SwipeDealbreakerKey.INTENTS
                } else {
                    draft.dealbreakers
                }
            onDraftChange(draft.copy(intents = intents, dealbreakers = dealbreakers))
        },
    )

    SectionHeader(
        title = "Verified",
        dealbreakerKey = SwipeDealbreakerKey.VERIFIED,
        // One "verified" lock covers both Verified only and Verified couples only (server relaxation).
        eligible = draft.verifiedOnly || draft.verifiedCouples,
        locked = SwipeDealbreakerKey.VERIFIED in draft.dealbreakers,
        onLockChange = { locked ->
            onDraftChange(
                draft.copy(
                    dealbreakers =
                        toggleDealbreaker(
                            draft.dealbreakers,
                            SwipeDealbreakerKey.VERIFIED,
                            locked,
                        ),
                ),
            )
        },
    )
    VerifiedModeSection(
        verifiedOnly = draft.verifiedOnly,
        verifiedPreferred = draft.verifiedPreferred,
        onChange = { only, preferred ->
            val dealbreakers =
                if (!only && !draft.verifiedCouples) {
                    draft.dealbreakers - SwipeDealbreakerKey.VERIFIED
                } else {
                    draft.dealbreakers
                }
            onDraftChange(
                draft.copy(
                    verifiedOnly = only,
                    verifiedPreferred = preferred,
                    dealbreakers = dealbreakers,
                ),
            )
        },
    )
    VerifiedCouplesSection(
        verifiedCouples = draft.verifiedCouples,
        onToggle = { onDraftChange(draft.withVerifiedCouplesToggled()) },
    )
}

/**
 * Toggles "Verified couples only". Turning it off with "Verified only" also off drops the shared
 * "verified" lock, the same way clearing "Verified only" does.
 */
internal fun SwipeFilters.withVerifiedCouplesToggled(): SwipeFilters {
    val next = !verifiedCouples
    val locks =
        if (!next && !verifiedOnly) this.dealbreakers - SwipeDealbreakerKey.VERIFIED else this.dealbreakers
    return copy(verifiedCouples = next, dealbreakers = locks)
}
