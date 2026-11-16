package com.pynapli.server.psync

import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.server.testsupport.raceTogether
import com.pynapli.shared.network.dto.CreatePsyncSessionRequestDto
import com.pynapli.shared.network.dto.SubmitPsyncActionRequestDto
import com.pynapli.shared.network.dto.SwipeCardDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * Psync writes that race a partner, the expiry worker or a failure: two partners creating a session
 * at once, a swipe landing as the session completes, and a Pineapple Like spent on a swipe that
 * never got recorded.
 */
class PsyncSessionRaceIntegrationTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest {
            PynapliTestDatabase.reset()
        }

        fun deckService(
            candidates: List<UUID>,
            repository: PsyncSessionRepository = JdbcPsyncSessionRepository(dataSource),
            pineappleInventory: PsyncPineappleInventory? = null,
        ): PsyncSessionService =
            PsyncSessionService(
                repository = repository,
                deckGenerator =
                    object : PsyncDeckGenerator {
                        override suspend fun rankCandidates(
                            viewerUserId: UUID,
                            filters: SwipeDeckRequestDto,
                            limit: Int,
                        ): List<SwipeCardDto> =
                            candidates.take(limit).map { id ->
                                SwipeCardDto(userId = id.toString(), displayName = "Candidate", rankToken = "rank:$id")
                            }
                    },
                pineappleInventory = pineappleInventory,
            )

        fun seedLinkedPair(): Pair<UUID, UUID> {
            val initiator = UUID.randomUUID()
            val partner = UUID.randomUUID()
            dataSource.insertRaceUser(initiator)
            dataSource.insertRaceUser(partner)
            dataSource.insertRaceLink(initiator, partner)
            return initiator to partner
        }

        fun seedTarget(): UUID = UUID.randomUUID().also { dataSource.insertRaceUser(it) }

        suspend fun PsyncSessionService.activeSession(
            initiator: UUID,
            partner: UUID,
        ): UUID {
            val sessionId = UUID.fromString(create(initiator, CreatePsyncSessionRequestDto(deckSize = 25)).id)
            accept(sessionId, partner)
            start(sessionId, initiator)
            start(sessionId, partner)
            return sessionId
        }

        suspend fun PsyncSessionService.submitOn(
            sessionId: UUID,
            user: UUID,
            target: UUID,
            action: String,
        ) = submitAction(
            sessionId,
            user,
            SubmitPsyncActionRequestDto(
                getDeck(sessionId, user).cards.first { it.card.userId == target.toString() }.cardToken,
                action,
            ),
        )

        "both partners starting Psync at the same moment create one session for the link" {
            runBlocking {
                val service = deckService(emptyList())
                val iterations = 30
                var wrong = 0
                repeat(iterations) {
                    val (initiator, partner) = seedLinkedPair()
                    val (a, b) =
                        raceTogether(
                            { service.create(initiator, CreatePsyncSessionRequestDto(deckSize = 10)) },
                            { service.create(partner, CreatePsyncSessionRequestDto(deckSize = 10)) },
                        )
                    val sessions = dataSource.nonTerminalSessionsForUser(initiator)
                    if (sessions != 1) wrong++
                    // The loser is told exactly what the sequential check tells it.
                    listOf(a, b).mapNotNull { it.exceptionOrNull() }.forEach { error ->
                        assertEquals("PSYNC_SESSION_ALREADY_ACTIVE", (error as PsyncException).code)
                    }
                }
                assertEquals(0, wrong, "iterations out of $iterations with other than one live session for the link")
            }
        }

        "an action is not recorded on a session that has already completed" {
            runBlocking {
                val repository = JdbcPsyncSessionRepository(dataSource)
                val (initiator, partner) = seedLinkedPair()
                val target = seedTarget()
                val service = deckService(listOf(target), repository)
                val sessionId = service.activeSession(initiator, partner)
                service.complete(sessionId, partner)

                // Straight to the repository, as if the service's own check ran just before completion.
                val outcome = repository.recordAction(sessionId, initiator, target, "like", Instant.now())

                assertFalse(outcome.newlyRecorded)
                assertFalse(outcome.sessionActive)
                assertEquals(0, dataSource.countSessionActions(sessionId))
            }
        }

        "a swipe racing the session's completion is either refused or resolved, never dropped" {
            runBlocking {
                val iterations = 30
                var dropped = 0
                repeat(iterations) {
                    val (initiator, partner) = seedLinkedPair()
                    val target = seedTarget()
                    val filler = seedTarget()
                    // Two cards, so the like alone does not auto-complete the session.
                    val service = deckService(listOf(target, filler))
                    val sessionId = service.activeSession(initiator, partner)
                    val token =
                        service
                            .getDeck(sessionId, initiator)
                            .cards
                            .first { it.card.userId == target.toString() }
                            .cardToken

                    val (submit, _) =
                        raceTogether(
                            { service.submitAction(sessionId, initiator, SubmitPsyncActionRequestDto(token, "like")) },
                            { service.complete(sessionId, partner) },
                        )
                    val recorded = submit.getOrNull()?.recorded == true
                    val delivered = dataSource.deliveredLikes(sessionId, initiator, target) > 0
                    // A like the caller was told is recorded must have reached resolution, and
                    // nothing may sit on the completed session that resolution never saw.
                    if ((recorded || dataSource.countSessionActions(sessionId) > 0) && !delivered) dropped++
                }
                assertEquals(0, dropped, "likes left on a completed session without being resolved")
            }
        }

        "a Pineapple Like is given back when recording the swipe fails" {
            runBlocking {
                val (initiator, partner) = seedLinkedPair()
                val target = seedTarget()
                val inventory = RacePineappleInventory(dataSource, initiator, quantity = 1)
                val failing =
                    object : PsyncSessionRepository by JdbcPsyncSessionRepository(dataSource) {
                        override suspend fun recordAction(
                            sessionId: UUID,
                            actorUserId: UUID,
                            targetUserId: UUID,
                            action: String,
                            now: Instant,
                            actionIdFactory: () -> UUID,
                            consumedInventoryId: UUID?,
                        ): PsyncSessionRepository.RecordActionOutcome = throw IllegalStateException("database went away")
                    }
                val service = deckService(listOf(target), failing, inventory)
                val sessionId = service.activeSession(initiator, partner)

                assertFailsWith<IllegalStateException> { service.submitOn(sessionId, initiator, target, "pineapple_like") }
                assertEquals(1, inventory.remaining())
            }
        }

        "a Pineapple Like is given back when the session completes between the check and the record" {
            runBlocking {
                val (initiator, partner) = seedLinkedPair()
                val target = seedTarget()
                val inventory = RacePineappleInventory(dataSource, initiator, quantity = 1)
                val jdbc = JdbcPsyncSessionRepository(dataSource)
                val completesFirst =
                    object : PsyncSessionRepository by jdbc {
                        override suspend fun recordAction(
                            sessionId: UUID,
                            actorUserId: UUID,
                            targetUserId: UUID,
                            action: String,
                            now: Instant,
                            actionIdFactory: () -> UUID,
                            consumedInventoryId: UUID?,
                        ): PsyncSessionRepository.RecordActionOutcome {
                            // The partner leaves the session just before this swipe is written.
                            jdbc.complete(sessionId, partner, now)
                            return jdbc.recordAction(sessionId, actorUserId, targetUserId, action, now, actionIdFactory, consumedInventoryId)
                        }
                    }
                val service = deckService(listOf(target), completesFirst, inventory)
                val sessionId = service.activeSession(initiator, partner)

                val refused = assertFailsWith<PsyncException> { service.submitOn(sessionId, initiator, target, "pineapple_like") }
                assertEquals("PSYNC_INVALID_STATE", refused.code)
                assertEquals(1, inventory.remaining())
                assertEquals(0, dataSource.countSessionActions(sessionId))
            }
        }

        "a Pineapple Like whose swipe committed stays spent when the request is cancelled" {
            runBlocking {
                val (initiator, partner) = seedLinkedPair()
                val target = seedTarget()
                val inventory = RacePineappleInventory(dataSource, initiator, quantity = 1)
                val jdbc = JdbcPsyncSessionRepository(dataSource)
                val request = AtomicReference<Job>()
                val cancelsMidWrite =
                    object : PsyncSessionRepository by jdbc {
                        override suspend fun recordAction(
                            sessionId: UUID,
                            actorUserId: UUID,
                            targetUserId: UUID,
                            action: String,
                            now: Instant,
                            actionIdFactory: () -> UUID,
                            consumedInventoryId: UUID?,
                        ): PsyncSessionRepository.RecordActionOutcome =
                            // The client goes away while the transaction is open; it still commits.
                            jdbc.recordAction(sessionId, actorUserId, targetUserId, action, now, {
                                request.get().cancel()
                                UUID.randomUUID()
                            }, consumedInventoryId)
                    }
                val service = deckService(listOf(target), cancelsMidWrite, inventory)
                val sessionId = service.activeSession(initiator, partner)
                val token =
                    service
                        .getDeck(sessionId, initiator)
                        .cards
                        .single()
                        .cardToken

                val job =
                    launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                        service.submitAction(sessionId, initiator, SubmitPsyncActionRequestDto(token, "pineapple_like"))
                    }
                request.set(job)
                job.start()
                job.join()

                assertEquals(1, dataSource.countSessionActions(sessionId))
                // The recorded like owns the spend; refunding it here would pay it back twice.
                assertEquals(0, inventory.remaining())
            }
        }
    })

private fun DataSource.insertRaceUser(userId: UUID) {
    connection.use { connection ->
        connection.prepareStatement("INSERT INTO users (id, status) VALUES (?, 'active')").use { statement ->
            statement.setObject(1, userId)
            statement.executeUpdate()
        }
        connection
            .prepareStatement(
                """
                INSERT INTO profiles (user_id, display_name, display_age, relationship_context, moderation_status, visibility_status)
                VALUES (?, 'Racer', 30, 'single', 'approved', 'visible')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeUpdate()
            }
    }
}

private fun DataSource.insertRaceLink(
    userId: UUID,
    linkedUserId: UUID,
) {
    connection.use { connection ->
        connection
            .prepareStatement(
                "INSERT INTO user_profile_links (id, user_id, linked_user_id, status, accepted_at) VALUES (?, ?, ?, 'active', now())",
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, userId)
                statement.setObject(3, linkedUserId)
                statement.executeUpdate()
            }
    }
}

private fun DataSource.nonTerminalSessionsForUser(userId: UUID): Int =
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                SELECT COUNT(*) FROM psync_sessions s
                JOIN psync_session_participants p ON p.session_id = s.id
                WHERE p.user_id = ? AND s.status IN ($PSYNC_NON_TERMINAL_STATUS_LIST)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
    }

private fun DataSource.countSessionActions(sessionId: UUID): Int =
    connection.use { connection ->
        connection.prepareStatement("SELECT COUNT(*) FROM psync_session_actions WHERE session_id = ?").use { statement ->
            statement.setObject(1, sessionId)
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

private fun DataSource.deliveredLikes(
    sessionId: UUID,
    actorUserId: UUID,
    targetUserId: UUID,
): Int =
    connection.use { connection ->
        connection
            .prepareStatement(
                "SELECT COUNT(*) FROM swipe_actions WHERE psync_session_id = ? AND actor_user_id = ? AND target_user_id = ?",
            ).use { statement ->
                statement.setObject(1, sessionId)
                statement.setObject(2, actorUserId)
                statement.setObject(3, targetUserId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
    }

/** Pineapple Like inventory on a real `premium_inventory` row, so the balance can be asserted. */
private class RacePineappleInventory(
    private val dataSource: DataSource,
    private val ownerUserId: UUID,
    quantity: Int,
) : PsyncPineappleInventory {
    private val inventoryId: UUID = UUID.randomUUID()

    init {
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "INSERT INTO premium_inventory (id, user_id, inventory_type, quantity, source) VALUES (?, ?, 'pineapple_like', ?, 'dev')",
                ).use { statement ->
                    statement.setObject(1, inventoryId)
                    statement.setObject(2, ownerUserId)
                    statement.setInt(3, quantity)
                    statement.executeUpdate()
                }
        }
    }

    override suspend fun consume(userId: UUID): UUID? =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "UPDATE premium_inventory SET quantity = quantity - 1 WHERE id = ? AND user_id = ? AND quantity > 0",
                ).use { statement ->
                    statement.setObject(1, inventoryId)
                    statement.setObject(2, userId)
                    if (statement.executeUpdate() > 0) inventoryId else null
                }
        }

    override suspend fun refund(inventoryId: UUID) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE premium_inventory SET quantity = quantity + 1 WHERE id = ?").use { statement ->
                statement.setObject(1, inventoryId)
                statement.executeUpdate()
            }
        }
    }

    fun remaining(): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT quantity FROM premium_inventory WHERE id = ?").use { statement ->
                statement.setObject(1, inventoryId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }
}
