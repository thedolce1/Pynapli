package com.pynapli.server.tonight

import com.pynapli.server.grid.insertActiveTravelLocation
import com.pynapli.server.grid.moveUser
import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.presence.PresenceService
import com.pynapli.server.safety.JdbcBlockRepository
import com.pynapli.server.safety.JdbcHiddenProfileRepository
import com.pynapli.server.testsupport.PynapliTestDatabase
import io.kotest.core.spec.style.StringSpec
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PT-3 Playing Tonight feed integration tests. Mirrors the Grid repository test
 * setup (PostGIS Testcontainer + Flyway) and exercises [TonightFeedService] over a
 * real [JdbcTonightFeedRepository], with MockK presence + a fake media resolver.
 */
class TonightFeedServiceIntegrationTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest {
            PynapliTestDatabase.reset()
        }

        // Viewer + nearby reference point (Paris-ish). Owners are placed near or far.
        val viewerLon = 2.3522
        val viewerLat = 48.8566

        fun service(online: Set<UUID> = emptySet()): TonightFeedService {
            val presence = mockk<PresenceService>()
            coEvery { presence.onlineUserIds(any()) } answers {
                val ids = firstArg<Collection<UUID>>()
                ids.filterTo(mutableSetOf()) { it in online }
            }
            return TonightFeedService(
                repository = JdbcTonightFeedRepository(dataSource),
                presenceService = presence,
                mediaAssetUrlResolver = FakeMediaResolver,
                blockRepository = JdbcBlockRepository(dataSource),
                hiddenProfileRepository = JdbcHiddenProfileRepository(dataSource),
            )
        }

        "returns a nearby live solo session anchored on the owner" {
            runBlocking {
                val viewer = UUID.randomUUID()
                val owner = UUID.randomUUID()
                dataSource.seedDiscoverableUser(viewer, "Viewer", viewerLon, viewerLat)
                dataSource.seedDiscoverableUser(owner, "Owner Olive", viewerLon + 0.001, viewerLat)
                dataSource.insertActiveSoloSession(owner, intent = "solo")

                val response = service().feed(viewer, radiusMeters = 50_000, intent = null, limit = 100)

                assertEquals(1, response.items.size)
                val item = response.items.single()
                assertEquals("solo", item.mode)
                assertEquals(owner.toString(), item.person.userId)
                assertEquals("Owner Olive", item.person.displayName)
                assertNotNull(item.person.zoneKey)
                assertNull(item.linkedPartner)
            }
        }

        "excludes proposed, declined, ended and expired sessions" {
            runBlocking {
                val viewer = UUID.randomUUID()
                dataSource.seedDiscoverableUser(viewer, "Viewer", viewerLon, viewerLat)

                val proposedOwner = UUID.randomUUID()
                val declinedOwner = UUID.randomUUID()
                val endedOwner = UUID.randomUUID()
                val expiredOwner = UUID.randomUUID()
                dataSource.seedDiscoverableUser(proposedOwner, "Prop", viewerLon + 0.001, viewerLat)
                dataSource.seedDiscoverableUser(declinedOwner, "Decl", viewerLon + 0.001, viewerLat)
                dataSource.seedDiscoverableUser(endedOwner, "Ended", viewerLon + 0.001, viewerLat)
                dataSource.seedDiscoverableUser(expiredOwner, "Expired", viewerLon + 0.001, viewerLat)
                dataSource.insertSoloSession(proposedOwner, status = "proposed", expiresInHours = 2)
                dataSource.insertSoloSession(declinedOwner, status = "declined", expiresInHours = 2)
                dataSource.insertSoloSession(endedOwner, status = "ended", expiresInHours = 2)
                // active but already past expiry.
                dataSource.insertSoloSession(expiredOwner, status = "active", expiresInHours = -1)

                val response = service().feed(viewer, radiusMeters = 50_000, intent = null, limit = 100)

                assertTrue(response.items.isEmpty(), "expected no items, got ${response.items.size}")
            }
        }

        "excludes map_visible=false sessions" {
            runBlocking {
                val viewer = UUID.randomUUID()
                val owner = UUID.randomUUID()
                dataSource.seedDiscoverableUser(viewer, "Viewer", viewerLon, viewerLat)
                dataSource.seedDiscoverableUser(owner, "Hidden Hank", viewerLon + 0.001, viewerLat)
                dataSource.insertActiveSoloSession(owner, intent = "solo", mapVisible = false)

                val response = service().feed(viewer, radiusMeters = 50_000, intent = null, limit = 100)

                assertTrue(response.items.isEmpty())
            }
        }

        "excludes out-of-radius owners" {
            runBlocking {
                val viewer = UUID.randomUUID()
                val owner = UUID.randomUUID()
                dataSource.seedDiscoverableUser(viewer, "Viewer", viewerLon, viewerLat)
                // ~1 degree of longitude away => far more than 50km.
                dataSource.seedDiscoverableUser(owner, "Far Fred", viewerLon + 1.5, viewerLat)
                dataSource.insertActiveSoloSession(owner, intent = "solo")

                val response = service().feed(viewer, radiusMeters = 50_000, intent = null, limit = 100)

                assertTrue(response.items.isEmpty())
            }
        }

        "excludes the viewer's own session" {
            runBlocking {
                val viewer = UUID.randomUUID()
                dataSource.seedDiscoverableUser(viewer, "Viewer", viewerLon, viewerLat)
                dataSource.insertActiveSoloSession(viewer, intent = "solo")

                val response = service().feed(viewer, radiusMeters = 50_000, intent = null, limit = 100)

                assertTrue(response.items.isEmpty())
            }
        }

        "drops a blocked owner and a hidden owner but keeps a clean one" {
            runBlocking {
                val viewer = UUID.randomUUID()
                val blockedOwner = UUID.randomUUID()
                val hiddenOwner = UUID.randomUUID()
                val cleanOwner = UUID.randomUUID()
                dataSource.seedDiscoverableUser(viewer, "Viewer", viewerLon, viewerLat)
                dataSource.seedDiscoverableUser(blockedOwner, "Blocked", viewerLon + 0.001, viewerLat)
                dataSource.seedDiscoverableUser(hiddenOwner, "Hidden", viewerLon + 0.001, viewerLat)
                dataSource.seedDiscoverableUser(cleanOwner, "Clean", viewerLon + 0.001, viewerLat)
                dataSource.insertActiveSoloSession(blockedOwner, intent = "solo")
                dataSource.insertActiveSoloSession(hiddenOwner, intent = "solo")
                dataSource.insertActiveSoloSession(cleanOwner, intent = "solo")
                dataSource.insertBlock(viewer, blockedOwner)
                dataSource.insertHidden(viewer, hiddenOwner)

                val response = service().feed(viewer, radiusMeters = 50_000, intent = null, limit = 100)

                assertEquals(setOf(cleanOwner.toString()), response.items.map { it.person.userId }.toSet())
            }
        }

        "together session surfaces the partner mini; a blocked partner nulls the mini but keeps the row" {
            runBlocking {
                val viewer = UUID.randomUUID()
                val ownerA = UUID.randomUUID()
                val partnerA = UUID.randomUUID()
                val ownerB = UUID.randomUUID()
                val partnerB = UUID.randomUUID()
                dataSource.seedDiscoverableUser(viewer, "Viewer", viewerLon, viewerLat)
                dataSource.seedDiscoverableUser(ownerA, "Owner A", viewerLon + 0.001, viewerLat)
                dataSource.seedDiscoverableUser(partnerA, "Partner A", viewerLon + 0.001, viewerLat)
                dataSource.seedDiscoverableUser(ownerB, "Owner B", viewerLon + 0.002, viewerLat)
                dataSource.seedDiscoverableUser(partnerB, "Partner B", viewerLon + 0.002, viewerLat)
                dataSource.insertActiveTogetherSession(ownerA, partnerA, intent = "together")
                dataSource.insertActiveTogetherSession(ownerB, partnerB, intent = "together")
                // Viewer blocked partnerB -> ownerB row stays, but the mini must be nulled.
                dataSource.insertBlock(viewer, partnerB)

                val response = service().feed(viewer, radiusMeters = 50_000, intent = null, limit = 100)

                val byOwner = response.items.associateBy { it.person.userId }
                val rowA = byOwner.getValue(ownerA.toString())
                assertNotNull(rowA.linkedPartner)
                assertEquals(partnerA.toString(), rowA.linkedPartner!!.userId)
                assertEquals("Partner A", rowA.linkedPartner!!.displayName)

                val rowB = byOwner.getValue(ownerB.toString())
                assertNull(rowB.linkedPartner)
            }
        }

        "excludes a paused together session, and it returns once resumed" {
            runBlocking {
                val viewer = UUID.randomUUID()
                val owner = UUID.randomUUID()
                val partner = UUID.randomUUID()
                dataSource.seedDiscoverableUser(viewer, "Viewer", viewerLon, viewerLat)
                dataSource.seedDiscoverableUser(owner, "Paused Pam", viewerLon + 0.001, viewerLat)
                dataSource.seedDiscoverableUser(partner, "Partner Pat", viewerLon + 0.001, viewerLat)
                dataSource.insertActiveTogetherSession(owner, partner, intent = "together")

                // Visible while running.
                assertEquals(1, service().feed(viewer, radiusMeters = 50_000, intent = null, limit = 100).items.size)

                // Pause it -> hidden from the feed even though status stays 'active'.
                dataSource.setPausedAt(owner, Instant.now())
                assertTrue(service().feed(viewer, radiusMeters = 50_000, intent = null, limit = 100).items.isEmpty())

                // Resume it -> reappears.
                dataSource.setPausedAt(owner, null)
                assertEquals(1, service().feed(viewer, radiusMeters = 50_000, intent = null, limit = 100).items.size)
            }
        }

        "intent filter narrows results" {
            runBlocking {
                val viewer = UUID.randomUUID()
                val soloOwner = UUID.randomUUID()
                val socialOwner = UUID.randomUUID()
                dataSource.seedDiscoverableUser(viewer, "Viewer", viewerLon, viewerLat)
                dataSource.seedDiscoverableUser(soloOwner, "Solo", viewerLon + 0.001, viewerLat)
                dataSource.seedDiscoverableUser(socialOwner, "Social", viewerLon + 0.001, viewerLat)
                dataSource.insertActiveSoloSession(soloOwner, intent = "solo")
                dataSource.insertActiveSoloSession(socialOwner, intent = "social")

                val response = service().feed(viewer, radiusMeters = 50_000, intent = "social", limit = 100)

                assertEquals(setOf(socialOwner.toString()), response.items.map { it.person.userId }.toSet())
            }
        }

        "an invalid intent is rejected with INVALID_TONIGHT_INTENT" {
            runBlocking {
                val viewer = UUID.randomUUID()
                dataSource.seedDiscoverableUser(viewer, "Viewer", viewerLon, viewerLat)
                val error =
                    assertFailsWith<TonightSessionException> {
                        service().feed(viewer, radiusMeters = 50_000, intent = "bogus", limit = 100)
                    }
                assertEquals("INVALID_TONIGHT_INTENT", error.code)
            }
        }

        "distance is omitted when the owner hides distance" {
            runBlocking {
                val viewer = UUID.randomUUID()
                val owner = UUID.randomUUID()
                dataSource.seedDiscoverableUser(viewer, "Viewer", viewerLon, viewerLat)
                dataSource.seedDiscoverableUser(owner, "Private", viewerLon + 0.001, viewerLat, showDistance = false)
                dataSource.insertActiveSoloSession(owner, intent = "solo")

                val response = service().feed(viewer, radiusMeters = 50_000, intent = null, limit = 100)

                val item = response.items.single()
                assertNull(item.person.distanceLabel)
                assertNull(item.person.distanceMeters)
                // L-11: the zone is location too, so it is withheld with the distance.
                assertNull(item.person.zoneKey)
            }
        }

        "distance is present when the owner shows distance" {
            runBlocking {
                val viewer = UUID.randomUUID()
                val owner = UUID.randomUUID()
                dataSource.seedDiscoverableUser(viewer, "Viewer", viewerLon, viewerLat)
                dataSource.seedDiscoverableUser(owner, "Open", viewerLon + 0.001, viewerLat, showDistance = true)
                dataSource.insertActiveSoloSession(owner, intent = "solo")

                val response = service().feed(viewer, radiusMeters = 50_000, intent = null, limit = 100)

                val item = response.items.single()
                assertNotNull(item.person.distanceLabel)
                assertNotNull(item.person.distanceMeters)
            }
        }

        "online reflects the presence set" {
            runBlocking {
                val viewer = UUID.randomUUID()
                val onlineOwner = UUID.randomUUID()
                val offlineOwner = UUID.randomUUID()
                dataSource.seedDiscoverableUser(viewer, "Viewer", viewerLon, viewerLat)
                dataSource.seedDiscoverableUser(onlineOwner, "On", viewerLon + 0.001, viewerLat)
                dataSource.seedDiscoverableUser(offlineOwner, "Off", viewerLon + 0.002, viewerLat)
                dataSource.insertActiveSoloSession(onlineOwner, intent = "solo")
                dataSource.insertActiveSoloSession(offlineOwner, intent = "solo")

                val response =
                    service(online = setOf(onlineOwner))
                        .feed(viewer, radiusMeters = 50_000, intent = null, limit = 100)

                val byOwner = response.items.associateBy { it.person.userId }
                assertTrue(byOwner.getValue(onlineOwner.toString()).person.online)
                assertTrue(!byOwner.getValue(offlineOwner.toString()).person.online)
            }
        }

        "zoneKey is a 0.02-degree zone: owners kilometres apart inside one zone are indistinguishable" {
            runBlocking {
                val viewer = UUID.randomUUID()
                dataSource.seedDiscoverableUser(viewer, "Viewer", viewerLon, viewerLat)
                // Opposite corners of the zone [2.34, 2.36) x [48.84, 48.86): ~2.4 km apart.
                val owners =
                    listOf(2.3405 to 48.8410, 2.3595 to 48.8590, 2.3500 to 48.8500).map { (lon, lat) ->
                        UUID.randomUUID().also { owner ->
                            dataSource.seedDiscoverableUser(owner, "Owner", lon, lat)
                            dataSource.insertActiveSoloSession(owner, intent = "solo")
                        }
                    }

                val zones = service().feed(viewer, radiusMeters = 50_000, intent = null, limit = 100).items.map { it.person.zoneKey }

                assertEquals(owners.size, zones.size)
                assertEquals(listOf("117_2442"), zones.distinct(), "zones must not separate owners inside one zone: $zones")
            }
        }

        "a travelling owner is placed, zoned and measured by their travel point, never their home" {
            runBlocking {
                val viewer = UUID.randomUUID()
                val owner = UUID.randomUUID()
                dataSource.seedDiscoverableUser(viewer, "Viewer", viewerLon, viewerLat)
                // Home ~70 m from the viewer; travelling ~6 km north-west.
                dataSource.seedDiscoverableUser(owner, "Traveller", viewerLon + 0.001, viewerLat)
                dataSource.insertActiveTravelLocation(owner, "Elsewhere", latitude = 48.9050, longitude = 2.3050)
                dataSource.insertActiveSoloSession(owner, intent = "solo")

                val person =
                    service()
                        .feed(viewer, radiusMeters = 50_000, intent = null, limit = 100)
                        .items
                        .single()
                        .person
                assertEquals("115_2445", person.zoneKey)
                assertTrue(assertNotNull(person.distanceMeters) >= 5_000, "home distance leaked: ${person.distanceMeters}")
                // Matched only where they travel: a 2 km radius around the viewer (their home) misses them.
                assertTrue(service().feed(viewer, radiusMeters = 2_000, intent = null, limit = 100).items.isEmpty())
            }
        }

        "an owner who hides distance has a list position that does not depend on where they are" {
            runBlocking {
                val viewer = UUID.randomUUID()
                dataSource.seedDiscoverableUser(viewer, "Viewer", viewerLon, viewerLat)
                val hidden = UUID.randomUUID()
                dataSource.seedDiscoverableUser(hidden, "Hidden", viewerLon + 0.001, viewerLat, showDistance = false)
                dataSource.insertActiveSoloSession(hidden, intent = "solo")
                listOf(0.004, 0.02, 0.05, 0.09, 0.15).forEach { offset ->
                    val owner = UUID.randomUUID()
                    dataSource.seedDiscoverableUser(owner, "Shown", viewerLon + offset, viewerLat)
                    dataSource.insertActiveSoloSession(owner, intent = "solo")
                }

                fun order() = runBlocking { service().feed(viewer, radiusMeters = 25_000, intent = null, limit = 100).items.map { it.person.userId } }
                val near = order()
                dataSource.moveUser(hidden, longitude = viewerLon + 0.12, latitude = viewerLat)
                val far = order()
                assertEquals(near, far, "a hidden owner moving ~9 km changed their place in the feed")
            }
        }

        "surfaces a session photo only once it is moderation-approved" {
            runBlocking {
                val viewer = UUID.randomUUID()
                val approvedOwner = UUID.randomUUID()
                val pendingOwner = UUID.randomUUID()
                dataSource.seedDiscoverableUser(viewer, "Viewer", viewerLon, viewerLat)
                dataSource.seedDiscoverableUser(approvedOwner, "Approved Amy", viewerLon + 0.001, viewerLat)
                dataSource.seedDiscoverableUser(pendingOwner, "Pending Pat", viewerLon + 0.001, viewerLat)

                val approvedPhoto = UUID.randomUUID()
                val pendingPhoto = UUID.randomUUID()
                dataSource.insertMediaAsset(approvedOwner, approvedPhoto, processing = "ready", moderation = "approved")
                dataSource.insertMediaAsset(pendingOwner, pendingPhoto, processing = "ready", moderation = "held")
                dataSource.insertActiveSoloSessionWithPhoto(approvedOwner, approvedPhoto)
                dataSource.insertActiveSoloSessionWithPhoto(pendingOwner, pendingPhoto)

                val items = service().feed(viewer, radiusMeters = 50_000, intent = null, limit = 100).items
                val byOwner = items.associateBy { it.person.userId }

                // Approved photo is surfaced; the unreviewed one is nulled but the session still shows.
                assertNotNull(byOwner[approvedOwner.toString()]?.photoUrl)
                assertNotNull(byOwner[pendingOwner.toString()])
                assertNull(byOwner[pendingOwner.toString()]?.photoUrl)
            }
        }
    })

private fun DataSource.insertMediaAsset(
    ownerUserId: UUID,
    assetId: UUID,
    processing: String,
    moderation: String,
) {
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO media_assets (
                    id, owner_user_id, media_type, storage_provider, storage_bucket,
                    storage_object_key, content_type, byte_size, processing_status, moderation_status
                )
                VALUES (?, ?, 'image', 'gcs', 'pynapli-local-media', ?, 'image/jpeg', 1024, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, assetId)
                statement.setObject(2, ownerUserId)
                statement.setString(3, "media/$assetId")
                statement.setString(4, processing)
                statement.setString(5, moderation)
                statement.executeUpdate()
            }
    }
}

private fun DataSource.insertActiveSoloSessionWithPhoto(
    ownerUserId: UUID,
    photoAssetId: UUID,
) {
    val sessionId = UUID.randomUUID()
    val now = Instant.now()
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO tonight_sessions (
                    id, created_by_user_id, mode, intent, host_mode, photo_media_asset_id,
                    map_visible, status, created_at, started_at, expires_at
                )
                VALUES (?, ?, 'solo', 'solo', 'public_meet_first', ?, true, 'active', ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, sessionId)
                statement.setObject(2, ownerUserId)
                statement.setObject(3, photoAssetId)
                statement.setTimestamp(4, Timestamp.from(now))
                statement.setTimestamp(5, Timestamp.from(now))
                statement.setTimestamp(6, Timestamp.from(now.plus(Duration.ofHours(2))))
                statement.executeUpdate()
            }
        connection.insertParticipant(sessionId, ownerUserId, role = "owner", approval = "approved")
    }
}

private object FakeMediaResolver : MediaAssetUrlResolver {
    override fun publicContentUrl(mediaAssetId: UUID): String = "https://media.example/$mediaAssetId"
}

private fun DataSource.seedDiscoverableUser(
    userId: UUID,
    displayName: String,
    longitude: Double,
    latitude: Double,
    showDistance: Boolean = true,
) {
    connection.use { connection ->
        connection
            .prepareStatement("INSERT INTO users (id, status) VALUES (?, 'active')")
            .use { statement ->
                statement.setObject(1, userId)
                statement.executeUpdate()
            }
        connection
            .prepareStatement(
                """
                INSERT INTO profiles (user_id, display_name, display_age, relationship_context, moderation_status, visibility_status)
                VALUES (?, ?, 30, 'single', 'approved', 'visible')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setString(2, displayName)
                statement.executeUpdate()
            }
        connection
            .prepareStatement(
                """
                INSERT INTO discovery_settings (user_id, grid_visible, swipe_visible, incognito, show_distance)
                VALUES (?, true, true, false, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setBoolean(2, showDistance)
                statement.executeUpdate()
            }
        connection
            .prepareStatement(
                """
                INSERT INTO user_locations (user_id, geom, location_source, accuracy_meters)
                VALUES (?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, 'gps', 12)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setDouble(2, longitude)
                statement.setDouble(3, latitude)
                statement.executeUpdate()
            }
    }
}

private fun DataSource.insertActiveSoloSession(
    ownerUserId: UUID,
    intent: String,
    mapVisible: Boolean = true,
) = insertSoloSession(ownerUserId, status = "active", expiresInHours = 2, intent = intent, mapVisible = mapVisible)

private fun DataSource.insertSoloSession(
    ownerUserId: UUID,
    status: String,
    expiresInHours: Long,
    intent: String = "solo",
    mapVisible: Boolean = true,
) {
    val sessionId = UUID.randomUUID()
    val now = Instant.now()
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO tonight_sessions (
                    id, created_by_user_id, mode, intent, host_mode, map_visible, status,
                    created_at, started_at, expires_at
                )
                VALUES (?, ?, 'solo', ?, 'public_meet_first', ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, sessionId)
                statement.setObject(2, ownerUserId)
                statement.setString(3, intent)
                statement.setBoolean(4, mapVisible)
                statement.setString(5, status)
                statement.setTimestamp(6, Timestamp.from(now))
                statement.setTimestamp(7, Timestamp.from(now))
                statement.setTimestamp(8, Timestamp.from(now.plus(Duration.ofHours(expiresInHours))))
                statement.executeUpdate()
            }
        connection.insertParticipant(sessionId, ownerUserId, role = "owner", approval = "approved")
    }
}

private fun DataSource.insertActiveTogetherSession(
    ownerUserId: UUID,
    partnerUserId: UUID,
    intent: String,
) {
    val sessionId = UUID.randomUUID()
    val now = Instant.now()
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO tonight_sessions (
                    id, created_by_user_id, mode, intent, host_mode, map_visible, status,
                    created_at, started_at, expires_at
                )
                VALUES (?, ?, 'together', ?, 'public_meet_first', true, 'active', ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, sessionId)
                statement.setObject(2, ownerUserId)
                statement.setString(3, intent)
                statement.setTimestamp(4, Timestamp.from(now))
                statement.setTimestamp(5, Timestamp.from(now))
                statement.setTimestamp(6, Timestamp.from(now.plus(Duration.ofHours(2))))
                statement.executeUpdate()
            }
        connection.insertParticipant(sessionId, ownerUserId, role = "owner", approval = "approved")
        connection.insertParticipant(sessionId, partnerUserId, role = "partner", approval = "approved")
    }
}

private fun java.sql.Connection.insertParticipant(
    sessionId: UUID,
    userId: UUID,
    role: String,
    approval: String,
) {
    prepareStatement(
        """
        INSERT INTO tonight_session_participants (session_id, user_id, role, approval_status, approved_at)
        VALUES (?, ?, ?, ?, now())
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, sessionId)
        statement.setObject(2, userId)
        statement.setString(3, role)
        statement.setString(4, approval)
        statement.executeUpdate()
    }
}

private fun DataSource.setPausedAt(
    ownerUserId: UUID,
    pausedAt: Instant?,
) {
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                UPDATE tonight_sessions
                SET paused_at = ?
                WHERE created_by_user_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setTimestamp(1, pausedAt?.let(Timestamp::from))
                statement.setObject(2, ownerUserId)
                statement.executeUpdate()
            }
    }
}

private fun DataSource.insertBlock(
    blockerUserId: UUID,
    blockedUserId: UUID,
) {
    connection.use { connection ->
        connection
            .prepareStatement(
                "INSERT INTO user_blocks (id, blocker_user_id, blocked_user_id) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, blockerUserId)
                statement.setObject(3, blockedUserId)
                statement.executeUpdate()
            }
    }
}

private fun DataSource.insertHidden(
    hiderUserId: UUID,
    hiddenUserId: UUID,
) {
    connection.use { connection ->
        connection
            .prepareStatement(
                "INSERT INTO user_hidden_profiles (id, hider_user_id, hidden_user_id) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, hiderUserId)
                statement.setObject(3, hiddenUserId)
                statement.executeUpdate()
            }
    }
}
