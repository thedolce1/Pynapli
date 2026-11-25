package com.pynapli.server.presence

import com.pynapli.shared.network.dto.PresenceDeltaDto
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PresenceOfflineScannerTest {
    @Test
    fun emitsOfflineDeltaOnlyForDisappearedUsers() =
        runBlocking {
            val stillOnlineUser = UUID.randomUUID()
            val disappearedUser = UUID.randomUUID()

            val tracker =
                OnlinePresenceTracker().apply {
                    markOnline(stillOnlineUser)
                    markOnline(disappearedUser)
                }
            val repository = FakePresenceRepository(onlineSet = setOf(stillOnlineUser))
            val broadcaster = PresenceBroadcaster()
            val scanner =
                PresenceOfflineScanner(
                    tracker = tracker,
                    repository = repository,
                    broadcaster = broadcaster,
                    clock = Clock.fixed(Instant.parse("2026-05-09T12:00:00Z"), ZoneOffset.UTC),
                    offlineJitterMillis = { 0 },
                )

            coroutineScope {
                val received = mutableListOf<PresenceDeltaDto>()
                val observerJob =
                    broadcaster.deltas
                        .onEach { received += it }
                        .launchIn(this)

                // The broadcaster's SharedFlow has replay=0, so an emission before the collector is
                // actually subscribed would be dropped. Wait for the subscription to register before
                // running the scan, instead of racing it on another dispatcher (the CI-flake source).
                withTimeoutOrNull(2_000) {
                    broadcaster.subscriptionCount.first { it > 0 }
                }

                scanner.scanOnce()

                // Allow the broadcaster's SharedFlow to deliver to the observer.
                withTimeoutOrNull(2_000) {
                    while (received.isEmpty()) delay(5)
                }
                observerJob.cancel()

                assertEquals(1, received.size)
                assertEquals(disappearedUser.toString(), received[0].userId)
                assertEquals(false, received[0].online)
                assertEquals("2026-05-09T12:00:00Z", received[0].lastActiveAt)
            }

            assertEquals(setOf(stillOnlineUser), tracker.snapshot())
        }

    @Test
    fun emitsNothingWhenAllStillOnline() =
        runBlocking {
            val a = UUID.randomUUID()
            val b = UUID.randomUUID()
            val tracker =
                OnlinePresenceTracker().apply {
                    markOnline(a)
                    markOnline(b)
                }
            val repository = FakePresenceRepository(onlineSet = setOf(a, b))
            val broadcaster = PresenceBroadcaster()
            val scanner =
                PresenceOfflineScanner(
                    tracker = tracker,
                    repository = repository,
                    broadcaster = broadcaster,
                    offlineJitterMillis = { 0 },
                )

            coroutineScope {
                val received = mutableListOf<PresenceDeltaDto>()
                val observerJob =
                    broadcaster.deltas
                        .onEach { received += it }
                        .launchIn(this)

                // Ensure the collector is subscribed first, so a spurious emission would be caught.
                withTimeoutOrNull(2_000) {
                    broadcaster.subscriptionCount.first { it > 0 }
                }
                scanner.scanOnce()
                delay(100)
                observerJob.cancel()

                assertTrue(received.isEmpty(), "No offline deltas expected when all tracked users are still online")
            }

            assertEquals(setOf(a, b), tracker.snapshot())
        }

    // Privacy review F2: "went offline" is delayed by its own random jitter, and dropped if the person
    // is back before it fires.
    @Test
    fun wentOfflineWaitsForItsJitterAndIsDroppedIfThePersonCameBack() =
        runBlocking {
            val stays = UUID.randomUUID()
            val returns = UUID.randomUUID()
            val tracker =
                OnlinePresenceTracker().apply {
                    markOnline(stays)
                    markOnline(returns)
                }
            val repository = MutablePresenceRepository()
            val broadcaster = PresenceBroadcaster()
            val scanner =
                PresenceOfflineScanner(
                    tracker = tracker,
                    repository = repository,
                    broadcaster = broadcaster,
                    offlineJitterMillis = { 400 },
                )

            coroutineScope {
                val received = mutableListOf<PresenceDeltaDto>()
                val job = broadcaster.deltas.onEach { received += it }.launchIn(this)
                withTimeoutOrNull(2_000) { broadcaster.subscriptionCount.first { it > 0 } }

                scanner.scanOnce()
                delay(150)
                assertTrue(received.isEmpty(), "nothing before the jitter elapses")
                // One of them comes back inside the delay.
                repository.online += returns
                withTimeoutOrNull(3_000) { while (received.isEmpty()) delay(10) }
                delay(600)
                job.cancel()

                assertEquals(listOf(stays.toString()), received.map { it.userId })
                assertEquals(false, received.single().online)
            }
            scanner.close()
        }
}

private class MutablePresenceRepository : PresenceRepository {
    val online =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<UUID>()

    override suspend fun heartbeat(userId: UUID): PresenceState = PresenceState(online = true, expiresInSeconds = 900)

    override suspend fun onlineUserIds(userIds: Collection<UUID>): Set<UUID> = userIds.filterTo(mutableSetOf()) { it in online }
}

private class FakePresenceRepository(
    private val onlineSet: Set<UUID>,
) : PresenceRepository {
    override suspend fun heartbeat(userId: UUID): PresenceState = PresenceState(online = true, expiresInSeconds = 300)

    override suspend fun onlineUserIds(userIds: Collection<UUID>): Set<UUID> = userIds.toSet().intersect(onlineSet)
}
