package com.pynapli.server.presence

import com.pynapli.shared.network.dto.PresenceDeltaDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PresenceOfflineScannerTest {

    @Test
    fun emitsOfflineDeltaOnlyForDisappearedUsers() = runBlocking {
        val stillOnlineUser = UUID.randomUUID()
        val disappearedUser = UUID.randomUUID()

        val tracker = OnlinePresenceTracker().apply {
            markOnline(stillOnlineUser)
            markOnline(disappearedUser)
        }
        val repository = FakePresenceRepository(onlineSet = setOf(stillOnlineUser))
        val broadcaster = PresenceBroadcaster()
        val scanner = PresenceOfflineScanner(
            tracker = tracker,
            repository = repository,
            broadcaster = broadcaster,
            clock = Clock.fixed(Instant.parse("2026-05-09T12:00:00Z"), ZoneOffset.UTC)
        )

        coroutineScope {
            val received = mutableListOf<PresenceDeltaDto>()
            val observerJob = broadcaster.deltas
                .onEach { received += it }
                .launchIn(this)

            val scanJob = async(Dispatchers.Default) { scanner.scanOnce() }
            scanJob.await()
            // Allow the broadcaster's SharedFlow to deliver to the observer.
            withTimeoutOrNull(500) {
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
    fun emitsNothingWhenAllStillOnline() = runBlocking {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val tracker = OnlinePresenceTracker().apply {
            markOnline(a)
            markOnline(b)
        }
        val repository = FakePresenceRepository(onlineSet = setOf(a, b))
        val broadcaster = PresenceBroadcaster()
        val scanner = PresenceOfflineScanner(
            tracker = tracker,
            repository = repository,
            broadcaster = broadcaster
        )

        coroutineScope {
            val received = mutableListOf<PresenceDeltaDto>()
            val observerJob = broadcaster.deltas
                .onEach { received += it }
                .launchIn(this)

            scanner.scanOnce()
            delay(100)
            observerJob.cancel()

            assertTrue(received.isEmpty(), "No offline deltas expected when all tracked users are still online")
        }

        assertEquals(setOf(a, b), tracker.snapshot())
    }
}

private class FakePresenceRepository(
    private val onlineSet: Set<UUID>
) : PresenceRepository {
    override suspend fun heartbeat(userId: UUID): PresenceState =
        PresenceState(online = true, expiresInSeconds = 300)

    override suspend fun onlineUserIds(userIds: Collection<UUID>): Set<UUID> =
        userIds.toSet().intersect(onlineSet)
}
