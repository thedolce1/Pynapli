package com.pynapli.server.presence

import com.pynapli.server.grid.insertDiscoverableUser
import com.pynapli.server.grid.insertSession
import com.pynapli.server.profile.JdbcProfileRepository
import com.pynapli.server.profile.ProfileService
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.PynapliJson
import com.pynapli.shared.network.dto.PublicProfileDto
import com.pynapli.shared.presence.LastActiveBucket
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Last seen: coarse server-computed buckets only, never a timestamp, nothing for incognito. */
class LastActiveIntegrationTest {
    private val dataSource: DataSource = PynapliTestDatabase.dataSource
    private val now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    private fun user(name: String): UUID = UUID.randomUUID().also { dataSource.insertDiscoverableUser(it, name, age = 30, longitude = -122.08, latitude = 37.42) }

    private fun service(online: Set<UUID> = emptySet()) =
        ProfileService(
            repository = JdbcProfileRepository(dataSource),
            clock = clock,
            presenceService = PresenceService(FixedPresence(online)),
            lastActiveRepository = JdbcLastActiveRepository(dataSource),
        )

    @Test
    fun bucketsFollowTheLatestHeartbeatOrSession() =
        runBlocking {
            val viewer = user("Viewer")
            val target = user("Target")
            val repository = JdbcLastActiveRepository(dataSource)

            assertNull(service().getPublicProfile(viewer, target).lastActive, "Never seen: no label")

            dataSource.insertSession(target, lastSeenAt = now.minus(Duration.ofDays(3)))
            assertEquals(LastActiveBucket.ThisWeek.key, service().getPublicProfile(viewer, target).lastActive)

            repository.record(target, now.minus(Duration.ofHours(5)))
            assertEquals(LastActiveBucket.Today.key, service().getPublicProfile(viewer, target).lastActive)

            repository.record(target, now.minus(Duration.ofMinutes(10)))
            assertEquals(LastActiveBucket.Recent.key, service().getPublicProfile(viewer, target).lastActive)
            // An older heartbeat never moves the stored value backwards.
            repository.record(target, now.minus(Duration.ofDays(2)))
            assertEquals(LastActiveBucket.Recent.key, service().getPublicProfile(viewer, target).lastActive)

            assertEquals(LastActiveBucket.OnlineNow.key, service(online = setOf(target)).getPublicProfile(viewer, target).lastActive)
        }

    @Test
    fun olderThanAWeekShowsNothing() =
        runBlocking {
            val viewer = user("Viewer")
            val target = user("Target")
            JdbcLastActiveRepository(dataSource).record(target, now.minus(Duration.ofDays(9)))

            assertNull(service().getPublicProfile(viewer, target).lastActive)
        }

    @Test
    fun incognitoShowsNoLastSeen() =
        runBlocking {
            val viewer = user("Viewer")
            val target = user("Target")
            JdbcLastActiveRepository(dataSource).record(target, now.minus(Duration.ofMinutes(5)))
            dataSource.connection.use { connection ->
                connection.prepareStatement("UPDATE discovery_settings SET incognito = true WHERE user_id = ?").use { statement ->
                    statement.setObject(1, target)
                    statement.executeUpdate()
                }
            }

            val lookup = JdbcLastActiveRepository(dataSource).lookup(target)
            assertEquals(true, lookup.hidden)
            assertNull(lookup.lastActiveAt)
            // Incognito profiles don't open for others at all today; if one ever does, it carries no label.
            val profile = runCatching { service(online = setOf(target)).getPublicProfile(viewer, target) }.getOrNull()
            assertNull(profile?.lastActive)
        }

    @Test
    fun theWireCarriesABucketKeyNeverATimestamp() =
        runBlocking {
            val viewer = user("Viewer")
            val target = user("Target")
            JdbcLastActiveRepository(dataSource).record(target, now.minus(Duration.ofMinutes(42)))

            val json = PynapliJson.encodeToString(PublicProfileDto.serializer(), service().getPublicProfile(viewer, target))
            assertFalse(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}""").containsMatchIn(json), json)
            assertEquals(true, json.contains("\"lastActive\":\"${LastActiveBucket.Today.key}\""), json)
        }

    @Test
    fun theRecorderWritesAtMostOncePerInterval() =
        runBlocking {
            val writes = mutableListOf<Instant>()
            val counting =
                object : LastActiveRepository {
                    override suspend fun record(
                        userId: UUID,
                        at: Instant,
                    ) {
                        writes += at
                    }

                    override suspend fun lookup(userId: UUID) = LastActiveLookup(hidden = false, lastActiveAt = null)
                }
            val recorder = LastActiveRecorder(counting, clock)
            val userId = UUID.randomUUID()
            repeat(5) { recorder.record(userId) }

            assertEquals(1, writes.size)
        }
}

private class FixedPresence(
    private val online: Set<UUID>,
) : PresenceRepository {
    override suspend fun heartbeat(userId: UUID): PresenceState = PresenceState(online = true, expiresInSeconds = 300)

    override suspend fun onlineUserIds(userIds: Collection<UUID>): Set<UUID> = userIds.filterTo(mutableSetOf()) { it in online }
}
