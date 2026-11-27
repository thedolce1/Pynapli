package com.pynapli.app.profile

import com.pynapli.shared.network.dto.LinkedPartnerMiniDto
import com.pynapli.shared.network.dto.PublicProfileDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The profile sheet renders the server's booleans; it never counts or guesses. */
class ProfileMetInPersonBadgeTest {
    private val profile =
        PublicProfileDto(
            userId = "sam",
            displayName = "Sam",
            discoveryStyle = "social",
            allowGridMessages = true,
            allowFlirts = true,
            allowAlbumRequests = true,
        )

    @Test
    fun noBadgeUnlessTheServerSaysSo() {
        assertNull(profile.metInPersonBadgeUi())
    }

    @Test
    fun thePersonBadgeCarriesTheHelperText() {
        val badge = profile.copy(metInPerson = true).metInPersonBadgeUi()
        assertEquals("Met in person", badge?.label)
        assertEquals("Confirmed by several members who've met them. Not checked by Pynapli.", badge?.helper)
    }

    @Test
    fun theCoupleVariantNeedsAVisiblePartner() {
        val couple = profile.copy(metInPerson = true, metInPersonAsCouple = true)
        assertEquals("Met in person", couple.metInPersonBadgeUi()?.label)
        val withPartner = couple.copy(linkedPartner = LinkedPartnerMiniDto(userId = "riley", displayName = "Riley"))
        assertEquals("Met in person as a couple", withPartner.metInPersonBadgeUi()?.label)
    }
}
