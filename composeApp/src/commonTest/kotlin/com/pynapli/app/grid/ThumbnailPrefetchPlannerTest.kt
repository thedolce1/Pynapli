package com.pynapli.app.grid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThumbnailPrefetchPlannerTest {
    @Test
    fun emptyCardsReturnsEmpty() {
        assertTrue(planThumbnailPrefetch(emptyList(), lastVisibleIndex = -1).isEmpty())
    }

    @Test
    fun returnsNextWindowAfterLastVisible() {
        val cards = (0 until 30).map { card("id-$it", thumbnail = "https://cdn/$it.jpg") }

        val urls = planThumbnailPrefetch(cards, lastVisibleIndex = 5, windowSize = 4)

        assertEquals(
            listOf("https://cdn/6.jpg", "https://cdn/7.jpg", "https://cdn/8.jpg", "https://cdn/9.jpg"),
            urls,
        )
    }

    @Test
    fun returnsEmptyWhenLastVisibleIsAtEnd() {
        val cards = (0 until 5).map { card("id-$it", thumbnail = "https://cdn/$it.jpg") }
        assertTrue(planThumbnailPrefetch(cards, lastVisibleIndex = 4).isEmpty())
        assertTrue(planThumbnailPrefetch(cards, lastVisibleIndex = 99).isEmpty())
    }

    @Test
    fun skipsCardsWithoutThumbnailUrl() {
        val cards =
            listOf(
                card("0", thumbnail = "https://cdn/0.jpg"),
                card("1", thumbnail = null),
                card("2", thumbnail = "https://cdn/2.jpg"),
                card("3", thumbnail = null),
            )

        val urls = planThumbnailPrefetch(cards, lastVisibleIndex = -1, windowSize = 10)

        assertEquals(listOf("https://cdn/0.jpg", "https://cdn/2.jpg"), urls)
    }

    @Test
    fun deduplicatesRepeatedUrls() {
        val cards =
            listOf(
                card("a", thumbnail = "https://cdn/shared.jpg"),
                card("b", thumbnail = "https://cdn/shared.jpg"),
                card("c", thumbnail = "https://cdn/other.jpg"),
            )

        val urls = planThumbnailPrefetch(cards, lastVisibleIndex = -1, windowSize = 10)

        assertEquals(listOf("https://cdn/shared.jpg", "https://cdn/other.jpg"), urls)
    }

    @Test
    fun windowSizeLargerThanRemainderReturnsWhatIsLeft() {
        val cards = (0 until 8).map { card("id-$it", thumbnail = "https://cdn/$it.jpg") }

        val urls = planThumbnailPrefetch(cards, lastVisibleIndex = 4, windowSize = 50)

        assertEquals(
            listOf("https://cdn/5.jpg", "https://cdn/6.jpg", "https://cdn/7.jpg"),
            urls,
        )
    }

    @Test
    fun nonPositiveWindowSizeReturnsEmpty() {
        val cards = (0 until 5).map { card("id-$it", thumbnail = "https://cdn/$it.jpg") }
        assertTrue(planThumbnailPrefetch(cards, lastVisibleIndex = 0, windowSize = 0).isEmpty())
        assertTrue(planThumbnailPrefetch(cards, lastVisibleIndex = 0, windowSize = -3).isEmpty())
    }

    @Test
    fun lockedCardsAreNeverPrefetched() {
        val cards =
            listOf(
                card("id-0", thumbnail = "https://cdn/0.jpg"),
                card("id-1", thumbnail = "https://cdn/1.jpg"),
                card("locked_a", thumbnail = "https://cdn/locked-a.jpg"),
                card("locked_b", thumbnail = "https://cdn/locked-b.jpg"),
            )
        assertEquals(listOf("https://cdn/1.jpg"), planThumbnailPrefetch(cards, lastVisibleIndex = 0))
    }

    private fun card(
        id: String,
        thumbnail: String?,
    ): GridProfileCardUi =
        GridProfileCardUi(
            id = id,
            displayName = "User $id",
            thumbnailUrl = thumbnail,
            online = false,
            distanceLabel = null,
        )
}
