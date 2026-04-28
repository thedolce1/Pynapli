package com.pynapli.server.media

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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaRepositoryIntegrationTest {
    private lateinit var dataSource: HikariDataSource

    @Before
    fun setUp() {
        dataSource = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 2
                poolName = "pynapli-media-test"
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

    @After
    fun tearDown() {
        dataSource.close()
    }

    @Test
    fun migrationsCreateMediaFoundationTablesAndIndexes() {
        assertTrue(dataSource.tableExists("media_assets"))
        assertTrue(dataSource.tableExists("media_albums"))
        assertTrue(dataSource.tableExists("media_album_items"))
        assertTrue(dataSource.tableExists("media_album_access_requests"))
        assertTrue(dataSource.tableExists("media_album_access_grants"))
        assertTrue(dataSource.indexExists("idx_media_album_access_grants_active"))
    }

    @Test
    fun privateAlbumRequiresGrantAndCanBeRevoked() = runBlocking {
        val ownerId = UUID.fromString("00000000-0000-0000-0000-000000000101")
        val granteeId = UUID.fromString("00000000-0000-0000-0000-000000000102")
        val repository = JdbcMediaRepository(dataSource)
        dataSource.insertUser(ownerId)
        dataSource.insertUser(granteeId)

        val album = repository.ensureAlbum(
            ownerUserId = ownerId,
            kind = "private",
            title = "Private album",
            albumIdFactory = { UUID.fromString("10000000-0000-0000-0000-000000000001") }
        )
        val asset = repository.createAsset(
            ownerUserId = ownerId,
            create = MediaAssetCreate(
                mediaType = "image",
                storageProvider = "gcs",
                storageBucket = "pynapli-test-media",
                storageObjectKey = "users/$ownerId/private/photo-1.jpg",
                contentType = "image/jpeg",
                byteSize = 1_024,
                pixelWidth = 800,
                pixelHeight = 600
            ),
            assetIdFactory = { UUID.fromString("20000000-0000-0000-0000-000000000001") }
        )
        assertNotNull(
            repository.addAssetToAlbum(
                ownerUserId = ownerId,
                albumId = album.albumId,
                mediaAssetId = asset.mediaAssetId,
                sortOrder = 0
            )
        )

        assertNull(repository.listAlbumItemsForViewer(granteeId, album.albumId))

        val grant = repository.grantAlbumAccess(
            ownerUserId = ownerId,
            albumId = album.albumId,
            granteeUserId = granteeId
        )

        assertNotNull(grant)
        assertTrue(repository.hasAlbumAccess(granteeId, album.albumId))
        assertEquals(1, repository.listAlbumItemsForViewer(granteeId, album.albumId)?.size)

        val revokedGrant = repository.revokeAlbumAccess(
            ownerUserId = ownerId,
            albumId = album.albumId,
            granteeUserId = granteeId
        )

        assertNotNull(revokedGrant?.revokedAt)
        assertFalse(repository.hasAlbumAccess(granteeId, album.albumId))
        assertNull(repository.listAlbumItemsForViewer(granteeId, album.albumId))
    }

    @Test
    fun listPrivateAlbumGrantsForOwnerReturnsActiveGranteesWithDisplayName() = runBlocking {
        val ownerId = UUID.fromString("00000000-0000-0000-0000-000000000301")
        val granteeWithName = UUID.fromString("00000000-0000-0000-0000-000000000302")
        val granteeWithoutName = UUID.fromString("00000000-0000-0000-0000-000000000303")
        val revokedGrantee = UUID.fromString("00000000-0000-0000-0000-000000000304")
        val repository = JdbcMediaRepository(dataSource)
        dataSource.insertUser(ownerId)
        dataSource.insertUser(granteeWithName)
        dataSource.insertUser(granteeWithoutName)
        dataSource.insertUser(revokedGrantee)
        dataSource.setProfileDisplayName(granteeWithName, "Maya")

        val album = repository.ensureAlbum(
            ownerUserId = ownerId,
            kind = "private",
            title = "Private album",
            albumIdFactory = { UUID.fromString("10000000-0000-0000-0000-000000000003") }
        )
        repository.grantAlbumAccess(ownerId, album.albumId, granteeWithName)
        repository.grantAlbumAccess(ownerId, album.albumId, granteeWithoutName)
        repository.grantAlbumAccess(ownerId, album.albumId, revokedGrantee)
        repository.revokeAlbumAccess(ownerId, album.albumId, revokedGrantee)

        val summaries = repository.listPrivateAlbumGrantsForOwner(ownerId)

        val granteeIds = summaries.map { it.granteeUserId }
        assertEquals(listOf(granteeWithName, granteeWithoutName).toSet(), granteeIds.toSet())
        val withName = summaries.single { it.granteeUserId == granteeWithName }
        val withoutName = summaries.single { it.granteeUserId == granteeWithoutName }
        assertEquals("Maya", withName.granteeDisplayName)
        assertNull(withoutName.granteeDisplayName)
    }

    @Test
    fun profileAlbumIsVisibleWithoutGrant() = runBlocking {
        val ownerId = UUID.fromString("00000000-0000-0000-0000-000000000201")
        val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000202")
        val repository = JdbcMediaRepository(dataSource)
        dataSource.insertUser(ownerId)
        dataSource.insertUser(viewerId)

        val album = repository.ensureAlbum(
            ownerUserId = ownerId,
            kind = "profile",
            title = "Profile photos",
            albumIdFactory = { UUID.fromString("10000000-0000-0000-0000-000000000002") }
        )

        assertTrue(repository.hasAlbumAccess(viewerId, album.albumId))
    }

    private fun DataSource.insertUser(userId: UUID) {
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
        }
    }

    private fun DataSource.setProfileDisplayName(userId: UUID, displayName: String) {
        connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO profiles (user_id, display_name)
                VALUES (?, ?)
                ON CONFLICT (user_id) DO UPDATE SET display_name = EXCLUDED.display_name
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setString(2, displayName)
                statement.executeUpdate()
            }
        }
    }

    private fun DataSource.tableExists(tableName: String): Boolean =
        connection.use { connection ->
            connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL").use { statement ->
                statement.setString(1, tableName)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getBoolean(1)
                }
            }
        }

    private fun DataSource.indexExists(indexName: String): Boolean =
        connection.use { connection ->
            connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL").use { statement ->
                statement.setString(1, indexName)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getBoolean(1)
                }
            }
        }

    companion object {
        private val postgisImage = DockerImageName
            .parse("postgis/postgis:16-3.4")
            .asCompatibleSubstituteFor("postgres")

        private val postgres = PostgreSQLContainer<Nothing>(postgisImage).apply {
            withDatabaseName("pynapli_media_test")
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
