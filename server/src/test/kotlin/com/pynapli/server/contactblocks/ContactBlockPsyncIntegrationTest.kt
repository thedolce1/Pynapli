package com.pynapli.server.contactblocks

import com.pynapli.server.account.JdbcAccountDataExportArchiver
import com.pynapli.server.grid.insertActiveProfileLink
import com.pynapli.server.grid.insertDiscoverableUser
import com.pynapli.server.psync.JdbcPsyncSessionRepository
import com.pynapli.server.psync.PsyncDeckGenerator
import com.pynapli.server.psync.PsyncException
import com.pynapli.server.psync.PsyncSessionService
import com.pynapli.server.swipe.JdbcMatchCelebrationRepository
import com.pynapli.server.swipe.MatchCelebrationService
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.CreatePsyncSessionRequestDto
import com.pynapli.shared.network.dto.SubmitPsyncActionRequestDto
import com.pynapli.shared.network.dto.SwipeCardDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Psync and "Hide from people you know": a together match can pair X with A without X ever acting
 * toward A (X liked A's partner P; A and P both liked X in their session). The match rows stay, so A's
 * world is the same whatever the address was; X never sees A in the match moment, the inbox, Activity,
 * realtime hints or the export. And a shared Psync deck is filtered per person, so A stays in the deck
 * of X's partner Q (A keeps getting Q's answers either way).
 */
class ContactBlockPsyncIntegrationTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest { PynapliTestDatabase.reset() }

        fun psync(
            graph: ContactBlockTestGraph,
            candidates: List<UUID>,
        ): PsyncSessionService =
            PsyncSessionService(
                repository = JdbcPsyncSessionRepository(dataSource),
                deckGenerator =
                    object : PsyncDeckGenerator {
                        override suspend fun rankCandidates(
                            viewerUserId: UUID,
                            filters: SwipeDeckRequestDto,
                            limit: Int,
                        ): List<SwipeCardDto> =
                            candidates.take(limit).map { id ->
                                SwipeCardDto(userId = id.toString(), displayName = "Candidate ${id.toString().take(4)}", rankToken = "rank:$id")
                            }
                    },
                blockRepository =
                    com.pynapli.server.safety
                        .JdbcBlockRepository(dataSource),
                hiddenProfileRepository =
                    com.pynapli.server.safety
                        .JdbcHiddenProfileRepository(dataSource),
                matchConversationOpener = graph.messaging.matchConversationOpener,
                activityService = graph.activity,
                notificationPublisher = graph.notifications,
                contactBlockVisibility = graph.visibility,
            )

        suspend fun PsyncSessionService.activeSession(
            initiator: UUID,
            partner: UUID,
        ): UUID {
            val created = create(initiator, CreatePsyncSessionRequestDto(deckSize = 10))
            val sessionId = UUID.fromString(created.id)
            accept(sessionId, partner)
            start(sessionId, initiator)
            start(sessionId, partner)
            return sessionId
        }

        suspend fun PsyncSessionService.act(
            sessionId: UUID,
            user: UUID,
            target: UUID,
            action: String,
        ) {
            val token = getDeck(sessionId, user).cards.first { it.card.userId == target.toString() }.cardToken
            submitAction(sessionId, user, SubmitPsyncActionRequestDto(token, action))
        }

        "a together match pairing X with A shows X only P: no moment, conversation, Activity, hint or export row about A" {
            runBlocking<Unit> {
                val graph = ContactBlockTestGraph(dataSource)
                dataSource.insertDiscoverableUser(A, "Alex", age = 34, longitude = -122.0800, latitude = 37.4200)
                dataSource.insertDiscoverableUser(P, "Jo", age = 36, longitude = -122.0801, latitude = 37.4201)
                dataSource.insertDiscoverableUser(X, "Sam", age = 41, longitude = -122.0810, latitude = 37.4210)
                dataSource.insertActiveProfileLink(userId = A, linkedUserId = P)
                graph.execute("UPDATE discovery_settings SET together_enabled = true WHERE user_id = ?", X)
                graph.verifyAddress(X, X_ADDRESS)
                graph.contactBlocks.add(A, X_ADDRESS)
                graph.resolver.resolveRecent()
                assertEquals(setOf(A), graph.visibility.hiddenFrom(X))
                // X liked P (P's card shows no partner mini for X); X never acted toward A.
                graph.execute("INSERT INTO swipe_actions (id, actor_user_id, target_user_id, action) VALUES (gen_random_uuid(), ?, ?, 'like')", X, P)
                val recorders = graph.recordRealtime(this)

                val service = psync(graph, listOf(X))
                val sessionId = service.activeSession(A, P)
                service.act(sessionId, A, X, "like")
                service.act(sessionId, P, X, "like")
                service.complete(sessionId, A)
                yield()

                // The rows stay: A's world is the same either way.
                assertEquals(1, graph.count("SELECT COUNT(*) FROM matches WHERE (user_a = ? AND user_b = ?) OR (user_a = ? AND user_b = ?)", A, X, X, A))
                val celebrations = MatchCelebrationService(JdbcMatchCelebrationRepository(dataSource), contactBlockVisibility = graph.visibility)
                assertTrue(celebrations.listUncelebrated(A).items.any { it.matchedUserId == X.toString() }, "A celebrates as usual")

                // X: only P, never A, never a pair moment naming A.
                val moments = celebrations.listUncelebrated(X).items
                assertEquals(listOf(P.toString()), moments.map { it.matchedUserId })
                assertNull(moments.single().pairUserId)
                val inbox = graph.messaging.listConversations(X, null).items
                assertTrue(inbox.none { conversation -> conversation.participants.any { it.userId == A.toString() } }, "no conversation with A for X")
                assertTrue(
                    graph.activity
                        .listActivity(X)
                        .items
                        .none { it.actorUserId == A.toString() },
                )
                assertTrue(graph.messageDeltas.none { it.recipientUserId == X && it.conversationId in conversationsWith(graph, A, X) }, "no hint about the A-X conversation")
                assertEquals(0, graph.outboxCount(recipient = X, actor = A))
                val export = JdbcAccountDataExportArchiver(dataSource, contactBlockTokens = graph.tokens).buildArchive(X)
                val matches = Json.parseToJsonElement(export.bytes.decodeToString()).jsonObject["matches"] as JsonArray
                assertTrue(matches.none { it.jsonObject["other_user_id"]?.jsonPrimitive?.content == A.toString() }, "X's export shows no match with A")
                recorders.forEach { it.cancel() }
            }
        }

        "a shared deck is filtered per person: A stays in Q's deck, X never gets A's card, and both still finish" {
            runBlocking<Unit> {
                val graph = ContactBlockTestGraph(dataSource)
                dataSource.insertDiscoverableUser(A, "Alex", age = 34, longitude = -122.0800, latitude = 37.4200)
                dataSource.insertDiscoverableUser(X, "Sam", age = 41, longitude = -122.0810, latitude = 37.4210)
                dataSource.insertDiscoverableUser(Q, "Riley", age = 39, longitude = -122.0811, latitude = 37.4211)
                dataSource.insertDiscoverableUser(OTHER, "Kim", age = 31, longitude = -122.0812, latitude = 37.4212)
                dataSource.insertActiveProfileLink(userId = X, linkedUserId = Q)
                graph.verifyAddress(X, X_ADDRESS)
                graph.contactBlocks.add(A, X_ADDRESS)
                graph.resolver.resolveRecent()

                val service = psync(graph, listOf(A, OTHER))
                val sessionId = service.activeSession(X, Q)
                val deckForX = service.getDeck(sessionId, X)
                val deckForQ = service.getDeck(sessionId, Q)

                assertEquals(listOf(OTHER.toString()), deckForX.cards.map { it.card.userId })
                assertEquals(setOf(A.toString(), OTHER.toString()), deckForQ.cards.map { it.card.userId }.toSet())
                assertEquals(deckForQ.deckSize - 1, deckForX.deckSize)
                // Q's token for A's card doesn't work for X.
                val tokenForA = deckForQ.cards.first { it.card.userId == A.toString() }.cardToken
                assertEquals(
                    "PSYNC_INVALID_CARD",
                    assertFailsWith<PsyncException> { service.submitAction(sessionId, X, SubmitPsyncActionRequestDto(tokenForA, "like")) }.code,
                )

                service.act(sessionId, Q, A, "like")
                service.act(sessionId, Q, OTHER, "pass")
                val last = service.submitAction(sessionId, X, SubmitPsyncActionRequestDto(deckForX.cards.single().cardToken, "pass"))
                assertTrue(last.sessionCompleted, "X finishes at their own count, so the session completes")
            }
        }
    })

private const val X_ADDRESS = "sam.psync@example.com"
private val A: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c4a1")
private val P: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c4a2")
private val X: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c4a3")
private val Q: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c4a4")
private val OTHER: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c4a5")

private fun conversationsWith(
    graph: ContactBlockTestGraph,
    a: UUID,
    b: UUID,
): Set<UUID> =
    graph.dataSource.connection.use { connection ->
        connection
            .prepareStatement(
                "SELECT conversation_id FROM direct_conversations WHERE LEAST(user_a_id, user_b_id) = LEAST(?::uuid, ?::uuid) AND GREATEST(user_a_id, user_b_id) = GREATEST(?::uuid, ?::uuid)",
            ).use { statement ->
                statement.setObject(1, a)
                statement.setObject(2, b)
                statement.setObject(3, a)
                statement.setObject(4, b)
                statement.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getObject(1, UUID::class.java)) } }
            }
    }
