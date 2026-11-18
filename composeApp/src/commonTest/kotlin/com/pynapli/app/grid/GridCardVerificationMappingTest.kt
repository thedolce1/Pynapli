package com.pynapli.app.grid

import com.pynapli.shared.network.dto.GridCardDto
import com.pynapli.shared.network.dto.LinkedPartnerMiniDto
import kotlin.test.Test
import kotlin.test.assertEquals

class GridCardVerificationMappingTest {
    @Test
    fun verifiedAndCoupleVerifiedComeFromTheServer() {
        val card =
            GridCardDto(
                userId = "u-1",
                displayName = "Maya",
                online = false,
                rankToken = "grid:v1:u-1",
                linkedPartner = LinkedPartnerMiniDto(userId = "u-2", displayName = "Alex"),
                verified = true,
                coupleVerified = true,
            ).toGridProfileCardUi(index = 0)

        assertEquals(true, card.verified)
        assertEquals(true, card.coupleVerified)
    }

    @Test
    fun coupleVerifiedNeedsAVisiblePartner() {
        // Defensive: a couple flag without a partner mini never draws a couple badge.
        val card =
            GridCardDto(
                userId = "u-1",
                displayName = "Maya",
                online = false,
                rankToken = "grid:v1:u-1",
                verified = true,
                coupleVerified = true,
            ).toGridProfileCardUi(index = 0)

        assertEquals(true, card.verified)
        assertEquals(false, card.coupleVerified)
    }

    @Test
    fun unverifiedByDefault() {
        val card = GridCardDto(userId = "u-1", displayName = "Una", online = true, rankToken = "t").toGridProfileCardUi(index = 1)

        assertEquals(false, card.verified)
        assertEquals(false, card.coupleVerified)
    }
}
