package com.pynapli.server.contactblocks

import com.pynapli.server.db.lockPairForTransaction
import com.pynapli.server.grid.insertActiveProfileLink
import com.pynapli.server.grid.insertDiscoverableUser
import com.pynapli.server.psync.JdbcPsyncSessionRepository
import com.pynapli.server.psync.PsyncDeckGenerator
import com.pynapli.server.psync.PsyncSessionService
import com.pynapli.server.retention.DataRetentionService
import com.pynapli.server.swipe.SWIPE_PAIR_LOCK_NAMESPACE
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.CreateDirectConversationRequestDto
import com.pynapli.shared.network.dto.CreatePsyncSessionRequestDto
import com.pynapli.shared.network.dto.SendMessageRequestDto
import com.pynapli.shared.network.dto.SubmitPsyncActionRequestDto
import com.pynapli.shared.network.dto.SwipeCardDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The edges of "Hide from people you know" (TRUST_AND_SAFETY_SPEC, "Contact Blocks"): per-viewer
 * Psync counts when X answered A's card before the bind; group threads (A's non-message acts never move
 * X's thread, "read" ignores A, A's timer notice is attributed to nobody); the durable read/written
 * exemption, also after a real retention purge; a match X was never shown; the bind/act race in both
 * orders; the flip hiding A's backlog; the bounded settle; tombstone growth; the sweep claim; the
 * early-out token.
 */
class ContactBlockEdgesIntegrationTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest { PynapliTestDatabase.reset() }

        fun seedPair() {
            dataSource.insertDiscoverableUser(A, "Alex", age = 34, longitude = -122.0800, latitude = 37.4200)
            dataSource.insertDiscoverableUser(X, "Sam", age = 41, longitude = -122.0810, latitude = 37.4210)
        }

        suspend fun ContactBlockTestGraph.addAndResolve() {
            contactBlocks.add(A, X_ADDRESS)
            resolver.resolveRecent()
        }

        "Psync: X passed A's card before the bind; X's deck size stays consistent everywhere and nobody finishes early" {
            runBlocking<Unit> {
                val graph = ContactBlockTestGraph(dataSource)
                seedPair()
                dataSource.insertDiscoverableUser(Q, "Riley", age = 39, longitude = -122.0811, latitude = 37.4211)
                dataSource.insertDiscoverableUser(OTHER, "Kim", age = 31, longitude = -122.0812, latitude = 37.4212)
                dataSource.insertActiveProfileLink(userId = X, linkedUserId = Q)
                graph.verifyAddress(X, X_ADDRESS)
                val service = psync(dataSource, graph, listOf(A, OTHER))
                val sessionId = service.activeSession(X, Q)
                val fullSize = service.getDeck(sessionId, Q).deckSize

                // X passes A's card (a pass is no interaction), THEN A's row binds.
                service.act(sessionId, X, A, "pass")
                graph.addAndResolve()
                assertEquals(setOf(A), graph.visibility.hiddenFrom(X))

                val deckForX = service.getDeck(sessionId, X)
                assertEquals(listOf(OTHER.toString()), deckForX.cards.map { it.card.userId })
                assertEquals(fullSize, deckForX.deckSize, "the answered hidden card still counts for X")
                assertEquals(deckForX.deckSize, service.get(sessionId, X).deckSize, "session DTO agrees")
                assertEquals(deckForX.deckSize, service.current(X).session?.deckSize)

                val answer = service.submitAction(sessionId, X, SubmitPsyncActionRequestDto(deckForX.cards.single().cardToken, "pass"))
                assertEquals(deckForX.deckSize, answer.deckSize, "action response agrees")
                assertFalse(answer.sessionCompleted, "Q hasn't finished")
                service.act(sessionId, Q, A, "pass")
                service.act(sessionId, Q, OTHER, "pass")
                assertEquals(PSYNC_COMPLETED, service.get(sessionId, X).status)
            }
        }

        "Psync: bound before the session; every count X gets leaves A's card out" {
            runBlocking<Unit> {
                val graph = ContactBlockTestGraph(dataSource)
                seedPair()
                dataSource.insertDiscoverableUser(Q, "Riley", age = 39, longitude = -122.0811, latitude = 37.4211)
                dataSource.insertDiscoverableUser(OTHER, "Kim", age = 31, longitude = -122.0812, latitude = 37.4212)
                dataSource.insertActiveProfileLink(userId = X, linkedUserId = Q)
                graph.verifyAddress(X, X_ADDRESS)
                graph.addAndResolve()
                val service = psync(dataSource, graph, listOf(A, OTHER))
                val sessionId = service.activeSession(X, Q)

                val forQ = service.getDeck(sessionId, Q).deckSize
                val forX = service.getDeck(sessionId, X).deckSize
                assertEquals(forQ - 1, forX)
                assertEquals(forX, service.get(sessionId, X).deckSize)
                assertEquals(forQ, service.get(sessionId, Q).deckSize)
            }
        }

        "a group thread: A's deletion, timer change and leaving never move it for X; 'read' ignores A; the notice names nobody" {
            runBlocking<Unit> {
                val graph = ContactBlockTestGraph(dataSource)
                seedPair()
                dataSource.insertDiscoverableUser(C, "Casey", age = 37, longitude = -122.0802, latitude = 37.4202)
                dataSource.insertActiveProfileLink(userId = C, linkedUserId = X)
                graph.verifyAddress(X, X_ADDRESS)
                graph.addAndResolve()
                val conversationId =
                    graph.messaging
                        .createDirectConversation(A, CreateDirectConversationRequestDto(targetUserId = C.toString()))
                        .conversation.conversationId
                graph.messaging.sendMessage(A, conversationId, SendMessageRequestDto(body = "Hi Casey"))
                graph.messaging.sendMessage(C, conversationId, SendMessageRequestDto(body = "Hi Alex"))
                graph.messaging.inviteLinkedPartnerToConversation(C, conversationId)
                graph.messaging.acceptPartnerInvite(A, conversationId)
                graph.messaging.acceptPartnerInvite(X, conversationId)
                graph.messaging.sendMessage(X, conversationId, SendMessageRequestDto(body = "Hello all"))
                suspend fun xSort() =
                    graph.messaging
                        .listConversations(X, null)
                        .items
                        .single()
                        .updatedAt
                val before = xSort()

                // C reads; A (hiding from X) never does: X's message shows read.
                graph.messaging.markConversationRead(C, conversationId)
                val mine = graph.messaging.getConversationDetail(X, conversationId).messages.single { it.body == "Hello all" }
                assertEquals("read", mine.deliveryStatus)

                // A writes and deletes, changes the timer, then leaves.
                val written = graph.messaging.sendMessage(A, conversationId, SendMessageRequestDto(body = "Welcome"))
                graph.messaging.deleteOwnMessage(A, written.messageId)
                assertEquals(before, xSort(), "A's deletion didn't move it")
                graph.messaging.setConversationExpiry(A, conversationId, 3_600)
                assertEquals(before, xSort(), "A's timer change didn't move it")
                val notices = graph.messaging.getConversationDetail(X, conversationId).messages.filter { it.messageType == "system" }
                assertTrue(notices.all { it.senderUserId == NOBODY }, "a notice by A is attributed to nobody")
                graph.messaging.leaveConversation(A, conversationId)
                assertEquals(before, xSort(), "A leaving didn't move it")
                assertTrue(
                    graph.messaging
                        .getConversationDetail(X, conversationId)
                        .messages
                        .none { it.senderUserId == A.toString() },
                )
            }
        }

        "X having read or written in a shared conversation exempts, and writing survives the retention purge" {
            runBlocking<Unit> {
                // Wrote, then the message was purged by the real retention job.
                var graph = ContactBlockTestGraph(dataSource)
                seedPair()
                graph.verifyAddress(X, X_ADDRESS)
                var conversationId = openByA(graph)
                graph.messaging.sendMessage(X, conversationId, SendMessageRequestDto(body = "Hi back"))
                graph.execute("UPDATE conversation_participants SET message_expiry_seconds = 3600 WHERE conversation_id = ?::uuid", conversationId)
                graph.execute("UPDATE messages SET created_at = now() - interval '40 days' WHERE conversation_id = ?::uuid", conversationId)
                DataRetentionService(dataSource).runOnce()
                assertEquals(0, graph.count("SELECT COUNT(*) FROM messages WHERE sender_user_id = ?", X), "purged")
                graph.addAndResolve()
                assertTrue(graph.visibility.hiddenFrom(X).isEmpty(), "X wrote: exempt, even with the message gone")

                // Read only.
                PynapliTestDatabase.reset()
                graph = ContactBlockTestGraph(dataSource)
                seedPair()
                graph.verifyAddress(X, X_ADDRESS)
                conversationId = openByA(graph)
                graph.messaging.markConversationRead(X, conversationId)
                graph.addAndResolve()
                assertTrue(graph.visibility.hiddenFrom(X).isEmpty(), "X read: exempt")

                // Neither: bound (A's messages alone never exempt).
                PynapliTestDatabase.reset()
                graph = ContactBlockTestGraph(dataSource)
                seedPair()
                graph.verifyAddress(X, X_ADDRESS)
                openByA(graph)
                graph.addAndResolve()
                assertEquals(setOf(A), graph.visibility.hiddenFrom(X))
            }
        }

        "a match X was never shown (made while A hid) neither lists nor exempts after a release and re-add" {
            runBlocking<Unit> {
                val graph = ContactBlockTestGraph(dataSource)
                seedPair()
                graph.verifyAddress(X, X_ADDRESS)
                graph.addAndResolve()
                graph.execute(
                    "INSERT INTO matches (id, user_a, user_b) VALUES (gen_random_uuid(), LEAST(?::uuid, ?::uuid), GREATEST(?::uuid, ?::uuid))",
                    A,
                    X,
                    A,
                    X,
                )
                graph.messaging.matchConversationOpener.openForMatch(A, X)
                graph.contactBlocks.remove(A, X_ADDRESS)

                assertTrue(graph.visibility.hiddenFrom(X).isEmpty())
                assertTrue(
                    graph.messaging
                        .listConversations(X, null)
                        .items
                        .isEmpty(),
                    "the hidden-window match doesn't list the conversation for X, nor flag a new match",
                )

                graph.addAndResolve()
                assertEquals(setOf(A), graph.visibility.hiddenFrom(X), "the match X never saw doesn't exempt")
            }
        }

        "the bind/act race, deterministic in both orders, under the pair lock" {
            runBlocking<Unit> {
                // Order 1: the bind holds the pair lock (its check saw no act); X's like commits meanwhile,
                // and X's noteActToward waits for the bind, then flips the bound row.
                var graph = ContactBlockTestGraph(dataSource)
                seedPair()
                graph.verifyAddress(X, X_ADDRESS)
                graph.contactBlocks.add(A, X_ADDRESS)
                dataSource.connection.use { bind ->
                    bind.autoCommit = false
                    bind.lockPairForTransaction(SWIPE_PAIR_LOCK_NAMESPACE, A, X)
                    bind.prepareStatement("UPDATE contact_blocks SET target_token = ? WHERE blocker_user_id = ?").use { statement ->
                        statement.setString(1, graph.tokens.targetToken(X))
                        statement.setObject(2, A)
                        statement.executeUpdate()
                    }
                    graph.execute("INSERT INTO swipe_actions (id, actor_user_id, target_user_id, action) VALUES (gen_random_uuid(), ?, ?, 'like')", X, A)
                    val noted = async(Dispatchers.IO) { graph.visibility.noteActToward(actorUserId = X, targetUserId = A) }
                    delay(300)
                    assertFalse(noted.isCompleted, "waits for the bind's pair lock")
                    bind.commit()
                    noted.await()
                }
                assertTrue(graph.visibility.hiddenFrom(X).isEmpty(), "flipped to exempt after the bind committed")

                // Order 2: X's act and its note land first (nothing bound yet: a no-op); the bind's check
                // then sees the act and settles exempt.
                PynapliTestDatabase.reset()
                graph = ContactBlockTestGraph(dataSource)
                seedPair()
                graph.verifyAddress(X, X_ADDRESS)
                graph.contactBlocks.add(A, X_ADDRESS)
                graph.execute("INSERT INTO swipe_actions (id, actor_user_id, target_user_id, action) VALUES (gen_random_uuid(), ?, ?, 'like')", X, A)
                graph.visibility.noteActToward(actorUserId = X, targetUserId = A)
                graph.resolver.resolveRecent()
                assertTrue(graph.visibility.hiddenFrom(X).isEmpty())
                assertEquals(0, graph.count("SELECT COUNT(*) FROM contact_blocks WHERE released_at IS NOT NULL"), "no flip needed")
            }
        }

        "the race flip keeps A's backlog hidden from X; only what A does afterwards shows" {
            runBlocking<Unit> {
                val graph = ContactBlockTestGraph(dataSource)
                seedPair()
                graph.verifyAddress(X, X_ADDRESS)
                graph.addAndResolve()
                graph.activity.sendFlirt(A, X)
                graph.execute("UPDATE activity_events SET first_occurred_at = now() - interval '1 minute', occurred_at = now() - interval '1 minute' WHERE actor_user_id = ?", A)

                graph.execute("INSERT INTO swipe_actions (id, actor_user_id, target_user_id, action) VALUES (gen_random_uuid(), ?, ?, 'like')", X, A)
                graph.visibility.noteActToward(actorUserId = X, targetUserId = A)

                assertTrue(graph.visibility.hiddenFrom(X).isEmpty(), "exempt from now on")
                assertEquals(1, graph.contactBlocks.summary(A).count, "A's count is unchanged")
                assertTrue(
                    graph.activity
                        .listActivity(X)
                        .items
                        .none { it.actorUserId == A.toString() },
                    "the flirt sent while hidden never shows",
                )
            }
        }

        "a held lock makes the sign-in settle give up after the lock timeout, queue a retry, and return" {
            runBlocking<Unit> {
                val graph = ContactBlockTestGraph(dataSource)
                seedPair()
                graph.execute("UPDATE users SET email_verified_at = now() WHERE id = ?", X)
                graph.contactBlocks.add(A, X_ADDRESS)
                val worker = ContactBlockResolverWorker(graph.resolver)
                val settling = ContactBlockSettling(graph.resolver, worker)
                dataSource.connection.use { holder ->
                    holder.autoCommit = false
                    // Someone else's transaction holds the pair lock (a long swipe, say).
                    holder.lockPairForTransaction(SWIPE_PAIR_LOCK_NAMESPACE, A, X)
                    val started = Instant.now()
                    settling.atSignIn(X, X_ADDRESS)
                    val took = Duration.between(started, Instant.now())
                    assertTrue(took < Duration.ofSeconds(8), "bounded by lock_timeout, took $took")
                    assertTrue(graph.visibility.hiddenFrom(X).isEmpty(), "not settled yet")
                    holder.rollback()
                }
                worker.runOnce()
                assertEquals(setOf(A), graph.visibility.hiddenFrom(X), "the worker's retry settled it")
                worker.close()
            }
        }

        "add/remove cycling one address keeps at most five rows for the pair" {
            runBlocking<Unit> {
                val graph = ContactBlockTestGraph(dataSource)
                seedPair()
                graph.verifyAddress(X, X_ADDRESS)
                repeat(8) {
                    graph.addAndResolve()
                    graph.contactBlocks.remove(A, X_ADDRESS)
                }
                assertEquals(TOMBSTONES_KEPT_BEFORE_NEW + 1, graph.count("SELECT COUNT(*) FROM contact_blocks WHERE blocker_user_id = ?", A))
                graph.addAndResolve()
                graph.contactBlocks.removeAll(A)
                assertEquals(TOMBSTONES_KEPT_BEFORE_NEW + 1, graph.count("SELECT COUNT(*) FROM contact_blocks WHERE blocker_user_id = ?", A))
            }
        }

        "the full sweep: a claim without a success is retried within the hour; a success holds for a day" {
            runBlocking<Unit> {
                val repository = JdbcContactBlockResolverRepository(dataSource)
                val day = Duration.ofDays(1)
                val now = Instant.now()
                assertTrue(repository.claimFullSweep(now, day))
                assertFalse(repository.claimFullSweep(now.plusSeconds(600), day), "an attempt is in flight")
                assertTrue(repository.claimFullSweep(now.plusSeconds(3_601), day), "the failed sweep is retried")
                repository.recordFullSweepSuccess(now.plusSeconds(3_700))
                assertFalse(repository.claimFullSweep(now.plusSeconds(3 * 3_600L), day), "succeeded: not again today")
                assertTrue(repository.claimFullSweep(now.plus(day).plusSeconds(3_800), day))
            }
        }

        "the early-out token: none for a viewer nobody targets, the real one once any row (even a tombstone) does" {
            runBlocking<Unit> {
                val graph = ContactBlockTestGraph(dataSource)
                seedPair()
                graph.verifyAddress(X, X_ADDRESS)
                assertEquals(ContactBlockSql.NO_VIEWER_TOKEN, dataSource.viewerTokenIfTargeted(graph.tokens, X))
                graph.addAndResolve()
                assertEquals(graph.tokens.targetToken(X), dataSource.viewerTokenIfTargeted(graph.tokens, X))
                graph.contactBlocks.remove(A, X_ADDRESS)
                assertEquals(graph.tokens.targetToken(X), dataSource.viewerTokenIfTargeted(graph.tokens, X), "a tombstone still filters the backlog")
            }
        }
    })

private const val X_ADDRESS = "sam.edges@example.com"
private const val PSYNC_COMPLETED = "completed"
private val NOBODY = UUID(0L, 0L).toString()
private val A: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c6a1")
private val X: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c6a2")
private val C: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c6a3")
private val Q: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c6a4")
private val OTHER: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c6a5")

/** A opens a conversation with X and writes in it. */
private suspend fun openByA(graph: ContactBlockTestGraph): String {
    val conversationId =
        graph.messaging
            .createDirectConversation(A, CreateDirectConversationRequestDto(targetUserId = X.toString()))
            .conversation.conversationId
    graph.messaging.sendMessage(A, conversationId, SendMessageRequestDto(body = "Hi Sam"))
    return conversationId
}

private fun psync(
    dataSource: DataSource,
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

private suspend fun PsyncSessionService.activeSession(
    initiator: UUID,
    partner: UUID,
): UUID {
    val sessionId = UUID.fromString(create(initiator, CreatePsyncSessionRequestDto(deckSize = 10)).id)
    accept(sessionId, partner)
    start(sessionId, initiator)
    start(sessionId, partner)
    return sessionId
}

private suspend fun PsyncSessionService.act(
    sessionId: UUID,
    user: UUID,
    target: UUID,
    action: String,
) {
    val token = getDeck(sessionId, user).cards.first { it.card.userId == target.toString() }.cardToken
    submitAction(sessionId, user, SubmitPsyncActionRequestDto(token, action))
}
