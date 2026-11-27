package com.pynapli.server.grid

import com.pynapli.server.discovery.DiscoveryCursorCodec
import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.discovery.discoveryFiltersFrom
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.GridQueryRequestDto
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

/**
 * Lifestyle "All of (up to 3)" (plan G2-4, Grindr's tags): every chosen tag must be on the profile,
 * instead of any one per group. Validated (3 tags, one experience level), and part of the rank-cache
 * key and the cursor scope.
 */
class LifestyleAllOfFilterIntegrationTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest {
            PynapliTestDatabase.reset()
        }

        "all-of keeps only profiles holding every chosen tag; any-of keeps any one per group" {
            runBlocking {
                val viewer = UUID.randomUUID()
                val both = UUID.randomUUID()
                val softOnly = UUID.randomUUID()
                val fullOnly = UUID.randomUUID()
                val bothExperienced = UUID.randomUUID()
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                listOf(both, softOnly, fullOnly, bothExperienced).forEachIndexed { index, id ->
                    dataSource.insertDiscoverableUser(id, "P$index", age = 30 + index, longitude = -122.0836, latitude = 37.4226)
                }
                dataSource.setProfileLifestyleTags(both, listOf("soft_swap", "full_swap"))
                dataSource.setProfileLifestyleTags(softOnly, listOf("soft_swap"))
                dataSource.setProfileLifestyleTags(fullOnly, listOf("full_swap"))
                dataSource.setProfileLifestyleTags(bothExperienced, listOf("soft_swap", "full_swap"))
                dataSource.setProfileExperienceLevel(bothExperienced, "experienced")
                val repository = JdbcGridRepository(dataSource)

                suspend fun ids(filters: DiscoveryFilters) =
                    repository.queryNearby(viewer, 100_000, 20, filters).cards.map { it.userId }.toSet()

                val tags = setOf("soft_swap", "full_swap")
                assertEquals(setOf(both, softOnly, fullOnly, bothExperienced), ids(DiscoveryFilters(lifestyleTags = tags)))
                assertEquals(setOf(both, bothExperienced), ids(DiscoveryFilters(lifestyleTags = tags, lifestyleMatchAll = true)))
                // An experience level in "All of" must equal the profile's own.
                assertEquals(
                    setOf(bothExperienced),
                    ids(DiscoveryFilters(lifestyleTags = tags + "experienced", lifestyleMatchAll = true)),
                )

                // Through the Grid request too.
                val service = GridService(repository)
                val viaRequest =
                    service.queryGrid(
                        viewer,
                        GridQueryRequestDto(radiusMeters = 100_000, lifestyleTags = tags.toList(), lifestyleMatchAll = true),
                    )
                assertEquals(setOf(both.toString(), bothExperienced.toString()), viaRequest.items.map { it.userId }.toSet())
            }
        }

        "all-of takes at most 3 tags and one experience level, and is in the cache key and cursor scope" {
            fun parse(
                tags: List<String>,
                matchAll: Boolean,
            ) = discoveryFiltersFrom(
                minimumAge = null,
                maximumAge = null,
                relationshipContexts = emptyList(),
                linkedOnly = null,
                lifestyleTags = tags,
                lifestyleMatchAll = matchAll,
            ) { code -> IllegalArgumentException(code) }

            assertFailsWith<IllegalArgumentException> { parse(listOf("soft_swap", "full_swap", "same_room", "enm"), matchAll = true) }
            assertFailsWith<IllegalArgumentException> { parse(listOf("new_to_lifestyle", "experienced"), matchAll = true) }
            // Any-of keeps its own, larger limit.
            parse(listOf("soft_swap", "full_swap", "same_room", "enm"), matchAll = false)
            // Without tags the mode means nothing, so it is dropped (older scopes keep their hash).
            assertFalse(parse(emptyList(), matchAll = true).lifestyleMatchAll)

            val any = parse(listOf("soft_swap", "full_swap"), matchAll = false)
            val all = parse(listOf("soft_swap", "full_swap"), matchAll = true)
            assertNotEquals(any.gridRankCacheKey(), all.gridRankCacheKey())
            val viewer = UUID.randomUUID()
            assertNotEquals(
                DiscoveryCursorCodec.gridScopeHash(viewer, 50_000, 60, any, onlineOnly = false),
                DiscoveryCursorCodec.gridScopeHash(viewer, 50_000, 60, all, onlineOnly = false),
            )
        }
    })
