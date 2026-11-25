package com.pynapli.server.presence

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.SessionRecord
import com.pynapli.server.auth.security.Sha256
import com.pynapli.server.testsupport.SingleSessionAuthRepository
import com.pynapli.shared.network.dto.PresenceDeltaDto
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PresenceServiceTest {
    @Test
    fun refreshOnlineMarksUserOnline() =
        runBlocking {
            val repository = InMemoryPresenceRepository()
            val service = PresenceService(repository)
            val userId = UUID.randomUUID()

            service.refreshOnline(userId)

            assertEquals(setOf(userId), service.onlineUserIds(listOf(userId)))
        }

    @Test
    fun touchWritesAtMostOncePerIntervalPerUser() =
        runBlocking {
            val clock = MutableClock(Instant.parse("2026-11-24T10:00:00Z"))
            val repository = InMemoryPresenceRepository()
            val service = PresenceService(repository, clock = clock)
            val a = UUID.randomUUID()
            val b = UUID.randomUUID()

            repeat(5) { service.touch(a) }
            service.touch(b)
            assertEquals(2, repository.heartbeats, "one write per user inside the interval")

            clock.now = clock.now.plusSeconds(59)
            service.touch(a)
            assertEquals(2, repository.heartbeats)

            clock.now = clock.now.plusSeconds(1)
            service.touch(a)
            assertEquals(3, repository.heartbeats, "the next minute writes again")
        }

    @Test
    fun claimRefreshIsTheSameThrottleTheAuthHookUses() {
        val clock = MutableClock(Instant.parse("2026-11-24T10:00:00Z"))
        val service = PresenceService(InMemoryPresenceRepository(), clock = clock)
        val userId = UUID.randomUUID()

        assertTrue(service.claimRefresh(userId))
        assertFalse(service.claimRefresh(userId))
        clock.now = clock.now.plus(PresenceService.DEFAULT_REFRESH_INTERVAL)
        assertTrue(service.claimRefresh(userId))
    }

    @Test
    fun comingOnlineEmitsAnOnlineDeltaAndStayingOnlineEmitsNothing() =
        runBlocking {
            val clock = MutableClock(Instant.parse("2026-11-24T10:00:42.123Z"))
            val repository = InMemoryPresenceRepository()
            val broadcaster = PresenceBroadcaster()
            val tracker = OnlinePresenceTracker()
            val service = PresenceService(repository, onlineTracker = tracker, broadcaster = broadcaster, clock = clock)
            val userId = UUID.randomUUID()

            coroutineScope {
                val received = mutableListOf<PresenceDeltaDto>()
                val job = broadcaster.deltas.onEach { received += it }.launchIn(this)
                withTimeoutOrNull(2_000) { broadcaster.subscriptionCount.first { it > 0 } }

                service.refreshOnline(userId)
                // Still online: a second refresh is not a transition.
                service.refreshOnline(userId)
                withTimeoutOrNull(2_000) { while (received.isEmpty()) delay(5) }
                delay(100)
                job.cancel()

                assertEquals(1, received.size)
                assertEquals(userId.toString(), received.single().userId)
                assertTrue(received.single().online)
                // Cut to the minute, never the exact request time.
                assertEquals("2026-11-24T10:00:00Z", received.single().lastActiveAt)
            }
            // The offline scanner now watches this person, so it can say when they go.
            assertEquals(setOf(userId), tracker.snapshot())
        }

    @Test
    fun anAuthenticatedRequestRunsTheHookWithTheTokenUser() =
        runBlocking {
            val userId = UUID.randomUUID()
            val seen = mutableListOf<UUID>()
            val authService = authServiceFor("token-1", userId, onAuthenticated = { seen += it })

            authService.authenticate("token-1")
            authService.authenticate("token-1")
            runCatching { authService.authenticate("wrong-token") }

            assertEquals(listOf(userId, userId), seen, "every successful authenticate, never a failed one")
        }

    @Test
    fun aFailingHookNeverFailsTheRequest() =
        runBlocking {
            val userId = UUID.randomUUID()
            val authService = authServiceFor("token-1", userId, onAuthenticated = { error("presence down") })

            assertEquals(userId, authService.authenticate("token-1").id)
        }

    @Test
    fun heartbeatRouteMarksTheCallerOnlineAndEchoesNothing() =
        testApplication {
            val userId = UUID.randomUUID()
            val repository = InMemoryPresenceRepository()
            val presenceService = PresenceService(repository)
            val authService = authServiceFor("token-1", userId)
            application { configurePresenceHeartbeatRoutes(authService, presenceService) }

            val response = client.post("/v1/presence/heartbeat") { header(HttpHeaders.Authorization, "Bearer token-1") }
            assertEquals(HttpStatusCode.NoContent, response.status)
            assertEquals("", response.bodyAsText())
            assertEquals(setOf(userId), presenceService.onlineUserIds(listOf(userId)))

            // Calling it again inside the minute costs no write.
            client.post("/v1/presence/heartbeat") { header(HttpHeaders.Authorization, "Bearer token-1") }
            assertEquals(1, repository.heartbeats)
        }

    private fun authServiceFor(
        token: String,
        userId: UUID,
        onAuthenticated: ((UUID) -> Unit)? = null,
    ): AuthService {
        val now = Instant.now()
        val session =
            SessionRecord(
                id = UUID.randomUUID(),
                userId = userId,
                accessTokenHash = Sha256.hash(token),
                refreshTokenHash = Sha256.hash("refresh-$token"),
                accessExpiresAt = now.plus(1, ChronoUnit.HOURS),
                refreshExpiresAt = now.plus(30, ChronoUnit.DAYS),
                revokedAt = null,
                userStatus = "active",
            )
        return AuthService(SingleSessionAuthRepository(session), onAuthenticated = onAuthenticated)
    }
}

private class MutableClock(
    var now: Instant,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now
}

private class InMemoryPresenceRepository : PresenceRepository {
    private val onlineUserIds = mutableSetOf<UUID>()
    var heartbeats = 0

    override suspend fun heartbeat(userId: UUID): PresenceState {
        heartbeats++
        val cameOnline = onlineUserIds.add(userId)
        return PresenceState(online = true, expiresInSeconds = 900, cameOnline = cameOnline)
    }

    override suspend fun onlineUserIds(userIds: Collection<UUID>): Set<UUID> = userIds.filterTo(mutableSetOf()) { it in onlineUserIds }
}
