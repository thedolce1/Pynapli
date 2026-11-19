package com.pynapli.server.messaging

import com.pynapli.server.grid.insertDiscoverableUser
import com.pynapli.server.grid.insertMatch
import com.pynapli.server.safety.JdbcBlockRepository
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.CreateDirectConversationRequestDto
import com.pynapli.shared.network.dto.SendMessageRequestDto
import kotlinx.coroutines.runBlocking
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The server decides the Messages new-matches rail (ConversationDto.newMatch, MESSAGING_SPEC
 * "New-matches rail"), per viewer: a fresh match until the first message, and a match between people
 * who had already talked from the Grid until each of them opens the conversation.
 */
class MatchInboxRailIntegrationTest {
    private val dataSource: DataSource = PynapliTestDatabase.dataSource
    private val avery = UUID.fromString("00000000-0000-0000-0000-000000000b01")
    private val blake = UUID.fromString("00000000-0000-0000-0000-000000000b02")
    private val casey = UUID.fromString("00000000-0000-0000-0000-000000000b03")

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
        dataSource.insertDiscoverableUser(avery, "Avery", 35, -79.38, 43.65)
        dataSource.insertDiscoverableUser(blake, "Blake", 34, -79.381, 43.651)
        dataSource.insertDiscoverableUser(casey, "Casey", 33, -79.382, 43.652)
    }

    private fun service(): MessagingService =
        MessagingService(
            JdbcMessagingRepository(dataSource),
            blockRepository = JdbcBlockRepository(dataSource),
        )

    private suspend fun MessagingService.inbox(userId: UUID): List<ConversationDto> = listConversations(userId).items

    private suspend fun MessagingService.railFlag(
        userId: UUID,
        conversationId: String,
    ): Boolean? = inbox(userId).singleOrNull { it.conversationId == conversationId }?.newMatch

    private suspend fun MessagingService.gridConversationWithHistory(): String {
        val conversationId =
            createDirectConversation(
                avery,
                CreateDirectConversationRequestDto(targetUserId = blake.toString(), origin = "grid"),
            ).conversation.conversationId
        sendMessage(avery, conversationId, SendMessageRequestDto(body = "Hi Blake"))
        sendMessage(blake, conversationId, SendMessageRequestDto(body = "Hey Avery"))
        return conversationId
    }

    @Test
    fun aFreshMatchIsNewForBothPeopleUntilTheFirstMessage() =
        runBlocking {
            val service = service()
            dataSource.insertMatch(avery, blake)
            service.openMatchConversation(avery, blake)

            val conversationId = service.inbox(avery).single().conversationId
            assertEquals(true, service.railFlag(avery, conversationId))
            // The person who did not create the conversation sees the match too.
            assertEquals(true, service.railFlag(blake, conversationId))

            // Opening an empty match keeps it in the rail (as before): it still waits for a hello.
            service.markConversationRead(blake, conversationId)
            assertEquals(true, service.railFlag(blake, conversationId))

            service.sendMessage(blake, conversationId, SendMessageRequestDto(body = "Hi!"))
            assertEquals(false, service.railFlag(avery, conversationId))
            assertEquals(false, service.railFlag(blake, conversationId))
        }

    @Test
    fun aMatchBetweenPeopleWhoAlreadyTalkedFromTheGridIsNewForBoth() =
        runBlocking {
            val service = service()
            val conversationId = service.gridConversationWithHistory()
            assertEquals(false, service.railFlag(avery, conversationId))
            assertEquals(false, service.railFlag(blake, conversationId))

            dataSource.insertMatch(avery, blake)
            service.openMatchConversation(blake, avery)

            // The Grid conversation is reused, keeps its origin, and is still a new match for both.
            val averyView = service.inbox(avery).single()
            assertEquals(conversationId, averyView.conversationId)
            assertEquals("grid", averyView.origin)
            assertEquals(true, averyView.newMatch)
            assertEquals(true, service.railFlag(blake, conversationId))
        }

    @Test
    fun openingTheConversationClearsItForTheOpenerOnlyAndItNeverReturns() =
        runBlocking {
            val service = service()
            val conversationId = service.gridConversationWithHistory()
            dataSource.insertMatch(avery, blake)
            service.openMatchConversation(avery, blake)

            service.markConversationRead(avery, conversationId)

            assertEquals(false, service.railFlag(avery, conversationId))
            assertEquals(true, service.railFlag(blake, conversationId))
            // Opening again is harmless.
            service.markConversationRead(avery, conversationId)
            assertEquals(false, service.railFlag(avery, conversationId))

            // New messages move it into the list for both and never bring it back to the rail.
            service.sendMessage(avery, conversationId, SendMessageRequestDto(body = "We matched!"))
            service.sendMessage(blake, conversationId, SendMessageRequestDto(body = "We did"))
            assertEquals(false, service.railFlag(avery, conversationId))
            assertEquals(false, service.railFlag(blake, conversationId))
            // A redelivered match side effect does not resurrect it either.
            service.openMatchConversation(avery, blake)
            assertEquals(false, service.railFlag(avery, conversationId))
            assertEquals(false, service.railFlag(blake, conversationId))
        }

    @Test
    fun aMatchReusingAnOlderConversationMovesItToTheTopOfTheInbox() =
        runBlocking {
            val service = service()
            val gridConversationId = service.gridConversationWithHistory()
            val newerConversationId =
                service
                    .createDirectConversation(
                        casey,
                        CreateDirectConversationRequestDto(targetUserId = avery.toString(), origin = "grid"),
                    ).conversation.conversationId
            service.sendMessage(casey, newerConversationId, SendMessageRequestDto(body = "Hi Avery"))
            assertEquals(listOf(newerConversationId, gridConversationId), service.inbox(avery).map { it.conversationId })

            dataSource.insertMatch(avery, blake)
            service.openMatchConversation(avery, blake)

            assertEquals(listOf(gridConversationId, newerConversationId), service.inbox(avery).map { it.conversationId })
        }

    @Test
    fun aBlockRemovesTheRailItemWithTheConversation() =
        runBlocking {
            val service = service()
            val conversationId = service.gridConversationWithHistory()
            dataSource.insertMatch(avery, blake)
            service.openMatchConversation(avery, blake)
            assertEquals(true, service.railFlag(blake, conversationId))

            JdbcBlockRepository(dataSource).block(avery, blake, null)

            assertNull(service.inbox(avery).singleOrNull { it.conversationId == conversationId })
            assertNull(service.inbox(blake).singleOrNull { it.conversationId == conversationId })
        }

    @Test
    fun anUnmatchedPairIsNeverANewMatch() =
        runBlocking {
            val service = service()
            val conversationId = service.gridConversationWithHistory()
            // A Grid conversation nobody has written in yet is still only listed for its creator.
            val emptyGridConversationId =
                service
                    .createDirectConversation(
                        casey,
                        CreateDirectConversationRequestDto(targetUserId = blake.toString(), origin = "grid"),
                    ).conversation.conversationId

            assertEquals(false, service.railFlag(avery, conversationId))
            assertEquals(false, service.railFlag(casey, emptyGridConversationId))
            assertNull(service.railFlag(blake, emptyGridConversationId))
        }
}
