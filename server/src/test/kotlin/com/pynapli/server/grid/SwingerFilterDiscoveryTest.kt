package com.pynapli.server.grid

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.swipe.JdbcSwipeRepository
import com.pynapli.server.testsupport.PynapliTestDatabase
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Round 4 swinger filters on the real rank SQL (LifestyleFilterSql), on Grid and Swipe alike, at
 * home and in Travel mode: swap/room/experience are "any of" inside a group and "all of" across
 * groups; experience matches the single experience_level; boundaries hide "not for me" only.
 */
class SwingerFilterDiscoveryTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest {
            PynapliTestDatabase.reset()
        }

        val viewer = UUID.fromString("00000000-0000-0000-0000-000000004001")
        val softSame = UUID.fromString("00000000-0000-0000-0000-000000004002")
        val softSeparate = UUID.fromString("00000000-0000-0000-0000-000000004003")
        val fullSame = UUID.fromString("00000000-0000-0000-0000-000000004004")
        val untagged = UUID.fromString("00000000-0000-0000-0000-000000004005")

        // Seeds four candidates around (lon, lat); the viewer lives in Mountain View.
        fun seed(
            lon: Double,
            lat: Double,
        ) {
            dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
            dataSource.insertDiscoverableUser(softSame, "Ana", age = 30, longitude = lon + 0.0005, latitude = lat + 0.0006)
            dataSource.insertDiscoverableUser(softSeparate, "Bo", age = 31, longitude = lon + 0.0003, latitude = lat + 0.0008)
            dataSource.insertDiscoverableUser(fullSame, "Cy", age = 32, longitude = lon + 0.0011, latitude = lat + 0.0010)
            dataSource.insertDiscoverableUser(untagged, "Di", age = 33, longitude = lon + 0.0008, latitude = lat + 0.0004)
            dataSource.setProfileLifestyleTags(softSame, listOf("soft_swap", "same_room"))
            dataSource.setProfileLifestyleTags(softSeparate, listOf("soft_swap", "separate_room"))
            dataSource.setProfileLifestyleTags(fullSame, listOf("full_swap", "same_room"))
            dataSource.setProfileExperienceLevel(softSame, "new_to_lifestyle")
            dataSource.setProfileExperienceLevel(softSeparate, "some_experience")
            dataSource.setProfileExperienceLevel(fullSame, "experienced")
            dataSource.setProfileBoundaries(softSame, listOf("photos:not_for_me"))
            dataSource.setProfileBoundaries(softSeparate, listOf("photos:ask_first", "groups:not_for_me"))
        }

        suspend fun grid(filters: DiscoveryFilters): Set<UUID> =
            JdbcGridRepository(dataSource)
                .queryNearby(viewerUserId = viewer, radiusMeters = 100_000, limit = 20, filters = filters)
                .cards
                .map { it.userId }
                .toSet()

        suspend fun swipe(filters: DiscoveryFilters): Set<UUID> =
            JdbcSwipeRepository(dataSource)
                .queryDeck(viewerUserId = viewer, radiusMeters = 100_000, limit = 20, filters = filters)
                .cards
                .map { it.userId }
                .toSet()

        suspend fun assertBothSurfaces(
            filters: DiscoveryFilters,
            expected: Set<UUID>,
        ) {
            assertEquals(expected, grid(filters), "grid $filters")
            assertEquals(expected, swipe(filters), "swipe $filters")
        }

        suspend fun assertSwingerSemantics() {
            // Across groups: soft swap AND same room.
            assertBothSurfaces(DiscoveryFilters(lifestyleTags = setOf("soft_swap", "same_room")), setOf(softSame))
            // Within a group: soft OR full swap.
            assertBothSurfaces(
                DiscoveryFilters(lifestyleTags = setOf("soft_swap", "full_swap")),
                setOf(softSame, softSeparate, fullSame),
            )
            // Experience is one level per person; any-of inside the group.
            assertBothSurfaces(DiscoveryFilters(lifestyleTags = setOf("some_experience")), setOf(softSeparate))
            assertBothSurfaces(
                DiscoveryFilters(lifestyleTags = setOf("new_to_lifestyle", "experienced")),
                setOf(softSame, fullSame),
            )
            // Boundaries hide "not for me" only: ask-first and unset still show.
            assertBothSurfaces(DiscoveryFilters(boundaryItems = setOf("photos")), setOf(softSeparate, fullSame, untagged))
            assertBothSurfaces(
                DiscoveryFilters(boundaryItems = setOf("photos", "groups")),
                setOf(fullSame, untagged),
            )
            // Combined: same room + experienced + photos OK.
            assertBothSurfaces(
                DiscoveryFilters(lifestyleTags = setOf("same_room", "experienced"), boundaryItems = setOf("photos")),
                setOf(fullSame),
            )
        }

        "swap, room, experience and boundary filters match the same way on Grid and Swipe" {
            runBlocking {
                seed(lon = -122.0841, lat = 37.4220)
                assertSwingerSemantics()
            }
        }

        "the same filters apply in Travel mode, where the viewer browses their destination" {
            runBlocking {
                // Candidates live in Las Vegas; the viewer is travelling there.
                seed(lon = -115.1398, lat = 36.1699)
                dataSource.insertActiveTravelLocation(viewer, "Las Vegas", latitude = 36.1699, longitude = -115.1398)
                assertSwingerSemantics()
            }
        }
    })
