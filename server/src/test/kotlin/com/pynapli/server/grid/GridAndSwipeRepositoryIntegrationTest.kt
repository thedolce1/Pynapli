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
