package com.pynapli.app.store

import com.pynapli.app.profilelink.ProfileLinkUi
import com.pynapli.app.profilelink.ProfileLinksUiState
import com.pynapli.shared.network.dto.EntitlementSource
import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import com.pynapli.shared.network.dto.SubscriptionTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StorePartnerBenefitCopyTest {
    private val free =
        SubscriptionEntitlementDto(
            tier = SubscriptionTier.FREE,
            source = EntitlementSource.FREE,
            displayName = "Pynapli Free",
            linkedPartnerIncluded = false,
        )

    private val ownSelect =
        SubscriptionEntitlementDto(
            tier = SubscriptionTier.SELECT,
            source = EntitlementSource.OWN_SUBSCRIPTION,
            displayName = "Pynapli Select",
            linkedPartnerIncluded = true,
            sourceUserId = "viewer",
        )

    private val coveredByPartner =
        SubscriptionEntitlementDto(
            tier = SubscriptionTier.SOCIAL,
            source = EntitlementSource.LINKED_PARTNER_BENEFIT,
            displayName = "Pynapli Social",
            linkedPartnerIncluded = true,
            sourceUserId = "partner-1",
            linkedPartnerUserId = "partner-1",
        )

    // The server sets linkedPartnerIncluded on every paid entitlement, promo included, but a promo
    // grant never reaches a partner.
    private val promoSelect =
        SubscriptionEntitlementDto(
            tier = SubscriptionTier.SELECT,
            source = EntitlementSource.PROMO,
            displayName = "Pynapli Select",
            linkedPartnerIncluded = true,
            sourceUserId = "viewer",
        )

    private val linkedToSam = StorePartnerLink.Linked(partnerUserId = "partner-1", partnerDisplayName = "Sam")

    @Test
    fun freeAndUnlinkedLeadsWithTheBenefitAndStillSpeaksToSingles() {
        val copy = storePartnerBenefitCopy(free, StorePartnerLink.None)

        assertEquals(ONE_SUBSCRIPTION_HEADLINE, copy.headline)
        assertEquals("Link your partner and they get the same plan at no extra cost.", copy.supporting)
        assertEquals("Solo? Every plan works just as well for one.", copy.secondary)
    }

    @Test
    fun freeAndLinkedNamesThePartner() {
        val copy = storePartnerBenefitCopy(free, linkedToSam)

        assertEquals(ONE_SUBSCRIPTION_HEADLINE, copy.headline)
        assertEquals("Choose Social or Select and Sam gets the same plan at no extra cost while you're linked.", copy.supporting)
        assertNull(copy.secondary)
    }

    @Test
    fun aBlankOrLongPartnerNameStaysGeneric() {
        listOf(" ", null, "A display name far too long to fit").forEach { name ->
            val link = StorePartnerLink.Linked(partnerUserId = "partner-1", partnerDisplayName = name)

            assertEquals(
                "Choose Social or Select and your linked partner gets the same plan at no extra cost.",
                storePartnerBenefitCopy(free, link).supporting,
            )
            assertEquals("Your plan covers your partner too", storePartnerBenefitCopy(ownSelect, link).headline)
            assertEquals("Covers your linked partner too", currentPlanCoverageLine(ownSelect, link))
        }
    }

    @Test
    fun linksStillLoadingKeepTheCopyNeutral() {
        val copy = storePartnerBenefitCopy(free, StorePartnerLink.Unknown)

        assertEquals(ONE_SUBSCRIPTION_HEADLINE, copy.headline)
        assertEquals("Social and Select cover a linked partner at no extra cost.", copy.supporting)
        assertNull(copy.secondary)
    }

    @Test
    fun ownSubscriptionWithALinkedPartnerSaysItCoversThemNow() {
        val copy = storePartnerBenefitCopy(ownSelect, linkedToSam)

        assertEquals("Your plan covers you and Sam", copy.headline)
        assertEquals("Sam gets the same plan at no extra cost while you're linked.", copy.supporting)
        assertEquals("Covers Sam too", currentPlanCoverageLine(ownSelect, linkedToSam))
    }

    @Test
    fun ownSubscriptionWithoutALinkInvitesLinking() {
        val copy = storePartnerBenefitCopy(ownSelect, StorePartnerLink.None)

        assertEquals("Your plan can cover your partner too", copy.headline)
        assertEquals("Link your partner and they get the same plan at no extra cost.", copy.supporting)
        assertEquals("Link your partner to cover them too", currentPlanCoverageLine(ownSelect, StorePartnerLink.None))
    }

    @Test
    fun aViewerCoveredByTheirPartnerIsToldSo() {
        assertEquals("You're covered by Sam's plan", storePartnerBenefitCopy(coveredByPartner, linkedToSam).headline)
        assertEquals("Covered by Sam's plan", currentPlanCoverageLine(coveredByPartner, linkedToSam))

        // The name is used only when the subscriber is the partner linked now.
        val otherLink = StorePartnerLink.Linked(partnerUserId = "someone-else", partnerDisplayName = "Kim")
        assertEquals("You're covered by your partner's plan", storePartnerBenefitCopy(coveredByPartner, otherLink).headline)
        assertEquals("Covered by your partner's plan", currentPlanCoverageLine(coveredByPartner, StorePartnerLink.Unknown))
    }

    @Test
    fun aPromoPlanNeverClaimsToCoverThePartner() {
        val copy = storePartnerBenefitCopy(promoSelect, linkedToSam)

        assertEquals(ONE_SUBSCRIPTION_HEADLINE, copy.headline)
        assertEquals("Choose Social or Select and Sam gets the same plan at no extra cost while you're linked.", copy.supporting)
        assertEquals("Covers your account only", currentPlanCoverageLine(promoSelect, linkedToSam))
        assertEquals("Core discovery and safety included", currentPlanCoverageLine(free, linkedToSam))
    }

    @Test
    fun theLinkComesFromTheLoadedProfileLinks() {
        val link =
            ProfileLinkUi(
                linkId = "link-1",
                otherUserId = "partner-1",
                otherDisplayName = "Sam",
                otherPhotoUrl = null,
                status = "active",
                direction = "current",
                createdAt = "2026-11-01T00:00:00Z",
                acceptedAt = "2026-11-01T00:00:00Z",
                unlinkedAt = null,
            )

        assertEquals(linkedToSam, ProfileLinksUiState(current = link).toStorePartnerLink())
        assertEquals(StorePartnerLink.Unknown, ProfileLinksUiState(isLoading = true).toStorePartnerLink())
        assertEquals(StorePartnerLink.None, ProfileLinksUiState().toStorePartnerLink())
    }
}
