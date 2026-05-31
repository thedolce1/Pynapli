package com.pynapli.server.messaging

import org.junit.Test
import kotlin.test.assertEquals

class MessageSafetyPolicyTest {
    private val policy = MessageSafetyPolicy()

    @Test
    fun cleanFirstMessageRemainsVisible() {
        val decision =
            policy.evaluateFirstMessage(
                body = "Hey, loved your profile",
                recentDuplicateCount = 0,
            )

        assertEquals(MessageModerationStatus.VISIBLE, decision.moderationStatus)
        assertEquals(null, decision.safetyReason)
    }

    @Test
    fun firstMessageWithExternalLinkIsHeld() {
        val decision =
            policy.evaluateFirstMessage(
                body = "Message me at www.example.com",
                recentDuplicateCount = 0,
            )

        assertEquals(MessageModerationStatus.HELD, decision.moderationStatus)
        assertEquals(MessageSafetyReason.LINK_FIRST_MESSAGE, decision.safetyReason)
    }

    @Test
    fun repeatedFirstMessageCopyIsHeld() {
        val decision =
            policy.evaluateFirstMessage(
                body = "Hey, loved your profile",
                recentDuplicateCount = MessageSafetyPolicy.DUPLICATE_FIRST_MESSAGE_PREVIOUS_LIMIT,
            )

        assertEquals(MessageModerationStatus.HELD, decision.moderationStatus)
        assertEquals(MessageSafetyReason.DUPLICATE_FIRST_MESSAGE, decision.safetyReason)
    }

    @Test
    fun fingerprintNormalizesWhitespaceAndCase() {
        assertEquals(
            policy.fingerprint("Hey, loved your profile"),
            policy.fingerprint("  hey,   loved YOUR profile  "),
        )
    }
}
