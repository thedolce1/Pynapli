package com.pynapli.shared.grid.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GridCardTest {
    @Test
    fun gridCardRepresentsOneAutonomousUser() {
        val card =
            GridCard(
                userId = "user-1",
                displayName = "Alex",
                thumbnailUrl = null,
                online = true,
                distanceMetersRounded = 1000,
                linkedPartnerUserId = "user-2",
                linkedPartnerThumbUrl = null,
            )

        assertEquals("user-1", card.userId)
        assertEquals("user-2", card.linkedPartnerUserId)
        assertTrue(card.online)
    }
}
