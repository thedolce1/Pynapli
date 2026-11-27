package com.pynapli.server.grid

import com.pynapli.server.testsupport.PynapliTestDatabase
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

/**
 * G2-2 ticket 6: the rank tier (0 boosted, 1 normal, 2 demoted) now comes from joined sets instead of
 * per-candidate subqueries. Each case of the rule still lands in the same tier, including the edge the
 * old GREATEST(created_at, subquery) handled: a member with no last-active row at all.
 * GridRankOrderPinTest pins the full order on top of this.
 */
class GridRankTierJoinTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest {
            PynapliTestDatabase.reset()
        }

        "every tier case is ranked exactly as the subquery rule ranked it" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000023001")
                val activeWithPhoto = UUID.fromString("00000000-0000-0000-0000-000000023002")
                val dormantWithPhoto = UUID.fromString("00000000-0000-0000-0000-000000023003")
                val activeNoPhoto = UUID.fromString("00000000-0000-0000-0000-000000023004")
                val boostedNoPhoto = UUID.fromString("00000000-0000-0000-0000-000000023005")
                val newNoActivityRow = UUID.fromString("00000000-0000-0000-0000-000000023006")
                val oldNoActivityRow = UUID.fromString("00000000-0000-0000-0000-000000023007")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 30, longitude = -122.0840, latitude = 37.4220)
                listOf(activeWithPhoto, dormantWithPhoto, activeNoPhoto, boostedNoPhoto, newNoActivityRow, oldNoActivityRow)
                    .forEachIndexed { index, id ->
                        dataSource.insertDiscoverableUser(id, "T$index", age = 30, longitude = -122.0830 + 0.002 * index, latitude = 37.4220)
                    }
                listOf(activeWithPhoto, dormantWithPhoto, newNoActivityRow, oldNoActivityRow).forEach { dataSource.insertApprovedProfilePhoto(it) }
                listOf(activeWithPhoto, dormantWithPhoto, activeNoPhoto, boostedNoPhoto, oldNoActivityRow).forEach {
                    dataSource.setUserCreatedDaysAgo(it, days = 90)
                }
                dataSource.setLastActive(activeWithPhoto, Instant.now().minus(Duration.ofHours(2)))
                dataSource.setLastActive(dormantWithPhoto, Instant.now().minus(Duration.ofDays(40)))
                dataSource.setLastActive(activeNoPhoto, Instant.now().minus(Duration.ofHours(2)))
                dataSource.setLastActive(boostedNoPhoto, Instant.now().minus(Duration.ofHours(2)))
                dataSource.insertBoostActivation(boostedNoPhoto)
                // newNoActivityRow: created now, no user_last_active row: counts as active (GREATEST
                // ignores the NULL). oldNoActivityRow: created 90 days ago, no row: dormant.

                val tiers =
                    JdbcGridRepository(dataSource, rankCacheTtl = Duration.ZERO)
                        .queryNearby(viewer, radiusMeters = 100_000, limit = 20)
                        .cards
                        .associate { it.userId to it.boostBucket }

                assertEquals(
                    mapOf(
                        boostedNoPhoto to 0,
                        activeWithPhoto to 1,
                        newNoActivityRow to 1,
                        dormantWithPhoto to 2,
                        activeNoPhoto to 2,
                        oldNoActivityRow to 2,
                    ),
                    tiers,
                )
            }
        }
    })
