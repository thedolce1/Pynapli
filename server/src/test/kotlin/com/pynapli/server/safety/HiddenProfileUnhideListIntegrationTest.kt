package com.pynapli.server.safety

import com.pynapli.server.grid.GridService
import com.pynapli.server.grid.JdbcGridRepository
import com.pynapli.server.grid.insertApprovedProfilePhoto
import com.pynapli.server.grid.insertBlock
import com.pynapli.server.grid.insertDiscoverableUser
import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.GridQueryRequestDto
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Plan G2-4: hide is silent and one-way, the Grid drops the card, and the Safety Center lists the
 * hidden people with a small avatar and Unhide. The avatar never shows across a block.
 */
class HiddenProfileUnhideListIntegrationTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest {
            PynapliTestDatabase.reset()
        }

        val urls =
            object : MediaAssetUrlResolver {
                override fun publicContentUrl(mediaAssetId: UUID): String = "https://media.test/$mediaAssetId"
            }

        "the unhide list carries a name and avatar, never across a block, and Unhide brings the card back" {
            runBlocking {
                val viewer = UUID.randomUUID()
                val withPhoto = UUID.randomUUID()
                val blockedLater = UUID.randomUUID()
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(withPhoto, "Noa", age = 33, longitude = -122.0836, latitude = 37.4226)
                dataSource.insertDiscoverableUser(blockedLater, "Ivo", age = 35, longitude = -122.0836, latitude = 37.4226)
                dataSource.insertApprovedProfilePhoto(withPhoto)
                dataSource.insertApprovedProfilePhoto(blockedLater)
                val repository = JdbcHiddenProfileRepository(dataSource)
                val service = HiddenProfileService(repository, mediaAssetUrlResolver = urls)
                val grid = GridService(JdbcGridRepository(dataSource), hiddenProfileRepository = repository)

                service.hideProfile(viewer, withPhoto.toString())
                service.hideProfile(viewer, blockedLater.toString())
                // They block the viewer after being hidden: no photo of theirs in the list any more.
                dataSource.insertBlock(blockerUserId = blockedLater, blockedUserId = viewer)

                val listed = service.listHiddenProfiles(viewer).hiddenProfiles.associateBy { it.hiddenUserId }
                assertEquals(setOf(withPhoto.toString(), blockedLater.toString()), listed.keys)
                val noa = listed.getValue(withPhoto.toString())
                assertEquals("Noa", noa.hiddenDisplayName)
                assertNotNull(noa.thumbnailUrl)
                assertNotNull(noa.thumbnailCacheKey)
                assertNull(listed.getValue(blockedLater.toString()).thumbnailUrl)

                // Hidden: gone from the viewer's Grid. The hidden person still sees the viewer (one-way).
                val request = GridQueryRequestDto(radiusMeters = 100_000)
                assertFalse(withPhoto.toString() in grid.queryGrid(viewer, request).items.map { it.userId })
                assertTrue(viewer.toString() in grid.queryGrid(withPhoto, request).items.map { it.userId })

                // Unhide: back in the Grid and off the list.
                assertTrue(service.unhideProfile(viewer, withPhoto.toString()))
                assertTrue(withPhoto.toString() in grid.queryGrid(viewer, request).items.map { it.userId })
                assertEquals(listOf(blockedLater.toString()), service.listHiddenProfiles(viewer).hiddenProfiles.map { it.hiddenUserId })
            }
        }

        "no avatar once the hidden person goes incognito or is quarantined, back when they return" {
            runBlocking {
                val viewer = UUID.randomUUID()
                val incognito = UUID.randomUUID()
                val quarantined = UUID.randomUUID()
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(incognito, "Ines", age = 31, longitude = -122.0836, latitude = 37.4226)
                dataSource.insertDiscoverableUser(quarantined, "Quin", age = 36, longitude = -122.0836, latitude = 37.4226)
                dataSource.insertApprovedProfilePhoto(incognito)
                dataSource.insertApprovedProfilePhoto(quarantined)
                val service = HiddenProfileService(JdbcHiddenProfileRepository(dataSource), mediaAssetUrlResolver = urls)
                service.hideProfile(viewer, incognito.toString())
                service.hideProfile(viewer, quarantined.toString())

                suspend fun avatars() = service.listHiddenProfiles(viewer).hiddenProfiles.associate { it.hiddenUserId to it.thumbnailUrl }
                assertNotNull(avatars().getValue(incognito.toString()))
                assertNotNull(avatars().getValue(quarantined.toString()))

                dataSource.connection.use { connection ->
                    connection.prepareStatement("UPDATE discovery_settings SET incognito = true WHERE user_id = ?").use {
                        it.setObject(1, incognito)
                        it.executeUpdate()
                    }
                    connection
                        .prepareStatement(
                            """
                            INSERT INTO account_risk_states (user_id, score, tier) VALUES (?, 50, 'quarantine')
                            ON CONFLICT (user_id) DO UPDATE SET tier = EXCLUDED.tier, score = EXCLUDED.score
                            """.trimIndent(),
                        ).use {
                            it.setObject(1, quarantined)
                            it.executeUpdate()
                        }
                }
                // Still listed so they can be unhidden, but nothing of their current profile shows.
                assertEquals(setOf(incognito.toString(), quarantined.toString()), avatars().keys)
                assertNull(avatars().getValue(incognito.toString()))
                assertNull(avatars().getValue(quarantined.toString()))

                dataSource.connection.use { connection ->
                    connection.prepareStatement("UPDATE discovery_settings SET incognito = false WHERE user_id = ?").use {
                        it.setObject(1, incognito)
                        it.executeUpdate()
                    }
                }
                assertNotNull(avatars().getValue(incognito.toString()))
            }
        }
    })
