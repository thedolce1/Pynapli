package com.pynapli.server.swipe

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class SwipeDeckBoostPlacementTest {
    private fun row(
        name: String,
        boosted: Boolean = false,
    ) = DeckRankRow(
        userId = UUID.nameUUIDFromBytes(name.toByteArray()),
        displayName = name,
        displayAge = 30,
        bio = null,
        tags = emptyList(),
        verified = false,
        visiting = false,
        showDistance = true,
        distanceMeters = 1_000.0,
        isBoosted = boosted,
        softScore = 0,
    )

    private fun List<DeckRankRow>.names() = map { it.displayName }

    @Test
    fun liftsBoostedPeopleIntoOneSlotInEveryFour() {
        val regular = (1..10).map { row("r$it") }
        val boosted = (1..5).map { row("b$it", boosted = true) }

        val page = SwipeDeckBoostPlacement.arrange(regular + boosted, limit = 10)

        assertEquals(listOf("b1", "r1", "r2", "r3", "b2", "r4", "r5", "r6", "b3", "r7"), page.names())
    }

    @Test
    fun boostedPeopleWhoAreNearAnywayStillCountTowardTheCap() {
        val rows = listOf(row("r1"), row("b1", boosted = true), row("b2", boosted = true), row("r2"), row("r3"), row("r4"), row("r5"))

        val page = SwipeDeckBoostPlacement.arrange(rows, limit = 6)

        assertEquals(listOf("b1", "r1", "r2", "r3", "b2", "r4"), page.names())
    }

    @Test
    fun fillsWithBoostedPeopleOnceNoOneElseIsLeft() {
        val rows = listOf(row("r1"), row("b1", boosted = true), row("b2", boosted = true), row("b3", boosted = true))

        assertEquals(listOf("b1", "r1", "b2", "b3"), SwipeDeckBoostPlacement.arrange(rows, limit = 10).names())
    }

    @Test
    fun leavesAnUnboostedDeckInItsNaturalOrder() {
        val rows = (1..6).map { row("r$it") }

        assertEquals(rows.take(4), SwipeDeckBoostPlacement.arrange(rows, limit = 4))
    }
}
