package com.pynapli.app.grid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The presence subscription is capped client-side, favourites first, so the server never cuts them. */
class PresenceSubscriptionTest {
    @Test
    fun favouritesComeFirstThenGridCardsInListOrder() {
        val ids = presenceSubscription(favorites = linkedSetOf("f1", "f2"), grid = linkedSetOf("g1", "f1", "g2"))
        assertEquals(listOf("f1", "f2", "g1", "g2"), ids.toList(), "no duplicates; a favourite on the Grid is sent once")
    }

    @Test
    fun theTotalNeverExceedsTheServerCapAndFavouritesSurvive() {
        val favorites = (1..30).mapTo(LinkedHashSet()) { "fav-$it" }
        val grid = (1..400).mapTo(LinkedHashSet()) { "grid-$it" }

        val ids = presenceSubscription(favorites, grid).toList()

        assertEquals(PRESENCE_SUBSCRIPTION_CAP, ids.size)
        assertEquals(favorites.toList(), ids.take(30), "every favourite is kept, first")
        assertEquals((1..170).map { "grid-$it" }, ids.drop(30), "then the Grid from the top")
    }

    @Test
    fun moreFavouritesThanTheCapKeepTheFirstOnes() {
        val favorites = (1..250).mapTo(LinkedHashSet()) { "fav-$it" }
        val ids = presenceSubscription(favorites, linkedSetOf("grid-1"))
        assertEquals(PRESENCE_SUBSCRIPTION_CAP, ids.size)
        assertTrue("grid-1" !in ids)
    }
}
