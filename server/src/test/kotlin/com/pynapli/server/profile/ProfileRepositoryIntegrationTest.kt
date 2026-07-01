package com.pynapli.server.profile

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProfileRepositoryIntegrationTest {
    private lateinit var dataSource: HikariDataSource

    @Before
    fun setUp() {
        dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = postgres.jdbcUrl
                    username = postgres.username
                    password = postgres.password
                    maximumPoolSize = 2
                    poolName = "pynapli-profile-test"
                },
            )

        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .cleanDisabled(false)
            .load()
            .also { flyway ->
                flyway.clean()
                flyway.migrate()
            }
    }

    @After
    fun tearDown() {
        dataSource.close()
    }

    @Test
    fun updateDiscoverySettingsPersistsVisibilityAndDistanceToggles() =
        runBlocking {
            val userId = UUID.fromString("00000000-0000-0000-0000-000000000701")
            dataSource.insertUserWithProfile(userId, "Seb Dev")
            val repository = JdbcProfileRepository(dataSource)

            val updated =
                repository.updateDiscoverySettings(
                    userId = userId,
                    update =
                        DiscoverySettingsUpdate(
                            gridVisible = false,
                            swipeVisible = true,
                            showDistance = false,
                            showInViewedLists = false,
                            allowGridMessages = true,
                            gridMessagePolicy = "matches_only",
                            allowFlirts = false,
                            allowIntroNotes = false,
                            allowAlbumRequests = false,
                            visibleWhileTraveling = null,
                            sharePsyncSoloMatches = null,
                        ),
                )

            val settings = requireNotNull(updated).discoverySettings
            assertEquals(false, settings.gridVisible)
            assertEquals(true, settings.swipeVisible)
            assertEquals(false, settings.showDistance)
            assertEquals(false, settings.showInViewedLists)
            assertEquals(true, settings.allowGridMessages)
            assertEquals("matches_only", settings.gridMessagePolicy)
            assertEquals(false, settings.allowFlirts)
            assertEquals(false, settings.allowIntroNotes)
            assertEquals(false, settings.allowAlbumRequests)
        }

    @Test
    fun publicProfileHidesQuarantinedTarget() =
        runBlocking {
            val viewer = UUID.fromString("00000000-0000-0000-0000-000000000711")
            val target = UUID.fromString("00000000-0000-0000-0000-000000000712")
            dataSource.insertUserWithProfile(viewer, "Viewer")
            dataSource.insertUserWithProfile(target, "Spam")
            dataSource.setRiskState(target, score = 75, tier = "quarantine")
            val repository = JdbcProfileRepository(dataSource)

            val profile = repository.getPublicProfile(viewer, target)

            assertNull(profile)
        }

    @Test
    fun publicProfileAllowsSelfPreviewEvenWhenGridHidden() =
        runBlocking {
            val user = UUID.fromString("00000000-0000-0000-0000-000000000741")
            val other = UUID.fromString("00000000-0000-0000-0000-000000000742")
            dataSource.insertUserWithProfile(user, "Seb Dev")
            dataSource.insertUserWithProfile(other, "Viewer")
            // Make the user swipe-only (hidden from grid); the discovery query normally excludes them.
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement("UPDATE discovery_settings SET grid_visible = false WHERE user_id = ?")
                    .use { statement ->
                        statement.setObject(1, user)
                        statement.executeUpdate()
                    }
            }
            val repository = JdbcProfileRepository(dataSource)

            // Self-preview ("Preview profile") returns the profile despite grid_visible = false...
            assertEquals(user, requireNotNull(repository.getPublicProfile(user, user)).userId)
            // ...but another viewer still cannot see the grid-hidden profile.
            assertNull(repository.getPublicProfile(other, user))
        }

    @Test
    fun publicProfileReportsVerifiedFromVerifiedAt() =
        runBlocking {
            val viewer = UUID.fromString("00000000-0000-0000-0000-000000000751")
            val target = UUID.fromString("00000000-0000-0000-0000-000000000752")
            dataSource.insertUserWithProfile(viewer, "Viewer")
            dataSource.insertUserWithProfile(target, "Vera")
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement("UPDATE profiles SET verified_at = now() WHERE user_id = ?")
                    .use { statement ->
                        statement.setObject(1, target)
                        statement.executeUpdate()
                    }
            }
            val repository = JdbcProfileRepository(dataSource)

            assertEquals(false, requireNotNull(repository.getPublicProfile(viewer, viewer)).verified)
            assertEquals(true, requireNotNull(repository.getPublicProfile(viewer, target)).verified)
        }

    @Test
    fun publicProfileHidesQuarantinedLinkedPartnerMini() =
        runBlocking {
            val viewer = UUID.fromString("00000000-0000-0000-0000-000000000721")
            val target = UUID.fromString("00000000-0000-0000-0000-000000000722")
            val partner = UUID.fromString("00000000-0000-0000-0000-000000000723")
            dataSource.insertUserWithProfile(viewer, "Viewer")
            dataSource.insertUserWithProfile(target, "Maya")
            dataSource.insertUserWithProfile(partner, "Spam")
            dataSource.insertActiveProfileLink(userId = target, linkedUserId = partner)
            dataSource.setRiskState(partner, score = 75, tier = "quarantine")
            val repository = JdbcProfileRepository(dataSource)

            val profile = repository.getPublicProfile(viewer, target)

            assertEquals(target, requireNotNull(profile).userId)
            assertNull(profile.linkedPartnerUserId)
        }

    @Test
    fun updateProfilePersistsBioTagsAndSelfDescribeAndMatchesTheFilterPredicate() =
        runBlocking {
            val userId = UUID.fromString("00000000-0000-0000-0000-000000000731")
            dataSource.insertUserWithProfile(userId, "Seb Dev")
            val repository = JdbcProfileRepository(dataSource)

            val updated =
                repository.updateProfile(
                    userId = userId,
                    update =
                        ProfileUpdate(
                            displayName = "Seb Dev",
                            birthdate = null,
                            gender = "self_describe",
                            genderDetail = "Genderfluid",
                            orientation = "queer",
                            relationshipContext = null,
                            discoveryStyle = null,
                            displayAge = null,
                            profileCompletenessScore = 40,
                            bioProvided = true,
                            bio = "Hosting-friendly and social-first.",
                            neighborhoodProvided = true,
                            neighborhood = "Mission",
                            lifestyleTags = listOf("enm", "soft_swap"),
                            logisticsTags = listOf("hosts"),
                            intents = listOf("events"),
                        ),
                )

            val profile = requireNotNull(updated).profile
            assertEquals("Hosting-friendly and social-first.", profile.bio)
            assertEquals("Mission", profile.neighborhood)
            assertEquals("Mission", requireNotNull(repository.getPublicProfile(userId, userId)).neighborhood)
            assertEquals("self_describe", profile.gender)
            assertEquals("Genderfluid", profile.genderDetail)
            assertEquals("queer", profile.orientation)
            assertEquals(listOf("enm", "soft_swap"), profile.lifestyleTags)
            assertEquals(listOf("hosts"), profile.logisticsTags)
            assertEquals(listOf("events"), profile.intents)

            // The stored shape must satisfy the exact overlap predicate the grid/swipe
            // filter SQL uses — this is what makes lifestyle filters match real profiles.
            assertEquals(true, dataSource.lifestyleOverlapMatches(userId, listOf("enm")))
            assertEquals(false, dataSource.lifestyleOverlapMatches(userId, listOf("full_swap")))

            // Omitted late-added fields leave the stored values unchanged.
            val unchanged =
                repository.updateProfile(
                    userId = userId,
                    update =
                        ProfileUpdate(
                            displayName = "Seb Dev",
                            birthdate = null,
                            gender = null,
                            genderDetail = null,
                            orientation = null,
                            orientationDetail = null,
                            relationshipContext = null,
                            discoveryStyle = null,
                            displayAge = null,
                            profileCompletenessScore = 20,
                        ),
                )
            assertEquals("Hosting-friendly and social-first.", requireNotNull(unchanged).profile.bio)
            assertEquals("Mission", unchanged.profile.neighborhood)
            assertEquals(listOf("enm", "soft_swap"), unchanged.profile.lifestyleTags)

            // Provided-but-empty clears: bio to NULL, tags to NULL (the V36 opt-out
            // shape), dropping the profile out of tag-filtered discovery.
            val cleared =
                repository.updateProfile(
                    userId = userId,
                    update =
                        ProfileUpdate(
                            displayName = "Seb Dev",
                            birthdate = null,
                            gender = null,
                            genderDetail = null,
                            orientation = null,
                            orientationDetail = null,
                            relationshipContext = null,
                            discoveryStyle = null,
                            displayAge = null,
                            profileCompletenessScore = 20,
                            bioProvided = true,
                            bio = null,
                            neighborhoodProvided = true,
                            neighborhood = null,
                            lifestyleTags = emptyList(),
                        ),
                )
            assertNull(requireNotNull(cleared).profile.bio)
            assertNull(cleared.profile.neighborhood)
            assertEquals(emptyList(), cleared.profile.lifestyleTags)
            assertEquals(true, dataSource.lifestyleTagsColumnIsNull(userId))
            assertEquals(false, dataSource.lifestyleOverlapMatches(userId, listOf("enm")))
        }

    private fun DataSource.lifestyleOverlapMatches(
        userId: UUID,
        tags: List<String>,
    ): Boolean =
        connection.use { connection ->
            connection
                .prepareStatement("SELECT EXISTS (SELECT 1 FROM profiles WHERE user_id = ? AND lifestyle_tags && ?::text[])")
                .use { statement ->
                    statement.setObject(1, userId)
                    statement.setArray(2, connection.createArrayOf("text", tags.toTypedArray()))
                    statement.executeQuery().use { rs ->
                        rs.next() && rs.getBoolean(1)
                    }
                }
        }

    private fun DataSource.lifestyleTagsColumnIsNull(userId: UUID): Boolean =
        connection.use { connection ->
            connection
                .prepareStatement("SELECT lifestyle_tags IS NULL FROM profiles WHERE user_id = ?")
                .use { statement ->
                    statement.setObject(1, userId)
                    statement.executeQuery().use { rs ->
                        rs.next() && rs.getBoolean(1)
                    }
                }
        }

    @Test
    fun publicProfileGatesLinkedPartnerMiniByOwnerVisibility() =
        runBlocking {
            val viewer = UUID.fromString("00000000-0000-0000-0000-000000000741")
            val owner = UUID.fromString("00000000-0000-0000-0000-000000000742")
            val partner = UUID.fromString("00000000-0000-0000-0000-000000000743")
            dataSource.insertUserWithProfile(viewer, "Viewer")
            dataSource.insertUserWithProfile(owner, "Owner")
            dataSource.insertUserWithProfile(partner, "Partner")
            dataSource.insertActiveProfileLink(userId = owner, linkedUserId = partner)
            val repository = JdbcProfileRepository(dataSource)

            // Default 'public' projects the partner mini.
            assertEquals(partner, requireNotNull(repository.getPublicProfile(viewer, owner)).linkedPartnerUserId)

            // 'hidden' suppresses the mini while the profile itself stays visible.
            dataSource.setPartnerVisibility(owner, "hidden")
            requireNotNull(repository.getPublicProfile(viewer, owner)).let {
                assertEquals(owner, it.userId)
                assertNull(it.linkedPartnerUserId)
            }

            // 'matches_only' hides until a match exists between viewer and owner.
            dataSource.setPartnerVisibility(owner, "matches_only")
            assertNull(requireNotNull(repository.getPublicProfile(viewer, owner)).linkedPartnerUserId)
            dataSource.insertMatch(viewer, owner)
            assertEquals(partner, requireNotNull(repository.getPublicProfile(viewer, owner)).linkedPartnerUserId)

            // 'verified_only' ignores the match and requires the VIEWER to be verified.
            dataSource.setPartnerVisibility(owner, "verified_only")
            assertNull(requireNotNull(repository.getPublicProfile(viewer, owner)).linkedPartnerUserId)
            dataSource.setVerified(viewer)
            assertEquals(partner, requireNotNull(repository.getPublicProfile(viewer, owner)).linkedPartnerUserId)
        }

    @Test
    fun publicProfileDropsLinkedPartnerMiniAfterUnlink() =
        runBlocking {
            val viewer = UUID.fromString("00000000-0000-0000-0000-000000000751")
            val owner = UUID.fromString("00000000-0000-0000-0000-000000000752")
            val partner = UUID.fromString("00000000-0000-0000-0000-000000000753")
            dataSource.insertUserWithProfile(viewer, "Viewer")
            dataSource.insertUserWithProfile(owner, "Owner")
            dataSource.insertUserWithProfile(partner, "Partner")
            dataSource.insertActiveProfileLink(userId = owner, linkedUserId = partner)
            val repository = JdbcProfileRepository(dataSource)

            // While the link is active the partner mini projects (default 'public' visibility).
            assertEquals(partner, requireNotNull(repository.getPublicProfile(viewer, owner)).linkedPartnerUserId)

            // Unlinking must drop the active linked visibility from the public projection.
            dataSource.unlinkProfileLink(owner)
            assertNull(requireNotNull(repository.getPublicProfile(viewer, owner)).linkedPartnerUserId)
        }

    private fun DataSource.unlinkProfileLink(userId: UUID) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    "UPDATE user_profile_links SET status = 'unlinked', unlinked_at = now() " +
                        "WHERE user_id = ? AND status = 'active'",
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.executeUpdate()
                }
        }
    }

    private fun DataSource.setPartnerVisibility(
        userId: UUID,
        value: String,
    ) {
        connection.use { connection ->
            connection
                .prepareStatement("UPDATE discovery_settings SET partner_visibility = ? WHERE user_id = ?")
                .use { statement ->
                    statement.setString(1, value)
                    statement.setObject(2, userId)
                    statement.executeUpdate()
                }
        }
    }

    private fun DataSource.insertMatch(
        userIdA: UUID,
        userIdB: UUID,
    ) {
        // matches enforces canonical user_a < user_b ordering.
        val (low, high) = if (userIdA.toString() < userIdB.toString()) userIdA to userIdB else userIdB to userIdA
        connection.use { connection ->
            connection
                .prepareStatement("INSERT INTO matches (id, user_a, user_b) VALUES (?, ?, ?)")
                .use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, low)
                    statement.setObject(3, high)
                    statement.executeUpdate()
                }
        }
    }

    private fun DataSource.setVerified(userId: UUID) {
        connection.use { connection ->
            connection
                .prepareStatement("UPDATE profiles SET verified_at = now() WHERE user_id = ?")
                .use { statement ->
                    statement.setObject(1, userId)
                    statement.executeUpdate()
                }
        }
    }

    private fun DataSource.insertUserWithProfile(
        userId: UUID,
        displayName: String,
    ) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO users (id, status)
                    VALUES (?, 'active')
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.executeUpdate()
                }
            connection
                .prepareStatement(
                    """
                    INSERT INTO profiles (user_id, display_name, moderation_status, visibility_status)
                    VALUES (?, ?, 'approved', 'visible')
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setString(2, displayName)
                    statement.executeUpdate()
                }
            connection
                .prepareStatement(
                    """
                    INSERT INTO discovery_settings (user_id, grid_visible, swipe_visible, show_distance)
                    VALUES (?, true, false, true)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.executeUpdate()
                }
        }
    }

    private fun DataSource.insertActiveProfileLink(
        userId: UUID,
        linkedUserId: UUID,
    ) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO user_profile_links (
                        id,
                        user_id,
                        linked_user_id,
                        status,
                        accepted_at
                    )
                    VALUES (?, ?, ?, 'active', now())
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, userId)
                    statement.setObject(3, linkedUserId)
                    statement.executeUpdate()
                }
        }
    }

    private fun DataSource.setRiskState(
        userId: UUID,
        score: Int,
        tier: String,
    ) {
        connection.use { connection ->
            connection
                .prepareStatement(
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
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setInt(2, score)
                    statement.setString(3, tier)
                    statement.executeUpdate()
                }
        }
    }

    companion object {
        private val postgisImage =
            DockerImageName
                .parse("postgis/postgis:16-3.4")
                .asCompatibleSubstituteFor("postgres")

        private val postgres =
            PostgreSQLContainer<Nothing>(postgisImage).apply {
                withDatabaseName("pynapli_profile_test")
                withUsername("pynapli")
                withPassword("pynapli_test_password")
            }

        @JvmStatic
        @BeforeClass
        fun startPostgres() {
            postgres.start()
        }

        @JvmStatic
        @AfterClass
        fun stopPostgres() {
            postgres.stop()
        }
    }
}
