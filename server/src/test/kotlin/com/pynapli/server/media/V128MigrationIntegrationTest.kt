package com.pynapli.server.media

import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.server.verification.insertVerificationUser
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * V128 (one live album per asset) re-run against seeded pre-fix rows: the double-album bypass, a
 * pre-V72 asset in both albums, a mismatched single placement, and a duplicate across a deleted and a
 * live album. Nothing is hard-deleted; surplus placements are soft-removed.
 */
class V128MigrationIntegrationTest {
    private val dataSource: DataSource = PynapliTestDatabase.dataSource

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    @Test
    fun surplusPlacementsAreSoftRemovedAndNothingIsLost() {
        val owner = dataSource.insertVerificationUser("Owner")
        val profileAlbum = album(owner, "profile")
        val privateAlbum = album(owner, "private")
        val deletedProfileAlbum = album(owner, "profile", deleted = true)

        val preV72Both = asset(owner, intended = null, moderation = "approved")
        val bypassProfile = asset(owner, intended = "profile", moderation = "approved")
        val privateBoth = asset(owner, intended = "private", moderation = "approved")
        val profileInPrivateOnly = asset(owner, intended = "profile", moderation = "approved")
        val deletedAndLive = asset(owner, intended = "profile", moderation = "approved")

        try {
            // The live-placement index would refuse these rows: seed them as the pre-fix code left them.
            execute("DROP INDEX IF EXISTS idx_media_album_items_one_live_album_per_asset")
            item(profileAlbum, preV72Both, owner, 0)
            item(privateAlbum, preV72Both, owner, 0)
            item(profileAlbum, bypassProfile, owner, 1)
            item(privateAlbum, bypassProfile, owner, 1)
            item(privateAlbum, privateBoth, owner, 2)
            item(profileAlbum, privateBoth, owner, 2)
            item(privateAlbum, profileInPrivateOnly, owner, 3)
            item(deletedProfileAlbum, deletedAndLive, owner, 0, addedAt = "2026-01-01T00:00:00Z")
            item(profileAlbum, deletedAndLive, owner, 3, addedAt = "2026-06-01T00:00:00Z")
            val itemsBefore = count("SELECT count(*) FROM media_album_items")
            val assetsBefore = count("SELECT count(*) FROM media_assets")

            runV128()

            assertEquals(itemsBefore, count("SELECT count(*) FROM media_album_items"), "no album placement is hard-deleted")
            assertEquals(assetsBefore, count("SELECT count(*) FROM media_assets"), "no photo is deleted")

            // Pre-V72 asset in both albums: kept as a profile photo, back in the human queue.
            assertEquals("profile", kind(preV72Both))
            assertEquals("uploaded" to "pending", status(preV72Both))
            assertNull(removedAt(profileAlbum, preV72Both))
            assertNotNull(removedAt(privateAlbum, preV72Both))

            // The bypass footprint: same outcome.
            assertEquals("uploaded" to "pending", status(bypassProfile))
            assertNull(removedAt(profileAlbum, bypassProfile))
            assertNotNull(removedAt(privateAlbum, bypassProfile))

            // Opened as private: stays in the private album, public placement soft-removed.
            assertEquals("ready" to "approved", status(privateBoth))
            assertNull(removedAt(privateAlbum, privateBoth))
            assertNotNull(removedAt(profileAlbum, privateBoth))

            // A single placement in the other kind's album takes that album's kind.
            assertEquals("private", kind(profileInPrivateOnly))
            assertNull(removedAt(privateAlbum, profileInPrivateOnly))

            // Same kind in a deleted and a live album: the live album's placement is kept.
            assertNull(removedAt(profileAlbum, deletedAndLive))
            assertNotNull(removedAt(deletedProfileAlbum, deletedAndLive))

            // Every asset now has at most one live placement.
            assertEquals(
                0,
                count("SELECT count(*) FROM (SELECT media_asset_id FROM media_album_items WHERE removed_at IS NULL GROUP BY media_asset_id HAVING count(*) > 1) d"),
            )
            // Already-decided assets are recorded as scanned (a retried complete won't re-scan).
            assertEquals(0, count("SELECT count(*) FROM media_assets WHERE processing_status <> 'pending_upload' AND moderation_scanned_at IS NULL"))
        } finally {
            // Leave the schema as the migrations define it for the other tests.
            execute("DELETE FROM media_album_items")
            runV128()
        }
    }

    private fun runV128() {
        val sql =
            requireNotNull(javaClass.classLoader.getResource("db/migration/V128__media_asset_one_album.sql")) { "V128 not on the classpath" }
                .readText()
        execute(sql)
    }

    private fun album(
        owner: UUID,
        kind: String,
        deleted: Boolean = false,
    ): UUID {
        val id = UUID.randomUUID()
        execute(
            "INSERT INTO media_albums (id, owner_user_id, kind, title, visibility_status, deleted_at) VALUES (?, ?, ?, ?, 'visible', ?)",
            id,
            owner,
            kind,
            kind,
            if (deleted) Timestamp.from(Instant.parse("2026-02-01T00:00:00Z")) else null,
        )
        return id
    }

    private fun asset(
        owner: UUID,
        intended: String?,
        moderation: String,
    ): UUID {
        val id = UUID.randomUUID()
        execute(
            "INSERT INTO media_assets (id, owner_user_id, media_type, storage_provider, storage_bucket, storage_object_key, content_type, " +
                "byte_size, processing_status, moderation_status, intended_album_kind) " +
                "VALUES (?, ?, 'image', 'gcs', 'pynapli-test-media', ?, 'image/jpeg', 10, 'ready', ?, ?)",
            id,
            owner,
            "media/$id",
            moderation,
            intended,
        )
        return id
    }

    private fun item(
        album: UUID,
        asset: UUID,
        owner: UUID,
        sortOrder: Int,
        addedAt: String = "2026-03-01T00:00:00Z",
    ) = execute(
        "INSERT INTO media_album_items (album_id, media_asset_id, owner_user_id, sort_order, added_at) VALUES (?, ?, ?, ?, ?)",
        album,
        asset,
        owner,
        sortOrder,
        Timestamp.from(Instant.parse(addedAt)),
    )

    private fun kind(asset: UUID): String? = string("SELECT intended_album_kind FROM media_assets WHERE id = ?", asset)

    private fun status(asset: UUID): Pair<String?, String?> =
        string("SELECT processing_status FROM media_assets WHERE id = ?", asset) to
            string("SELECT moderation_status FROM media_assets WHERE id = ?", asset)

    private fun removedAt(
        album: UUID,
        asset: UUID,
    ): String? = string("SELECT removed_at::text FROM media_album_items WHERE album_id = ? AND media_asset_id = ?", album, asset)

    private fun string(
        sql: String,
        vararg args: Any,
    ): String? =
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    private fun count(sql: String): Int =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun execute(
        sql: String,
        vararg args: Any?,
    ) {
        dataSource.connection.use { connection ->
            if (args.isEmpty()) {
                connection.createStatement().use { it.execute(sql) }
            } else {
                connection.prepareStatement(sql).use { statement ->
                    args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                    statement.executeUpdate()
                }
            }
        }
    }
}
