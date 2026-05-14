package com.pynapli.app.swipe

import com.pynapli.shared.network.dto.LinkedPartnerMiniDto
import com.pynapli.shared.network.dto.SwipeCardDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SwipeImagePrefetchPlannerTest {

    @Test
    fun emptyCardsReturnsEmpty() {
        assertTrue(planSwipeImagePrefetch(emptyList()).isEmpty())
    }

    @Test
    fun returnsTopWindowPrimaryAndPartnerImagesInCardOrder() {
        val cards = listOf(
            card("a", photoUrl = "https://cdn/a.jpg", partnerUrl = "https://cdn/a-partner.jpg"),
            card("b", photoUrl = "https://cdn/b.jpg"),
            card("c", partnerUrl = "https://cdn/c-partner.jpg"),
            card("d", photoUrl = "https://cdn/d.jpg"),
            card("e", photoUrl = "https://cdn/e.jpg")
        )

        val urls = planSwipeImagePrefetch(cards, windowSize = 4)

        assertEquals(
            listOf(
                "https://cdn/a.jpg",
                "https://cdn/a-partner.jpg",
                "https://cdn/b.jpg",
                "https://cdn/c-partner.jpg",
                "https://cdn/d.jpg"
            ),
            urls
        )
    }

    @Test
    fun deduplicatesRepeatedUrls() {
        val cards = listOf(
            card("a", photoUrl = "https://cdn/shared.jpg"),
            card("b", photoUrl = "https://cdn/shared.jpg", partnerUrl = "https://cdn/shared.jpg"),
            card("c", photoUrl = "https://cdn/other.jpg")
        )

        val urls = planSwipeImagePrefetch(cards, windowSize = 10)

        assertEquals(listOf("https://cdn/shared.jpg", "https://cdn/other.jpg"), urls)
    }

    @Test
    fun nonPositiveWindowSizeReturnsEmpty() {
        val cards = listOf(card("a", photoUrl = "https://cdn/a.jpg"))

        assertTrue(planSwipeImagePrefetch(cards, windowSize = 0).isEmpty())
        assertTrue(planSwipeImagePrefetch(cards, windowSize = -1).isEmpty())
    }
}

private fun card(
    userId: String,
    photoUrl: String? = null,
    partnerUrl: String? = null
): SwipeCardDto =
    SwipeCardDto(
        userId = userId,
        displayName = "User $userId",
        photoUrl = photoUrl,
        linkedPartner = partnerUrl?.let {
            LinkedPartnerMiniDto(
                userId = "$userId-partner",
                displayName = "Partner $userId",
                thumbnailUrl = it
            )
        },
        rankToken = "rank-$userId"
    )
