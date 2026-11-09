package com.pynapli.app.conversation

import com.pynapli.shared.network.dto.ConversationDetailDto
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.MessageDto
import kotlin.test.Test
import kotlin.test.assertEquals

class ConversationThreadRowsTest {
    @Test
    fun dayDividerPrecedesFirstMessageOfEachDay() {
        val rows =
            listOf(
                message("m1", "2026-05-21T10:00:00Z"),
                message("m2", "2026-05-21T11:00:00Z"),
                message("m3", "2026-05-22T09:00:00Z"),
            ).toThreadRows()

        assertEquals(
            listOf("day-m1", "message-m1", "message-m2", "day-m3", "message-m3"),
            rows.map { it.key },
        )
    }

    // A keyed LazyColumn throws on a duplicate key, so keys must stay unique even when a day
    // reappears (out-of-order input).
    @Test
    fun rowKeysStayUniqueWhenADayReappears() {
        val rows =
            listOf(
                message("m1", "2026-05-21T10:00:00Z"),
                message("m2", "2026-05-22T09:00:00Z"),
                message("m3", "2026-05-21T12:00:00Z"),
            ).toThreadRows()

        val keys = rows.map { it.key }
        assertEquals(keys.size, keys.toSet().size, "duplicate keys: $keys")
    }

    @Test
    fun appendingAnAlreadyPresentMessageReplacesItInsteadOfDuplicating() {
        val sent = message("m1", "2026-05-21T10:00:00Z")
        val detail = ConversationDetailDto(conversation = conversation(), messages = listOf(sent))

        val appended = detail.withAppendedMessage(sent.copy(deliveryStatus = "read"))

        assertEquals(listOf("m1"), appended.messages.map { it.messageId })
        assertEquals("read", appended.messages.single().deliveryStatus)
    }
}

private fun message(
    messageId: String,
    createdAt: String,
): MessageDto =
    MessageDto(
        messageId = messageId,
        conversationId = "conversation-1",
        senderUserId = "maya",
        body = "hello",
        createdAt = createdAt,
        status = "visible",
        deliveryStatus = "sent",
    )

private fun conversation(): ConversationDto =
    ConversationDto(
        conversationId = "conversation-1",
        kind = "direct",
        origin = "grid",
        participants = emptyList(),
        unreadCount = 0,
        updatedAt = "2026-05-21T10:00:00Z",
    )
