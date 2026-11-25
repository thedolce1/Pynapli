package com.pynapli.server.presence

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.SessionRecord
import com.pynapli.server.auth.security.Sha256
import com.pynapli.server.notifications.JdbcNotificationRepository
import com.pynapli.server.notifications.NotificationService
import com.pynapli.server.notifications.configureNotificationRoutes
import com.pynapli.server.plugins.configureSerialization
import com.pynapli.server.plugins.configureStatusPages
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.server.testsupport.SingleSessionAuthRepository
import com.pynapli.shared.network.dto.PresenceDeltaDto
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Privacy review F3 follow-up, behavioural: logout-adjacent calls are not activity, and a refresh that
 * was queued before the member signed out can never bring presence back or announce "came online".
 */
class PresenceLogoutRaceTest {
    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    @Test
    fun unregisteringAPushDeviceIsNotActivity() =
        testApplication {
            val userId = UUID.randomUUID()
            val activity = AtomicInteger()
            val authService = authServiceFor("token-1", userId, onAuthenticated = { activity.incrementAndGet() })
            application {
                configureSerialization()
                configureStatusPages()
                configureNotificationRoutes(authService, NotificationService(JdbcNotificationRepository(PynapliTestDatabase.dataSource)))
            }

            val response =
                client.post("/v1/notifications/devices/unregister") {
                    header(HttpHeaders.Authorization, "Bearer token-1")
                    contentType(ContentType.Application.Json)
                    setBody("""{"platform":"android","deviceToken":"device-token-0123456789"}""")
                }

            assertEquals(HttpStatusCode.NoContent, response.status)
            assertEquals(0, activity.get(), "the first step of logout must not mark the member online")
        }

    @Test
    fun aRefreshQueuedBeforeLogoutWritesNothingAndAnnouncesNothing() =
        runBlocking {
            val clock = StepClock(Instant.parse("2026-11-25T10:00:00Z"))
            val repository = TombstonePresence()
            val broadcaster = PresenceBroadcaster()
            val service = PresenceService(repository, onlineTracker = OnlinePresenceTracker(), broadcaster = broadcaster, clock = clock)
            val userId = UUID.randomUUID()

            coroutineScope {
                val received = mutableListOf<PresenceDeltaDto>()
                val job = broadcaster.deltas.onEach { received += it }.launchIn(this)
                withTimeoutOrNull(2_000) { broadcaster.subscriptionCount.first { it > 0 } }

                // The request (e.g. push unregister on an older build) claims a refresh at 10:00:00...
                val activityAt = requireNotNull(service.claimRefreshAt(userId))
                // ...logout ends presence a second later, before the queued refresh has run...
                clock.now = clock.now.plusSeconds(1)
                service.endPresence(userId)
                // ...and only then does the queued refresh run.
                clock.now = clock.now.plusSeconds(1)
                service.refreshOnline(userId, activityAt)
                delay(150)
                job.cancel()

                assertEquals(emptyList(), received, "no came-online after sign-out")
            }
            assertEquals(emptySet(), service.onlineUserIds(listOf(userId)), "presence must stay ended")
            assertEquals(0, repository.writes, "the stale refresh must not write the key at all")

            // Real activity after the logout (signing back in) counts again at once.
            clock.now = clock.now.plusSeconds(1)
            service.touch(userId)
            assertEquals(setOf(userId), service.onlineUserIds(listOf(userId)))
        }

    @Test
    fun aLogoutLandingBetweenTheChecksStillLeavesNoKeyAndNoDelta() =
        runBlocking {
            val clock = StepClock(Instant.parse("2026-11-25T10:00:00Z"))
            val broadcaster = PresenceBroadcaster()
            lateinit var service: PresenceService
            val userId = UUID.randomUUID()
            // The logout lands exactly while the refresh is writing the key.
            val repository = TombstonePresence(onHeartbeat = { clock.now = clock.now.plusSeconds(1); service.endPresence(userId) })
            service = PresenceService(repository, broadcaster = broadcaster, clock = clock)

            coroutineScope {
                val received = mutableListOf<PresenceDeltaDto>()
                val job = broadcaster.deltas.onEach { received += it }.launchIn(this)
                withTimeoutOrNull(2_000) { broadcaster.subscriptionCount.first { it > 0 } }

                service.refreshOnline(userId, requireNotNull(service.claimRefreshAt(userId)))
                delay(150)
                job.cancel()
                assertTrue(received.isEmpty())
            }
            assertEquals(emptySet(), service.onlineUserIds(listOf(userId)))
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

private class StepClock(
    var now: Instant,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now
}

/** In-memory presence with the logout tombstone, like RedisPresenceRepository. */
private class TombstonePresence(
    private val onHeartbeat: (suspend () -> Unit)? = null,
) : PresenceRepository {
    private val online = mutableSetOf<UUID>()
    private val endedAt = mutableMapOf<UUID, Instant>()
    var writes = 0

    override suspend fun heartbeat(userId: UUID): PresenceState {
        writes++
        val cameOnline = online.add(userId)
        onHeartbeat?.invoke()
        return PresenceState(online = true, expiresInSeconds = 900, cameOnline = cameOnline)
    }

    override suspend fun onlineUserIds(userIds: Collection<UUID>): Set<UUID> = userIds.filterTo(mutableSetOf()) { it in online }

    override suspend fun clear(userId: UUID) {
        online -= userId
    }

    override suspend fun markPresenceEnded(
        userId: UUID,
        at: Instant,
    ) {
        endedAt[userId] = at
    }

    override suspend fun presenceEndedAt(userId: UUID): Instant? = endedAt[userId]
}
