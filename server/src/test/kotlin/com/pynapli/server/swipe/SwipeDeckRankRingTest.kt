package com.pynapli.server.swipe

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.grid.insertActiveTravelLocation
import com.pynapli.server.grid.insertBoostActivation
import com.pynapli.server.grid.insertDiscoverableUser
import com.pynapli.server.grid.markProfileVerified
import com.pynapli.server.grid.setShowDistance
import com.pynapli.server.testsupport.PynapliTestDatabase
import io.kotest.core.spec.style.StringSpec
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ring pass of the deck rank ([queryDeckRankBounded]) is a cost bound, never a ranking change:
 * for every viewer, radius step, page size and filter it must return the window that ranking the
 * whole radius step returns, in the same order, with the same boosted candidates after it.
 */
class SwipeDeckRankRingTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest {
            PynapliTestDatabase.reset()
        }

        val centreLongitude = -122.08
        val centreLatitude = 37.42

        fun id(index: Int): UUID = UUID.fromString("00000000-0000-0000-0000-0000000a%04d".format(index))

        // A dense core inside the first ring and a thinner spread out to ~45 km, with people who hide
        // distance, verified people, travellers and boosted people scattered through both.
        fun seedMetro(): List<UUID> {
            val random = Random(20261118)
            val ids = (0 until 420).map(::id)
            ids.forEachIndexed { index, userId ->
                val spread = if (index % 2 == 0) 0.03 else 0.40
                val longitude = centreLongitude + (random.nextDouble() * 2 - 1) * spread
                val latitude = centreLatitude + (random.nextDouble() * 2 - 1) * spread
                dataSource.insertDiscoverableUser(userId, "Person $index", 25 + index % 30, longitude, latitude)
                if (index % 3 == 0) dataSource.setShowDistance(userId, false)
                if (index % 4 == 0) dataSource.markProfileVerified(userId)
                if (index % 53 == 0) dataSource.insertBoostActivation(userId)
                if (index % 37 == 0) {
                    dataSource.insertActiveTravelLocation(
                        userId,
                        "Elsewhere",
                        centreLatitude + (random.nextDouble() * 2 - 1) * 0.40,
                        centreLongitude + (random.nextDouble() * 2 - 1) * 0.40,
                    )
                }
            }
            return ids
        }

        "a ring-ranked deck is the whole-radius deck, row for row" {
            val ids = seedMetro()
            val viewers = listOf(ids[0], ids[1], ids[2], ids[37], ids[111])
            val filterSets =
                listOf(
                    DiscoveryFilters(),
                    DiscoveryFilters(verifiedPreferred = true),
                    DiscoveryFilters(minimumAge = 30, maximumAge = 45),
                    DiscoveryFilters(verifiedOnly = true, verifiedPreferred = true),
                )
            var ringServed = 0
            var compared = 0
            dataSource.connection.use { connection ->
                viewers.forEach { viewer ->
                    listOf(10_000, 25_000, 50_000, 100_000).forEach { radius ->
                        listOf(5, 20, 128).forEach { limit ->
                            filterSets.forEach { filters ->
                                // Shared (cacheable) window, then this viewer's own page past some cards.
                                listOf<Pair<UUID?, Set<UUID>>>(
                                    null to emptySet(),
                                    viewer to ids.filterIndexed { index, _ -> index % 5 == 0 }.toSet(),
                                ).forEach { (specificViewer, excluded) ->
                                    val whole =
                                        connection.queryDeckRank(
                                            viewerUserId = viewer,
                                            radiusMeters = radius,
                                            limit = limit,
                                            filters = filters,
                                            excludedTargetUserIds = excluded,
                                            userSpecificViewerId = specificViewer,
                                        )
                                    val bounded =
                                        connection.queryDeckRankBounded(
                                            viewerUserId = viewer,
                                            radiusMeters = radius,
                                            limit = limit,
                                            filters = filters,
                                            excludedTargetUserIds = excluded,
                                            userSpecificViewerId = specificViewer,
                                        )
                                    val case = "viewer=$viewer radius=$radius limit=$limit filters=$filters specific=${specificViewer != null}"
                                    // The window: identical rows, ranks included.
                                    assertEquals(
                                        whole.filter { it.naturalRank <= limit },
                                        bounded.filter { it.naturalRank <= limit },
                                        "window differs: $case",
                                    )
                                    // Boosted candidates past the window: same people, same order
                                    // (their naturalRank may read lower, but never inside the window).
                                    assertEquals(
                                        whole.filter { it.naturalRank > limit }.map { it.userId },
                                        bounded.filter { it.naturalRank > limit }.map { it.userId },
                                        "boosted tail differs: $case",
                                    )
                                    assertEquals(
                                        SwipeDeckBoostPlacement.arrange(whole, limit).map { it.userId },
                                        SwipeDeckBoostPlacement.arrange(bounded, limit).map { it.userId },
                                        "page differs: $case",
                                    )
                                    compared++
                                    if (bounded.any { it.naturalRank > limit && it.naturalRank != whole.first { w -> w.userId == it.userId }.naturalRank } ||
                                        DECK_RANK_RINGS_METERS.any { ring ->
                                            ring < radius &&
                                                connection
                                                    .queryDeckRank(
                                                        viewerUserId = viewer,
                                                        radiusMeters = radius,
                                                        limit = limit,
                                                        filters = filters,
                                                        excludedTargetUserIds = excluded,
                                                        userSpecificViewerId = specificViewer,
                                                        ringMeters = ring,
                                                    ).fillsWindowFromRing(limit, ring, filters.verifiedPreferred)
                                        }
                                    ) {
                                        ringServed++
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // Not vacuous: rings served a real share of the cases, and some fell through to the whole radius.
            assertTrue(ringServed > compared / 4, "rings served only $ringServed of $compared cases")
            assertTrue(ringServed < compared, "every case was served from a ring; the fall-through is untested")
        }

        "a ring that cannot fill the window, or holds an unverified card under verified-preferred, is not trusted" {
            fun row(
                rank: Long,
                bucket: Int,
                soft: Int,
            ) = DeckRankRow(
                userId = UUID.randomUUID(),
                displayName = "x",
                displayAge = 30,
                bio = null,
                tags = emptyList(),
                verified = soft > 0,
                visiting = false,
                showDistance = true,
                distanceMeters = bucket.toDouble(),
                isBoosted = false,
                softScore = soft,
                orderBucket = bucket,
                naturalRank = rank,
            )
            assertTrue(listOf(row(1, 0, 0), row(2, 4_000, 0)).fillsWindowFromRing(2, 5_000, verifiedPreferred = false))
            // Too few people in the ring.
            assertTrue(!listOf(row(1, 0, 0)).fillsWindowFromRing(2, 5_000, verifiedPreferred = false))
            // A boosted candidate from beyond the ring inside the window.
            assertTrue(!listOf(row(1, 0, 0), row(2, 9_000, 0)).fillsWindowFromRing(2, 5_000, verifiedPreferred = false))
            // A verified person outside the ring would outrank the unverified card.
            assertTrue(!listOf(row(1, 0, 1), row(2, 1_000, 0)).fillsWindowFromRing(2, 5_000, verifiedPreferred = true))
            assertTrue(listOf(row(1, 0, 1), row(2, 1_000, 1)).fillsWindowFromRing(2, 5_000, verifiedPreferred = true))
        }
    })
