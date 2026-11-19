package com.pynapli.app.messages

import com.pynapli.shared.network.PynapliJson
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.ConversationParticipantDto
import com.pynapli.shared.network.dto.MessageDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The rail follows the server's per-viewer `newMatch`; only a server without it falls back. */
class NewMatchesRailTest {
    @Test
    fun theServerFlagDecidesTheRailWhateverTheOriginAndLastMessage() {
        // A match with someone you had already talked to from the Grid: grid origin, with a message.
        assertTrue(railConversation("c1", origin = "grid", lastMessage = hello("c1"), newMatch = true).isNewMatch())
        // A swipe conversation with no messages the server says is not a new match stays in the list.
        assertFalse(railConversation("c2", origin = "swipe", lastMessage = null, newMatch = false).isNewMatch())
        assertFalse(railConversation("c3", origin = "swipe", lastMessage = hello("c3"), newMatch = false).isNewMatch())
    }

    @Test
    fun aServerWithoutTheFieldKeepsTheOldRule() {
        assertTrue(railConversation("c1", origin = "swipe", lastMessage = null, newMatch = null).isNewMatch())
        assertFalse(railConversation("c2", origin = "swipe", lastMessage = hello("c2"), newMatch = null).isNewMatch())
        assertFalse(railConversation("c3", origin = "grid", lastMessage = null, newMatch = null).isNewMatch())
    }

    @Test
    fun anOldServerResponseDecodesWithoutTheFieldAndFallsBack() {
        val json =
            """
            {"conversationId":"c1","kind":"direct","origin":"swipe","participants":[],
             "unreadCount":0,"updatedAt":"2026-11-18T10:00:00Z"}
            """.trimIndent()

        val conversation = PynapliJson.decodeFromString(ConversationDto.serializer(), json)

        assertNull(conversation.newMatch)
        assertTrue(conversation.isNewMatch())
    }

    @Test
    fun theHintOnlySaysHiWhenEveryMatchIsStillEmpty() {
        val fresh = railConversation("fresh", origin = "swipe", lastMessage = null, newMatch = true)
        val withHistory = railConversation("history", origin = "grid", lastMessage = hello("history"), newMatch = true)

        assertEquals(NewMatchesHint.SAY_HI, listOf(fresh).newMatchesHint())
        assertEquals(NewMatchesHint.OPEN, listOf(withHistory).newMatchesHint())
        assertEquals(NewMatchesHint.OPEN, listOf(fresh, withHistory).newMatchesHint())
        assertFalse(NewMatchesHint.OPEN.contains("say hi", ignoreCase = true))
    }

    @Test
    fun partitionPutsNewMatchesInTheRailAndKeepsTheServerOrder() {
        val inbox =
            listOf(
                railConversation("grid-match", origin = "grid", lastMessage = hello("grid-match"), newMatch = true),
                railConversation("chat", origin = "grid", lastMessage = hello("chat"), newMatch = false),
                railConversation("fresh-match", origin = "swipe", lastMessage = null, newMatch = true),
                railConversation("old-chat", origin = "swipe", lastMessage = hello("old-chat"), newMatch = false),
            )

        val (rail, list) = inbox.partitionNewMatches()

        assertEquals(listOf("grid-match", "fresh-match"), rail.map { it.conversationId })
        assertEquals(listOf("chat", "old-chat"), list.map { it.conversationId })
    }
}

private fun railConversation(
    conversationId: String,
    origin: String,
    lastMessage: MessageDto?,
    newMatch: Boolean?,
): ConversationDto =
    ConversationDto(
        conversationId = conversationId,
        kind = "direct",
        origin = origin,
        participants =
            listOf(
                ConversationParticipantDto(userId = "viewer", displayName = "You"),
                ConversationParticipantDto(userId = "maya", displayName = "Maya"),
            ),
        lastMessage = lastMessage,
        unreadCount = 0,
        updatedAt = "2026-11-18T10:00:00Z",
        newMatch = newMatch,
    )

private fun hello(conversationId: String): MessageDto =
    MessageDto(
        messageId = "m-$conversationId",
        conversationId = conversationId,
        senderUserId = "maya",
        body = "Hi",
        createdAt = "2026-11-18T09:00:00Z",
        status = "visible",
        deliveryStatus = "sent",
    )
