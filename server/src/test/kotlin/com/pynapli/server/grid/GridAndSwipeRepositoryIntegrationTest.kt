package com.pynapli.server.grid

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.swipe.JdbcSwipeRepository
import com.pynapli.server.swipe.canonicalPair
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GridAndSwipeRepositoryIntegrationTest : StringSpec({
    val postgisImage = DockerImageName
        .parse("postgis/postgis:16-3.4")
        .asCompatibleSubstituteFor("postgres")
    val postgres = PostgreSQLContainer<Nothing>(postgisImage).apply {
        withDatabaseName("pynapli_grid_swipe_test")
        withUsername("pynapli")
        withPassword("pynapli_test_password")
    }
    lateinit var dataSource: HikariDataSource

    beforeSpec {
        postgres.start()
    }

    afterSpec {
        postgres.stop()
    }

    beforeTest {
        dataSource = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 2
                poolName = "pynapli-grid-swipe-test"
            }
        )

        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .cleanDisabled(false)
            .load()
            .also { flyway ->
                flyway.clean()
                flyway.migrate()
            }
    }

    afterTest {
        dataSource.close()
    }

    "grid and swipe queries project linked partner display name" {
        runBlocking {
            val viewer = UUID.fromString("00000000-0000-0000-0000-000000000901")
            val target = UUID.fromString("00000000-0000-0000-0000-000000000902")
            val partner = UUID.fromString("00000000-0000-0000-0000-000000000903")
            dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
            dataSource.insertDiscoverableUser(target, "Maya", age = 35, longitude = -122.0836, latitude = 37.4226)
            dataSource.insertDiscoverableUser(partner, "Alex", age = 39, longitude = -122.0851, latitude = 37.4235)
            dataSource.insertActiveProfileLink(userId = target, linkedUserId = partner)

            val gridCards = JdbcGridRepository(dataSource).queryNearby(
                viewerUserId = viewer,
                radiusMeters = 100_000,
                limit = 10
            ).cards
            val swipeCards = JdbcSwipeRepository(dataSource).queryDeck(
                viewerUserId = viewer,
                radiusMeters = 100_000,
                limit = 10
            ).cards

            val gridTarget = gridCards.single { it.userId == target }
            val swipeTarget = swipeCards.single { it.userId == target }
            assertEquals(partner, gridTarget.linkedPartnerUserId)
            assertEquals("Alex", gridTarget.linkedPartnerDisplayName)
            assertEquals(partner, swipeTarget.linkedPartnerUserId)
            assertEquals("Alex", swipeTarget.linkedPartnerDisplayName)
        }
    }

    "grid and swipe discovery exclude quarantined profiles" {
        runBlocking {
            val viewer = UUID.fromString("00000000-0000-0000-0000-000000000911")
            val visibleTarget = UUID.fromString("00000000-0000-0000-0000-000000000912")
            val quarantinedTarget = UUID.fromString("00000000-0000-0000-0000-000000000913")
            dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
            dataSource.insertDiscoverableUser(visibleTarget, "Maya", age = 35, longitude = -122.0836, latitude = 37.4226)
            dataSource.insertDiscoverableUser(quarantinedTarget, "Spam", age = 39, longitude = -122.0838, latitude = 37.4228)
            dataSource.setRiskState(quarantinedTarget, score = 75, tier = "quarantine")

            val gridCards = JdbcGridRepository(dataSource).queryNearby(
                viewerUserId = viewer,
                radiusMeters = 100_000,
                limit = 10
            ).cards
            val swipeCards = JdbcSwipeRepository(dataSource).queryDeck(
                viewerUserId = viewer,
                radiusMeters = 100_000,
                limit = 10
            ).cards

            assertEquals(listOf(visibleTarget), gridCards.map { it.userId })
            assertEquals(listOf(visibleTarget), swipeCards.map { it.userId })
        }
    }

    "swipe matches exclude quarantined matched profiles" {
        runBlocking {
            val viewer = UUID.fromString("00000000-0000-0000-0000-000000000921")
            val visibleMatch = UUID.fromString("00000000-0000-0000-0000-000000000922")
            val quarantinedMatch = UUID.fromString("00000000-0000-0000-0000-000000000923")
            dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
            dataSource.insertDiscoverableUser(visibleMatch, "Maya", age = 35, longitude = -122.0836, latitude = 37.4226)
            dataSource.insertDiscoverableUser(quarantinedMatch, "Spam", age = 39, longitude = -122.0838, latitude = 37.4228)
            dataSource.insertMatch(viewer, visibleMatch)
            dataSource.insertMatch(viewer, quarantinedMatch)
            dataSource.setRiskState(quarantinedMatch, score = 75, tier = "quarantine")

            val matches = JdbcSwipeRepository(dataSource).listMatches(viewer, limit = 10)

            assertEquals(listOf(visibleMatch), matches.map { it.matchedUserId })
        }
    }

    "grid and swipe queries apply advanced discovery filters" {
        runBlocking {
            val viewer = UUID.fromString("00000000-0000-0000-0000-000000000931")
            val linkedTarget = UUID.fromString("00000000-0000-0000-0000-000000000932")
            val singleTarget = UUID.fromString("00000000-0000-0000-0000-000000000933")
            val partner = UUID.fromString("00000000-0000-0000-0000-000000000934")
            dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
            dataSource.insertDiscoverableUser(
                linkedTarget,
                "Maya",
                age = 36,
                longitude = -122.0836,
                latitude = 37.4226,
                relationshipContext = "linked"
            )
            dataSource.insertDiscoverableUser(
                singleTarget,
                "Alex",
                age = 31,
                longitude = -122.0838,
                latitude = 37.4228,
                relationshipContext = "single"
            )
            dataSource.insertDiscoverableUser(partner, "Noa", age = 35, longitude = -122.0851, latitude = 37.4235)
            dataSource.insertActiveProfileLink(userId = linkedTarget, linkedUserId = partner)
            val filters = DiscoveryFilters(
                minimumAge = 35,
                relationshipContexts = setOf("linked"),
                linkedOnly = true
            )

            val gridCards = JdbcGridRepository(dataSource).queryNearby(
                viewerUserId = viewer,
                radiusMeters = 100_000,
                limit = 10,
                filters = filters
            ).cards
            val swipeCards = JdbcSwipeRepository(dataSource).queryDeck(
                viewerUserId = viewer,
                radiusMeters = 100_000,
                limit = 10,
                filters = filters
            ).cards

            assertEquals(listOf(linkedTarget), gridCards.map { it.userId })
            assertEquals(listOf(linkedTarget), swipeCards.map { it.userId })
        }
    }

    "active boosts rank ahead of distance after discovery filters" {
        runBlocking {
            val viewer = UUID.fromString("00000000-0000-0000-0000-000000000941")
            val nearTarget = UUID.fromString("00000000-0000-0000-0000-000000000942")
            val boostedTarget = UUID.fromString("00000000-0000-0000-0000-000000000943")
            dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
            dataSource.insertDiscoverableUser(nearTarget, "Near", age = 35, longitude = -122.0836, latitude = 37.4226)
            dataSource.insertDiscoverableUser(boostedTarget, "Boosted", age = 36, longitude = -122.0900, latitude = 37.4280)
            dataSource.insertBoostActivation(boostedTarget)

            val gridCards = JdbcGridRepository(dataSource).queryNearby(
                viewerUserId = viewer,
                radiusMeters = 100_000,
                limit = 10
            ).cards
            val swipeCards = JdbcSwipeRepository(dataSource).queryDeck(
                viewerUserId = viewer,
                radiusMeters = 100_000,
                limit = 10
            ).cards

            assertEquals(listOf(boostedTarget, nearTarget), gridCards.map { it.userId })
            assertEquals(listOf(boostedTarget, nearTarget), swipeCards.map { it.userId })
        }
    }

    "grid cursor pagination returns stable pages without overlap" {
        runBlocking {
            val viewer = UUID.fromString("00000000-0000-0000-0000-000000000951")
            val firstTarget = UUID.fromString("00000000-0000-0000-0000-000000000952")
            val secondTarget = UUID.fromString("00000000-0000-0000-0000-000000000953")
            val thirdTarget = UUID.fromString("00000000-0000-0000-0000-000000000954")
            dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
            dataSource.insertDiscoverableUser(firstTarget, "First", age = 35, longitude = -122.0836, latitude = 37.4226)
            dataSource.insertDiscoverableUser(secondTarget, "Second", age = 36, longitude = -122.0838, latitude = 37.4228)
            dataSource.insertDiscoverableUser(thirdTarget, "Third", age = 37, longitude = -122.0840, latitude = 37.4230)

            val repository = JdbcGridRepository(dataSource)
            val firstPage = repository.queryNearby(
                viewerUserId = viewer,
                radiusMeters = 100_000,
                limit = 2
            )
            val secondPage = repository.queryNearby(
                viewerUserId = viewer,
                radiusMeters = 100_000,
                limit = 2,
                cursor = firstPage.nextCursorAnchor
            )

            assertEquals(listOf(firstTarget, secondTarget), firstPage.cards.map { it.userId })
            assertEquals(GridCursorAnchor(1, firstPage.cards.last().distanceBucketMeters, secondTarget), firstPage.nextCursorAnchor)
            assertEquals(listOf(thirdTarget), secondPage.cards.map { it.userId })
            assertEquals(null, secondPage.nextCursorAnchor)
        }
    }

    "grid excludes blocked users before applying page limit" {
        runBlocking {
            val viewer = UUID.fromString("00000000-0000-0000-0000-000000000961")
            val blockedTarget = UUID.fromString("00000000-0000-0000-0000-000000000962")
            val visibleTarget = UUID.fromString("00000000-0000-0000-0000-000000000963")
            dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
            dataSource.insertDiscoverableUser(blockedTarget, "Blocked", age = 35, longitude = -122.0836, latitude = 37.4226)
            dataSource.insertDiscoverableUser(visibleTarget, "Visible", age = 36, longitude = -122.0840, latitude = 37.4230)
            dataSource.insertBlock(viewer, blockedTarget)

            val cards = JdbcGridRepository(dataSource).queryNearby(
                viewerUserId = viewer,
                radiusMeters = 100_000,
                limit = 1
            ).cards

            assertEquals(listOf(visibleTarget), cards.map { it.userId })
        }
    }

    "grid query handles 5000 nearby candidates with stable cursor pages" {
        runBlocking {
            val viewer = UUID.fromString("00000000-0000-0000-0000-000000000971")
            dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
            dataSource.insertDenseDiscoverableUsers(
                count = 5_000,
                baseLongitude = -122.0841,
                baseLatitude = 37.4220
            )

            val repository = JdbcGridRepository(dataSource)
            val startedAt = System.nanoTime()
            val firstPage = repository.queryNearby(
                viewerUserId = viewer,
                radiusMeters = 100_000,
                limit = 120
            )
            val secondPage = repository.queryNearby(
                viewerUserId = viewer,
                radiusMeters = 100_000,
                limit = 120,
                cursor = assertNotNull(firstPage.nextCursorAnchor)
            )
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
            println("Grid dense 5000 candidate two-page query completed in ${elapsedMillis}ms")

            assertEquals(120, firstPage.cards.size)
            assertEquals(120, secondPage.cards.size)
            assertTrue(firstPage.cards.map { it.userId }.intersect(secondPage.cards.map { it.userId }.toSet()).isEmpty())
            assertTrue(
                elapsedMillis < 10_000,
                "Dense grid query should remain comfortably under 10s in local Testcontainers; actual=${elapsedMillis}ms"
            )
        }
    }

    "grid query with verifiedOnly excludes unverified profiles" {
        runBlocking {
            val viewer = UUID.fromString("00000000-0000-0000-0000-000000000a01")
            val verifiedTarget = UUID.fromString("00000000-0000-0000-0000-000000000a02")
            val unverifiedTarget = UUID.fromString("00000000-0000-0000-0000-000000000a03")
            dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
            dataSource.insertDiscoverableUser(verifiedTarget, "Vera", age = 35, longitude = -122.0836, latitude = 37.4226)
            dataSource.insertDiscoverableUser(unverifiedTarget, "Una", age = 33, longitude = -122.0838, latitude = 37.4228)
            dataSource.markProfileVerified(verifiedTarget)

            val repository = JdbcGridRepository(dataSource)
            val unfiltered = repository.queryNearby(viewer, radiusMeters = 100_000, limit = 10).cards
            val verifiedOnly = repository.queryNearby(
                viewerUserId = viewer,
                radiusMeters = 100_000,
                limit = 10,
                filters = DiscoveryFilters(verifiedOnly = true)
            ).cards

            assertEquals(setOf(verifiedTarget, unverifiedTarget), unfiltered.map { it.userId }.toSet())
            assertEquals(listOf(verifiedTarget), verifiedOnly.map { it.userId })
        }
    }

    "grid query with logisticsTags filters to overlapping tag and excludes untagged profiles" {
        runBlocking {
            val viewer = UUID.fromString("00000000-0000-0000-0000-000000000e01")
            val hosts = UUID.fromString("00000000-0000-0000-0000-000000000e02")
            val travels = UUID.fromString("00000000-0000-0000-0000-000000000e03")
            val untagged = UUID.fromString("00000000-0000-0000-0000-000000000e04")
            dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
            dataSource.insertDiscoverableUser(hosts, "Hosts", age = 30, longitude = -122.0836, latitude = 37.4226)
            dataSource.insertDiscoverableUser(travels, "Travels", age = 31, longitude = -122.0838, latitude = 37.4228)
            dataSource.insertDiscoverableUser(untagged, "Plain", age = 32, longitude = -122.0830, latitude = 37.4230)
            dataSource.setProfileLogisticsTags(hosts, listOf("hosts", "hotel_friendly"))
            dataSource.setProfileLogisticsTags(travels, listOf("travels"))

            val repository = JdbcGridRepository(dataSource)
            val hostsOnly = repository.queryNearby(
                viewerUserId = viewer,
                radiusMeters = 100_000,
                limit = 10,
                filters = DiscoveryFilters(logisticsTags = setOf("hosts"))
            ).cards
            val publicOnly = repository.queryNearby(
                viewerUserId = viewer,
                radiusMeters = 100_000,
                limit = 10,
                filters = DiscoveryFilters(logisticsTags = setOf("public_meet_first"))
            ).cards

            assertEquals(listOf(hosts), hostsOnly.map { it.userId })
            assertTrue(publicOnly.isEmpty(), "Untagged profile should not match a public_meet_first filter")
        }
    }

    "grid query with lifestyleTags filters to overlapping tag and excludes untagged profiles" {
        runBlocking {
            val viewer = UUID.fromString("00000000-0000-0000-0000-000000000d01")
            val softSwap = UUID.fromString("00000000-0000-0000-0000-000000000d02")
            val fullSwap = UUID.fromString("00000000-0000-0000-0000-000000000d03")
            val untagged = UUID.fromString("00000000-0000-0000-0000-000000000d04")
            dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
            dataSource.insertDiscoverableUser(softSwap, "Soft", age = 30, longitude = -122.0836, latitude = 37.4226)
            dataSource.insertDiscoverableUser(fullSwap, "Full", age = 31, longitude = -122.0838, latitude = 37.4228)
            dataSource.insertDiscoverableUser(untagged, "Plain", age = 32, longitude = -122.0830, latitude = 37.4230)
            dataSource.setProfileLifestyleTags(softSwap, listOf("soft_swap", "experienced"))
            dataSource.setProfileLifestyleTags(fullSwap, listOf("full_swap"))

            val repository = JdbcGridRepository(dataSource)
            val softOnly = repository.queryNearby(
                viewerUserId = viewer,
                radiusMeters = 100_000,
                limit = 10,
                filters = DiscoveryFilters(lifestyleTags = setOf("soft_swap"))
            ).cards
            val softOrFull = repository.queryNearby(
                viewerUserId = viewer,
                radiusMeters = 100_000,
                limit = 10,
                filters = DiscoveryFilters(lifestyleTags = setOf("soft_swap", "full_swap"))
            ).cards
            val anyKink = repository.queryNearby(
                viewerUserId = viewer,
                radiusMeters = 100_000,
                limit = 10,
                filters = DiscoveryFilters(lifestyleTags = setOf("kink_friendly"))
            ).cards

            assertEquals(listOf(softSwap), softOnly.map { it.userId })
            assertEquals(setOf(softSwap, fullSwap), softOrFull.map { it.userId }.toSet())
            assertTrue(anyKink.isEmpty(), "Untagged profile should not match a kink_friendly filter")
        }
    }

    "grid query with discoveryStyles filters to matching style" {
        runBlocking {
            val viewer = UUID.fromString("00000000-0000-0000-0000-000000000c01")
            val solo = UUID.fromString("00000000-0000-0000-0000-000000000c02")
            val together = UUID.fromString("00000000-0000-0000-0000-000000000c03")
            val social = UUID.fromString("00000000-0000-0000-0000-000000000c04")
            dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
            dataSource.insertDiscoverableUser(solo, "Solo", age = 30, longitude = -122.0836, latitude = 37.4226)
            dataSource.insertDiscoverableUser(together, "Together", age = 31, longitude = -122.0838, latitude = 37.4228)
            dataSource.insertDiscoverableUser(social, "Social", age = 32, longitude = -122.0830, latitude = 37.4230)
            dataSource.setProfileDiscoveryStyle(solo, "solo")
            dataSource.setProfileDiscoveryStyle(together, "together")
            dataSource.setProfileDiscoveryStyle(social, "social")

            val repository = JdbcGridRepository(dataSource)
            val soloOnly = repository.queryNearby(
                viewerUserId = viewer,
                radiusMeters = 100_000,
                limit = 10,
                filters = DiscoveryFilters(discoveryStyles = setOf("solo"))
            ).cards
            val soloOrTogether = repository.queryNearby(
                viewerUserId = viewer,
                radiusMeters = 100_000,
                limit = 10,
                filters = DiscoveryFilters(discoveryStyles = setOf("solo", "together"))
            ).cards

            assertEquals(listOf(solo), soloOnly.map { it.userId })
            assertEquals(setOf(solo, together), soloOrTogether.map { it.userId }.toSet())
        }
    }

    "grid query with facePhotoVerifiedOnly and ageVerifiedOnly each filter to their column" {
        runBlocking {
            val viewer = UUID.fromString("00000000-0000-0000-0000-000000000b01")
            val faceOnly = UUID.fromString("00000000-0000-0000-0000-000000000b02")
            val ageOnly = UUID.fromString("00000000-0000-0000-0000-000000000b03")
            val unverified = UUID.fromString("00000000-0000-0000-0000-000000000b04")
            dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
            dataSource.insertDiscoverableUser(faceOnly, "Face", age = 30, longitude = -122.0836, latitude = 37.4226)
            dataSource.insertDiscoverableUser(ageOnly, "Age", age = 31, longitude = -122.0838, latitude = 37.4228)
            dataSource.insertDiscoverableUser(unverified, "Plain", age = 32, longitude = -122.0830, latitude = 37.4230)
            dataSource.markFacePhotoVerified(faceOnly)
            dataSource.markAgeVerified(ageOnly)

            val repository = JdbcGridRepository(dataSource)
            val faceFiltered = repository.queryNearby(
                viewerUserId = viewer,
                radiusMeters = 100_000,
                limit = 10,
                filters = DiscoveryFilters(facePhotoVerifiedOnly = true)
            ).cards
            val ageFiltered = repository.queryNearby(
                viewerUserId = viewer,
                radiusMeters = 100_000,
                limit = 10,
                filters = DiscoveryFilters(ageVerifiedOnly = true)
            ).cards

            assertEquals(listOf(faceOnly), faceFiltered.map { it.userId })
            assertEquals(listOf(ageOnly), ageFiltered.map { it.userId })
        }
    }
})

private fun DataSource.insertDiscoverableUser(
    userId: UUID,
    displayName: String,
    age: Int,
    longitude: Double,
    latitude: Double,
    relationshipContext: String = "single"
) {
    connection.use { connection ->
        connection.prepareStatement(
            """
            INSERT INTO users (id, status)
            VALUES (?, 'active')
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, userId)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO profiles (
                user_id,
                display_name,
                display_age,
                relationship_context,
                moderation_status,
                visibility_status
            )
            VALUES (?, ?, ?, ?, 'approved', 'visible')
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, displayName)
            statement.setInt(3, age)
            statement.setString(4, relationshipContext)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO discovery_settings (
                user_id,
                grid_visible,
                swipe_visible,
                incognito,
                show_distance
            )
            VALUES (?, true, true, false, true)
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, userId)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO user_locations (
                user_id,
                geom,
                location_source,
                accuracy_meters
            )
            VALUES (?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, 'gps', 12)
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setDouble(2, longitude)
            statement.setDouble(3, latitude)
            statement.executeUpdate()
        }
    }
}

private fun DataSource.insertActiveProfileLink(userId: UUID, linkedUserId: UUID) {
    connection.use { connection ->
        connection.prepareStatement(
            """
            INSERT INTO user_profile_links (
                id,
                user_id,
                linked_user_id,
                status,
                accepted_at
            )
            VALUES (?, ?, ?, 'active', now())
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, userId)
            statement.setObject(3, linkedUserId)
            statement.executeUpdate()
        }
    }
}

private fun DataSource.markProfileVerified(userId: UUID) =
    setProfileTimestamp(userId, column = "verified_at")

private fun DataSource.markFacePhotoVerified(userId: UUID) =
    setProfileTimestamp(userId, column = "face_photo_verified_at")

private fun DataSource.markAgeVerified(userId: UUID) =
    setProfileTimestamp(userId, column = "age_verified_at")

private fun DataSource.setProfileDiscoveryStyle(userId: UUID, style: String) {
    require(style in setOf("solo", "together", "both", "social", "ask_in_chat")) {
        "Refusing to UPDATE profiles.discovery_style to invalid value from a test helper: $style"
    }
    connection.use { connection ->
        connection.prepareStatement("UPDATE profiles SET discovery_style = ? WHERE user_id = ?").use { statement ->
            statement.setString(1, style)
            statement.setObject(2, userId)
            statement.executeUpdate()
        }
    }
}

private fun DataSource.setProfileLifestyleTags(userId: UUID, tags: List<String>) =
    setProfileTagArray(userId, column = "lifestyle_tags", tags = tags)

private fun DataSource.setProfileLogisticsTags(userId: UUID, tags: List<String>) =
    setProfileTagArray(userId, column = "logistics_tags", tags = tags)

private fun DataSource.setProfileTagArray(userId: UUID, column: String, tags: List<String>) {
    require(column in setOf("lifestyle_tags", "logistics_tags")) {
        "Refusing to UPDATE arbitrary profile tag column from a test helper: $column"
    }
    connection.use { connection ->
        val array = connection.createArrayOf("text", tags.toTypedArray())
        connection.prepareStatement("UPDATE profiles SET $column = ? WHERE user_id = ?").use { statement ->
            statement.setArray(1, array)
            statement.setObject(2, userId)
            statement.executeUpdate()
        }
    }
}

private fun DataSource.setProfileTimestamp(userId: UUID, column: String) {
    require(column in setOf("verified_at", "face_photo_verified_at", "age_verified_at")) {
        "Refusing to UPDATE arbitrary profile column from a test helper: $column"
    }
    connection.use { connection ->
        connection.prepareStatement("UPDATE profiles SET $column = now() WHERE user_id = ?").use { statement ->
            statement.setObject(1, userId)
            statement.executeUpdate()
        }
    }
}

private fun DataSource.insertMatch(userA: UUID, userB: UUID) {
    val (a, b) = canonicalPair(userA, userB)
    connection.use { connection ->
        connection.prepareStatement(
            """
            INSERT INTO matches (id, user_a, user_b)
            VALUES (?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, a)
            statement.setObject(3, b)
            statement.executeUpdate()
        }
    }
}

private fun DataSource.setRiskState(userId: UUID, score: Int, tier: String) {
    connection.use { connection ->
        connection.prepareStatement(
            """
            INSERT INTO account_risk_states (
                user_id,
                score,
                tier,
                last_event_at,
                updated_at
            )
            VALUES (?, ?, ?, now(), now())
            ON CONFLICT (user_id) DO UPDATE
            SET score = EXCLUDED.score,
                tier = EXCLUDED.tier,
                last_event_at = EXCLUDED.last_event_at,
                updated_at = EXCLUDED.updated_at
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setInt(2, score)
            statement.setString(3, tier)
            statement.executeUpdate()
        }
    }
}

private fun DataSource.insertBoostActivation(userId: UUID) {
    connection.use { connection ->
        connection.prepareStatement(
            """
            INSERT INTO boost_activations (
                id,
                user_id,
                boost_type,
                source,
                starts_at,
                ends_at,
                status
            )
            VALUES (?, ?, 'boost', 'inventory', now() - interval '1 minute', now() + interval '29 minutes', 'active')
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, userId)
            statement.executeUpdate()
        }
    }
}

private fun DataSource.insertDenseDiscoverableUsers(
    count: Int,
    baseLongitude: Double,
    baseLatitude: Double
) {
    val users = List(count) { index ->
        DenseDiscoverableUser(
            id = UUID.nameUUIDFromBytes("dense-grid-user-$index".toByteArray()),
            displayName = "Dense ${index + 1}",
            age = 30 + (index % 25),
            longitude = baseLongitude + ((index % 100) - 50) * 0.00008,
            latitude = baseLatitude + ((index / 100) - 25) * 0.00008,
            relationshipContext = if (index % 3 == 0) "linked" else "single"
        )
    }

    connection.use { connection ->
        connection.prepareStatement(
            """
            INSERT INTO users (id, status)
            VALUES (?, 'active')
            """.trimIndent()
        ).use { statement ->
            users.forEach { user ->
                statement.setObject(1, user.id)
                statement.addBatch()
            }
            statement.executeBatch()
        }
        connection.prepareStatement(
            """
            INSERT INTO profiles (
                user_id,
                display_name,
                display_age,
                relationship_context,
                moderation_status,
                visibility_status
            )
            VALUES (?, ?, ?, ?, 'approved', 'visible')
            """.trimIndent()
        ).use { statement ->
            users.forEach { user ->
                statement.setObject(1, user.id)
                statement.setString(2, user.displayName)
                statement.setInt(3, user.age)
                statement.setString(4, user.relationshipContext)
                statement.addBatch()
            }
            statement.executeBatch()
        }
        connection.prepareStatement(
            """
            INSERT INTO discovery_settings (
                user_id,
                grid_visible,
                swipe_visible,
                incognito,
                show_distance
            )
            VALUES (?, true, true, false, true)
            """.trimIndent()
        ).use { statement ->
            users.forEach { user ->
                statement.setObject(1, user.id)
                statement.addBatch()
            }
            statement.executeBatch()
        }
        connection.prepareStatement(
            """
            INSERT INTO user_locations (
                user_id,
                geom,
                location_source,
                accuracy_meters
            )
            VALUES (?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, 'gps', 12)
            """.trimIndent()
        ).use { statement ->
            users.forEach { user ->
                statement.setObject(1, user.id)
                statement.setDouble(2, user.longitude)
                statement.setDouble(3, user.latitude)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }
}

private data class DenseDiscoverableUser(
    val id: UUID,
    val displayName: String,
    val age: Int,
    val longitude: Double,
    val latitude: Double,
    val relationshipContext: String
)

private fun DataSource.insertBlock(blockerUserId: UUID, blockedUserId: UUID) {
    connection.use { connection ->
        connection.prepareStatement(
            """
            INSERT INTO user_blocks (
                id,
                blocker_user_id,
                blocked_user_id
            )
            VALUES (?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, blockerUserId)
            statement.setObject(3, blockedUserId)
            statement.executeUpdate()
        }
    }
}
