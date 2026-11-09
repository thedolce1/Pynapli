package com.pynapli.server.swipe

import com.pynapli.server.discovery.DiscoveryFilters
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * Pins the swipe deck-session / card / cursor token security contract directly on the codec.
 * Unlike the grid/activity scope-hash cursors, these tokens carry the viewer id explicitly and a
 * generated/expiry window, so the contract proves: HMAC tamper-evidence, explicit viewer + target
 * binding, deck-session linkage, and time-based expiry (STALE).
 */
class SwipeDeckTokenCodecTest {
    private val codec = SwipeDeckTokenCodec("test-swipe-deck-secret")
    private val viewer = UUID.randomUUID()
    private val target = UUID.randomUUID()
    private val now: Instant = Instant.parse("2026-06-15T12:00:00Z")
    private val scopeHash = SwipeDeckTokenCodec.deckScopeHash(viewer, radiusMeters = 50_000, limit = 30, filters = DiscoveryFilters())

    private fun issuedSessionWithCard(): Pair<IssuedSwipeDeckSession, String> {
        val session = codec.issueDeckSession(viewer, scopeHash, now)
        val card =
            codec.issueCardToken(
                deckSessionId = session.deckSessionId,
                viewerUserId = viewer,
                targetUserId = target,
                scopeHash = scopeHash,
                generatedAt = session.generatedAt,
                expiresAt = session.expiresAt,
                position = 0,
            )
        return session to card
    }

    @Test
    fun validateActionAcceptsMatchingTokens() {
        val (session, card) = issuedSessionWithCard()
        val validated = codec.validateAction(session.deckSessionId, card, viewer, target, now)
        assertEquals(session.sessionId, validated.sessionId)
        assertEquals(scopeHash, validated.scopeHash)
    }

    @Test
    fun deckScopeHashDistinguishesEverySetOrOrderDefiningFilter() {
        val base = DiscoveryFilters()
        val baseHash = SwipeDeckTokenCodec.deckScopeHash(viewer, radiusMeters = 50_000, limit = 30, filters = base)
        // Each variant changes the deck's candidate set or order, so each must yield a distinct scope
        // hash — otherwise a deck token could carry across the change. The first four (genders,
        // orientations, withPhotosOnly, verifiedPreferred) were previously omitted from the hash.
        val variants =
            listOf(
                base.copy(genders = setOf("woman")),
                base.copy(orientations = setOf("bisexual")),
                base.copy(withPhotosOnly = true),
                base.copy(verifiedPreferred = true),
                base.copy(verifiedOnly = true),
                base.copy(minimumAge = 25),
                base.copy(intents = setOf("dating")),
            )
        variants.forEach { variant ->
            assertNotEquals(
                baseHash,
                SwipeDeckTokenCodec.deckScopeHash(viewer, radiusMeters = 50_000, limit = 30, filters = variant),
            )
        }
    }

    @Test
    fun rejectsDeckSessionReplayedByAnotherViewer() {
        val (session, card) = issuedSessionWithCard()
        val error =
            assertFailsWith<SwipeDeckTokenException> {
                codec.validateAction(session.deckSessionId, card, UUID.randomUUID(), target, now)
            }
        assertEquals(SwipeDeckTokenCodec.INVALID_DECK_SESSION, error.code)
    }

    @Test
    fun rejectsCardTokenForADifferentTarget() {
        val (session, card) = issuedSessionWithCard()
        val error =
            assertFailsWith<SwipeDeckTokenException> {
                codec.validateAction(session.deckSessionId, card, viewer, UUID.randomUUID(), now)
            }
        assertEquals(SwipeDeckTokenCodec.INVALID_CARD_TOKEN, error.code)
    }

    @Test
    fun rejectsExpiredDeckSession() {
        val (session, card) = issuedSessionWithCard()
        val afterExpiry = now.plus(Duration.ofMinutes(30)).plusSeconds(1)
        val error =
            assertFailsWith<SwipeDeckTokenException> {
                codec.validateAction(session.deckSessionId, card, viewer, target, afterExpiry)
            }
        assertEquals(SwipeDeckTokenCodec.STALE_DECK_SESSION, error.code)
    }

    @Test
    fun rejectsTamperedDeckSession() {
        val (session, card) = issuedSessionWithCard()
        val error =
            assertFailsWith<SwipeDeckTokenException> {
                codec.validateAction(tamperPayloadSegment(session.deckSessionId), card, viewer, target, now)
            }
        assertEquals(SwipeDeckTokenCodec.INVALID_DECK_SESSION, error.code)
    }

    @Test
    fun rejectsForeignSecret() {
        val (session, card) = issuedSessionWithCard()
        val foreign = SwipeDeckTokenCodec("a-different-secret")
        val error =
            assertFailsWith<SwipeDeckTokenException> {
                foreign.validateAction(session.deckSessionId, card, viewer, target, now)
            }
        assertEquals(SwipeDeckTokenCodec.INVALID_DECK_SESSION, error.code)
    }

    @Test
    fun cursorRoundTripsAndIsViewerBoundAndExpiring() {
        val session = codec.issueDeckSession(viewer, scopeHash, now)
        val cursor = codec.issueCursor(session, viewer, scopeHash, nextPosition = 10)

        assertEquals(10, codec.decodeCursor(cursor, viewer, now).nextPosition)

        assertEquals(
            SwipeDeckTokenCodec.INVALID_CURSOR,
            assertFailsWith<SwipeDeckTokenException> { codec.decodeCursor(cursor, UUID.randomUUID(), now) }.code,
        )
        assertEquals(
            SwipeDeckTokenCodec.STALE_DECK_SESSION,
            assertFailsWith<SwipeDeckTokenException> {
                codec.decodeCursor(cursor, viewer, now.plus(Duration.ofMinutes(31)))
            }.code,
        )
    }

    private fun tamperPayloadSegment(token: String): String {
        val parts = token.split(".")
        val payload = parts[1]
        val flipped = payload.dropLast(1) + if (payload.last() == 'A') 'B' else 'A'
        return "${parts[0]}.$flipped.${parts[2]}"
    }
}
