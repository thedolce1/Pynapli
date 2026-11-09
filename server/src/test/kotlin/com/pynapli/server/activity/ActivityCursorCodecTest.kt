package com.pynapli.server.activity

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pins the activity-feed pagination-cursor security contract directly on the codec: HMAC-signed
 * (tamper-evident), bound to the issuing viewer + tab scope (no cross-viewer / cross-tab replay),
 * and rejecting malformed input.
 */
class ActivityCursorCodecTest {
    private val codec = ActivityCursorCodec("test-activity-cursor-secret")
    private val viewer = UUID.randomUUID()
    private val anchor =
        ActivityCursorAnchor(
            occurredAt = Instant.parse("2026-06-15T12:00:00Z"),
            eventId = UUID.randomUUID(),
            seenCount = 5,
        )

    private fun scope(
        viewerUserId: UUID = viewer,
        tab: ActivityFeedTab = ActivityFeedTab.ALL,
    ): String = ActivityCursorCodec.scopeHash(viewerUserId, tab)

    @Test
    fun roundTripsWithinTheSameScope() {
        val decoded = codec.decode(codec.encode(scope(), anchor), scope())
        assertEquals(anchor, decoded)
    }

    @Test
    fun rejectsTamperedPayload() {
        val tampered = tamperPayloadSegment(codec.encode(scope(), anchor))
        assertFailsWith<ActivityCursorException> { codec.decode(tampered, scope()) }
    }

    @Test
    fun rejectsForeignSecret() {
        val token = codec.encode(scope(), anchor)
        val foreign = ActivityCursorCodec("a-different-secret")
        assertFailsWith<ActivityCursorException> { foreign.decode(token, scope()) }
    }

    @Test
    fun rejectsCursorReplayedByAnotherViewer() {
        // Same tab, only the viewer differs: proves the cursor is viewer-bound via the scope hash.
        val token = codec.encode(scope(viewer), anchor)
        assertFailsWith<ActivityCursorException> { codec.decode(token, scope(UUID.randomUUID())) }
    }

    @Test
    fun rejectsCursorFromADifferentTabScope() {
        val token = codec.encode(scope(tab = ActivityFeedTab.ALL), anchor)
        assertFailsWith<ActivityCursorException> { codec.decode(token, scope(tab = ActivityFeedTab.FLIRTS)) }
    }

    @Test
    fun rejectsMalformedTokens() {
        assertFailsWith<ActivityCursorException> { codec.decode("not-a-token", scope()) }
        assertFailsWith<ActivityCursorException> { codec.decode("v1.only-two-parts", scope()) }
    }

    private fun tamperPayloadSegment(token: String): String {
        val parts = token.split(".")
        val payload = parts[1]
        val flipped = payload.dropLast(1) + if (payload.last() == 'A') 'B' else 'A'
        return "${parts[0]}.$flipped.${parts[2]}"
    }
}
