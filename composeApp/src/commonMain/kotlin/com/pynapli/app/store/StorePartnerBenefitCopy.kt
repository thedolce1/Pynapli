package com.pynapli.app.store

import androidx.compose.runtime.Immutable
import com.pynapli.app.profilelink.ProfileLinksUiState
import com.pynapli.shared.network.dto.EntitlementSource
import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import com.pynapli.shared.network.dto.SubscriptionTier

/**
 * The viewer's partner link as the Store copy needs it, from the profile-links summary the app
 * already fetched at sign-in ([ProfileLinksUiState]); the Store never fetches links itself.
 */
sealed interface StorePartnerLink {
    /** Links haven't loaded yet: copy stays neutral rather than guessing. */
    data object Unknown : StorePartnerLink

    data object None : StorePartnerLink

    @Immutable
    data class Linked(
        val partnerUserId: String,
        val partnerDisplayName: String?,
    ) : StorePartnerLink
}

internal fun ProfileLinksUiState.toStorePartnerLink(): StorePartnerLink =
    current?.let { link -> StorePartnerLink.Linked(partnerUserId = link.otherUserId, partnerDisplayName = link.otherDisplayName) }
        ?: if (isLoading) StorePartnerLink.Unknown else StorePartnerLink.None

/** The linked-partner callout that leads the plans section of the Store sheet. */
@Immutable
data class StorePartnerBenefitCopy(
    val headline: String,
    val supporting: String,
    val secondary: String? = null,
)

/**
 * Wording only; the rule is the server's. A partner shares a plan only when it is the subscriber's
 * own paid subscription (JdbcSubscriptionEntitlementSql: the partner branch reads `subscriptions`,
 * never promo/admin grants) and the link is active. `linkedPartnerIncluded` on the entitlement is
 * true for every paid source, so it can't be used to say a partner is covered.
 */
internal fun storePartnerBenefitCopy(
    entitlement: SubscriptionEntitlementDto?,
    partnerLink: StorePartnerLink,
): StorePartnerBenefitCopy {
    val linked = partnerLink as? StorePartnerLink.Linked
    val name = linked?.let { partnerCopyName(it.partnerDisplayName) }
    return when {
        entitlement.isOwnPaidSubscription() ->
            when {
                linked != null && name != null ->
                    StorePartnerBenefitCopy(
                        headline = "Your plan covers you and $name",
                        supporting = "$name gets the same plan at no extra cost while you're linked.",
                        secondary = SEPARATE_ACCOUNTS,
                    )
                linked != null ->
                    StorePartnerBenefitCopy(
                        headline = "Your plan covers your partner too",
                        supporting = "Your linked partner gets the same plan at no extra cost while you're linked.",
                        secondary = SEPARATE_ACCOUNTS,
                    )
                partnerLink == StorePartnerLink.None ->
                    StorePartnerBenefitCopy(
                        headline = "Your plan can cover your partner too",
                        supporting = "Link your partner and they get the same plan at no extra cost.",
                        secondary = SEPARATE_ACCOUNTS,
                    )
                else ->
                    StorePartnerBenefitCopy(
                        headline = "Your plan can cover your partner too",
                        supporting = "A linked partner gets the same plan at no extra cost.",
                        secondary = SEPARATE_ACCOUNTS,
                    )
            }

        entitlement?.source == EntitlementSource.LINKED_PARTNER_BENEFIT -> {
            // Name the subscriber only when they are the partner this viewer is linked to now.
            val subscriberName = name?.takeIf { linked?.partnerUserId == entitlement?.linkedPartnerUserId }
            StorePartnerBenefitCopy(
                headline = subscriberName?.let { "You're covered by $it's plan" } ?: "You're covered by your partner's plan",
                supporting = "You get the same plan at no extra cost while you're linked.",
                secondary = SEPARATE_ACCOUNTS,
            )
        }

        else ->
            StorePartnerBenefitCopy(
                headline = ONE_SUBSCRIPTION_HEADLINE,
                supporting =
                    when {
                        linked != null && name != null -> "Choose Social or Select and $name gets the same plan at no extra cost while you're linked."
                        linked != null -> "Choose Social or Select and your linked partner gets the same plan at no extra cost."
                        partnerLink == StorePartnerLink.None -> "Link your partner and they get the same plan at no extra cost."
                        else -> "Social and Select cover a linked partner at no extra cost."
                    },
                secondary = SOLO_LINE.takeIf { partnerLink == StorePartnerLink.None },
            )
    }
}

/** The second line of the current-plan card: who this plan covers. */
internal fun currentPlanCoverageLine(
    entitlement: SubscriptionEntitlementDto?,
    partnerLink: StorePartnerLink,
): String {
    val linked = partnerLink as? StorePartnerLink.Linked
    val name = linked?.let { partnerCopyName(it.partnerDisplayName) }
    return when {
        entitlement.isOwnPaidSubscription() ->
            when {
                linked != null && name != null -> "Covers $name too"
                linked != null -> "Covers your linked partner too"
                partnerLink == StorePartnerLink.None -> "Link your partner to cover them too"
                else -> "Covers a linked partner too"
            }
        entitlement?.source == EntitlementSource.LINKED_PARTNER_BENEFIT -> {
            val subscriberName = name?.takeIf { linked?.partnerUserId == entitlement?.linkedPartnerUserId }
            subscriberName?.let { "Covered by $it's plan" } ?: "Covered by your partner's plan"
        }
        // Promo and admin grants are the viewer's alone: the partner benefit reads subscriptions only.
        entitlement != null && entitlement.tier != SubscriptionTier.FREE -> "Covers your account only"
        else -> "Core discovery and safety included"
    }
}

/** The headline a free viewer sees; the Android Store QA golden pins it. */
internal const val ONE_SUBSCRIPTION_HEADLINE = "One subscription covers you both"

/** Paid tier rows: the partner line, listed first. */
internal const val TIER_PARTNER_LINE = "Covers your linked partner too"

private const val SEPARATE_ACCOUNTS = "Your accounts stay separate."
private const val SOLO_LINE = "Solo? Every plan works just as well for one."

private const val MAX_NAME_LENGTH = 20

private fun SubscriptionEntitlementDto?.isOwnPaidSubscription(): Boolean = this != null && source == EntitlementSource.OWN_SUBSCRIPTION && tier != SubscriptionTier.FREE

/**
 * The partner's display name for copy, or null to stay generic. Display names are free text (a
 * couple may use "Sam & Alex"), so the whole name is used, and only when it is short enough to fit.
 */
private fun partnerCopyName(displayName: String?): String? = displayName?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_NAME_LENGTH }
