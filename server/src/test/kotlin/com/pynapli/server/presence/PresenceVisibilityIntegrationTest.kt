package com.pynapli.server.presence

import com.pynapli.server.grid.insertActiveTravelLocation
import com.pynapli.server.grid.insertDiscoverableUser
import com.pynapli.server.grid.setRiskState
import com.pynapli.server.grid.setVisibleWhileTraveling
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.PresenceDeltaDto
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Privacy review F1: presence transitions go out only for members the Grid could show. Every hidden
 * state is checked against the real query, then through PresenceService and the offline scanner.
 */
class PresenceVisibilityIntegrationTest {
    private val dataSource: DataSource = PynapliTestDatabase.dataSource
    private val visibility = JdbcPresenceVisibility(dataSource)

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    private fun member(): UUID = UUID.randomUUID().also { dataSource.insertDiscoverableUser(it, "Member", age = 30, longitude = -122.08, latitude = 37.42) }

    private fun DataSource.exec(
        sql: String,
        userId: UUID,
    ) {
        connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, userId)
                statement.executeUpdate()
            }
        }
    }

    @Test
    fun anOrdinaryVisibleMemberIsBroadcastable() =
        runBlocking {
            assertTrue(visibility.broadcastable(member()))
        }

    @Test
    fun everyHiddenStateIsNotBroadcastable() =
        runBlocking {
            val hiddenStates: Map<String, (UUID) -> Unit> =
                mapOf(
                    "grid_visible = false" to { id -> dataSource.exec("UPDATE discovery_settings SET grid_visible = false WHERE user_id = ?", id) },
                    "incognito" to { id -> dataSource.exec("UPDATE discovery_settings SET incognito = true WHERE user_id = ?", id) },
                    "profile hidden" to { id -> dataSource.exec("UPDATE profiles SET visibility_status = 'hidden' WHERE user_id = ?", id) },
                    "account suspended" to { id -> dataSource.exec("UPDATE users SET status = 'suspended' WHERE id = ?", id) },
                    "account deleted" to { id -> dataSource.exec("UPDATE users SET deleted_at = now() WHERE id = ?", id) },
                    "quarantined" to { id -> dataSource.setRiskState(id, score = 90, tier = "quarantine") },
                    "hidden while travelling" to { id ->
                        dataSource.insertActiveTravelLocation(id, "Elsewhere", latitude = 40.71, longitude = -74.0)
                        dataSource.setVisibleWhileTraveling(id, false)
                    },
                )
            hiddenStates.forEach { (state, apply) ->
                val id = member()
                apply(id)
                assertFalse(visibility.broadcastable(id), "broadcastable while $state")
            }
            // Travelling but visible while travelling is still broadcastable.
            val traveller = member()
            dataSource.insertActiveTravelLocation(traveller, "Elsewhere", latitude = 40.71, longitude = -74.0)
            assertTrue(visibility.broadcastable(traveller))
            // An id with no account at all.
            assertFalse(visibility.broadcastable(UUID.randomUUID()))
        }

    @Test
    fun onlyBroadcastableMembersReachTheStreamInEitherDirection() =
        runBlocking {
            val visible = member()
            val gridHidden = member().also { dataSource.exec("UPDATE discovery_settings SET grid_visible = false WHERE user_id = ?", it) }
            val deleted = member().also { dataSource.exec("UPDATE users SET deleted_at = now() WHERE id = ?", it) }
            val broadcaster = PresenceBroadcaster()
            val tracker = OnlinePresenceTracker()
            val service =
                PresenceService(
                    repository = SetPresence(),
                    onlineTracker = tracker,
                    broadcaster = broadcaster,
                    broadcastAllowed = visibility::broadcastable,
                )
            val scanner =
                PresenceOfflineScanner(
                    tracker = tracker,
                    // Knows nobody: everyone tracked "went offline".
                    repository = SetPresence(),
                    broadcaster = broadcaster,
                    broadcastAllowed = visibility::broadcastable,
                    offlineJitterMillis = { 0 },
                )

            coroutineScope {
                val received = mutableListOf<PresenceDeltaDto>()
                val job = broadcaster.deltas.onEach { received += it }.launchIn(this)
                withTimeoutOrNull(2_000) { broadcaster.subscriptionCount.first { it > 0 } }

                listOf(visible, gridHidden, deleted).forEach { service.refreshOnline(it) }
                scanner.scanOnce()
                withTimeoutOrNull(2_000) { while (received.size < 2) delay(5) }
                delay(100)
                job.cancel()

                assertEquals(
                    listOf(true to visible.toString(), false to visible.toString()),
                    received.map { it.online to it.userId },
                )
            }
            scanner.close()
        }

    @Test
    fun aSubscriptionIsCappedAndDeduplicated() {
        val ids = List(MAX_PRESENCE_SUBSCRIPTION + 50) { UUID.randomUUID().toString() }
        val parsed = parseSubscription(listOf("not-a-uuid", ids[0]) + ids)

        assertEquals(MAX_PRESENCE_SUBSCRIPTION, parsed.size)
        assertEquals(ids.take(MAX_PRESENCE_SUBSCRIPTION).map(UUID::fromString).toSet(), parsed)
    }
}

private class SetPresence : PresenceRepository {
    private val online = mutableSetOf<UUID>()

    override suspend fun heartbeat(userId: UUID): PresenceState = PresenceState(online = true, expiresInSeconds = 900, cameOnline = online.add(userId))

    override suspend fun onlineUserIds(userIds: Collection<UUID>): Set<UUID> = userIds.filterTo(mutableSetOf()) { it in online }
}
