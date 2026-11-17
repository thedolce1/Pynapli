package com.pynapli.server.activity

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PairLikeFoldTest {
    private val maya = UUID.randomUUID()
    private val alex = UUID.randomUUID()
    private val kai = UUID.randomUUID()

    private fun like(
        actor: UUID,
        name: String,
        pair: UUID? = null,
        pairName: String? = null,
        session: String? = null,
    ) = ActivityEventRecord(
        eventId = UUID.randomUUID(),
        type = "swipe_like",
        actorUserId = actor,
        actorDisplayName = name,
        actorProfilePhotoMediaAssetId = null,
        count = 1,
        occurredAt = Instant.parse("2026-11-13T12:00:00Z"),
        seen = false,
        showDistance = false,
        distanceMeters = null,
        albumRequestStatus = null,
        matchId = null,
        conversationId = null,
        pairUserId = pair,
        pairDisplayName = pairName,
        pairSessionId = session,
    )

    @Test
    fun aCouplesTwoLikesFoldIntoOneRowAndOtherLikesAreUntouched() {
        val events =
            listOf(
                like(maya, "Maya", pair = alex, pairName = "Alex", session = "s1"),
                like(kai, "Kai"),
                like(alex, "Alex", pair = maya, pairName = "Maya", session = "s1"),
            )

        val folded = events.foldPairLikes(blockedRelatedIds = emptySet())

        assertEquals(listOf("Maya", "Kai"), folded.map { it.actorDisplayName })
        assertEquals("Alex", folded.first().pairDisplayName)
    }

    @Test
    fun aBlockedOrMissingPartnerLeavesAnOrdinaryLike() {
        val blocked = listOf(like(maya, "Maya", pair = alex, pairName = "Alex", session = "s1"), like(alex, "Alex", pair = maya, pairName = "Maya", session = "s1"))
        val onlyOneListed = listOf(like(maya, "Maya", pair = alex, pairName = "Alex", session = "s1"))

        // The recipient blocked Alex: Maya's like stays, unnamed as a pair (Alex's own row is
        // already filtered out before this by the block filter).
        assertNull(blocked.foldPairLikes(blockedRelatedIds = setOf(alex)).first().pairDisplayName)
        assertNull(onlyOneListed.foldPairLikes(blockedRelatedIds = emptySet()).single().pairDisplayName)
    }
}
