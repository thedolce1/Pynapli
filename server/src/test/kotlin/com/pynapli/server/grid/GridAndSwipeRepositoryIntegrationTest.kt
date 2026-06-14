package com.pynapli.server.grid

import com.pynapli.server.dependencies.RedisCache
import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.safety.JdbcBlockRepository
import com.pynapli.server.safety.JdbcHiddenProfileRepository
import com.pynapli.server.swipe.JdbcSwipeRepository
import com.pynapli.server.swipe.SwipeException
import com.pynapli.server.swipe.SwipeService
import com.pynapli.server.swipe.canonicalPair
import com.pynapli.shared.network.dto.GridQueryRequestDto
import com.pynapli.shared.network.dto.SwipeAction
import com.pynapli.shared.network.dto.SwipeActionRequestDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GridAndSwipeRepositoryIntegrationTest :
    StringSpec({
        val postgisImage =
            DockerImageName
                .parse("postgis/postgis:16-3.4")
                .asCompatibleSubstituteFor("postgres")
        val postgres =
            PostgreSQLContainer<Nothing>(postgisImage).apply {
                withDatabaseName("pynapli_grid_swipe_test")
                withUsername("pynapli")
                withPassword("pynapli_test_password")
            }
        val redis =
            GenericContainer<Nothing>(DockerImageName.parse("redis:7-alpine"))
                .apply { withExposedPorts(6379) }
        lateinit var dataSource: HikariDataSource

        fun redisCache(): RedisCache = RedisCache("redis://${redis.host}:${redis.getMappedPort(6379)}")

        beforeSpec {
            postgres.start()
            redis.start()
        }

        afterSpec {
            postgres.stop()
            redis.stop()
        }

        beforeTest {
            dataSource =
                HikariDataSource(
                    HikariConfig().apply {
                        jdbcUrl = postgres.jdbcUrl
                        username = postgres.username
                        password = postgres.password
                        maximumPoolSize = 16
                        poolName = "pynapli-grid-swipe-test"
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

                val gridCards =
                    JdbcGridRepository(dataSource)
                        .queryNearby(
                            viewerUserId = viewer,
                            radiusMeters = 100_000,
                            limit = 10,
                        ).cards
                val swipeCards =
                    JdbcSwipeRepository(dataSource)
                        .queryDeck(
                            viewerUserId = viewer,
                            radiusMeters = 100_000,
                            limit = 10,
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

                val gridCards =
                    JdbcGridRepository(dataSource)
                        .queryNearby(
                            viewerUserId = viewer,
                            radiusMeters = 100_000,
                            limit = 10,
                        ).cards
                val swipeCards =
                    JdbcSwipeRepository(dataSource)
                        .queryDeck(
                            viewerUserId = viewer,
                            radiusMeters = 100_000,
                            limit = 10,
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

        "swipe service persists deck pages and actioned card state" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000000925")
                val firstTarget = UUID.fromString("00000000-0000-0000-0000-000000000926")
                val secondTarget = UUID.fromString("00000000-0000-0000-0000-000000000927")
                val thirdTarget = UUID.fromString("00000000-0000-0000-0000-000000000928")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(firstTarget, "First", age = 35, longitude = -122.0836, latitude = 37.4226)
                dataSource.insertDiscoverableUser(secondTarget, "Second", age = 36, longitude = -122.0838, latitude = 37.4228)
                dataSource.insertDiscoverableUser(thirdTarget, "Third", age = 37, longitude = -122.0840, latitude = 37.4230)
                val service = SwipeService(JdbcSwipeRepository(dataSource))

                val firstPage = service.queryDeck(viewer, SwipeDeckRequestDto(limit = 2))
                val secondPage =
                    service.queryDeck(
                        viewer,
                        SwipeDeckRequestDto(limit = 2, cursor = assertNotNull(firstPage.nextCursor)),
                    )
                val actionedCard = firstPage.items.first()
                val actionRequest =
                    SwipeActionRequestDto(
                        actionId = UUID.randomUUID().toString(),
                        deckSessionId = firstPage.deckSessionId,
                        cardToken = actionedCard.rankToken,
                        targetUserId = actionedCard.userId,
                        action = SwipeAction.LIKE,
                    )
                val response = service.submitAction(viewer, actionRequest)
                val retry = service.submitAction(viewer, actionRequest)
                val repeated =
                    assertFailsWith<SwipeException> {
                        service.submitAction(
                            viewer,
                            SwipeActionRequestDto(
                                actionId = UUID.randomUUID().toString(),
                                deckSessionId = firstPage.deckSessionId,
                                cardToken = actionedCard.rankToken,
                                targetUserId = actionedCard.userId,
                                action = SwipeAction.LIKE,
                            ),
                        )
                    }

                assertEquals(listOf(firstTarget, secondTarget).map { it.toString() }, firstPage.items.map { it.userId })
                assertEquals(listOf(thirdTarget.toString()), secondPage.items.map { it.userId })
                assertEquals(firstPage.deckSessionId, secondPage.deckSessionId)
                assertTrue(response.recorded)
                assertTrue(retry.recorded)
                assertEquals("INVALID_SWIPE_CARD_TOKEN", repeated.code)
            }
        }

        "swipe action rejects a stale dealt card after either user blocks" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000002001")
                val target = UUID.fromString("00000000-0000-0000-0000-000000002002")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(target, "Maya", age = 35, longitude = -122.0836, latitude = 37.4226)
                val service = SwipeService(JdbcSwipeRepository(dataSource))

                val deck = service.queryDeck(viewer, SwipeDeckRequestDto(limit = 2))
                val card = deck.items.single()
                dataSource.insertBlock(target, viewer)
                val error =
                    assertFailsWith<SwipeException> {
                        service.submitAction(
                            viewer,
                            SwipeActionRequestDto(
                                actionId = UUID.randomUUID().toString(),
                                deckSessionId = deck.deckSessionId,
                                cardToken = card.rankToken,
                                targetUserId = target.toString(),
                                action = SwipeAction.LIKE,
                            ),
                        )
                    }

                assertEquals("INVALID_SWIPE_TARGET", error.code)
                assertEquals(0, dataSource.countSwipeActions(viewer, target))
            }
        }

        "swipe deck and action eligibility exclude profiles hidden by the viewer" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-0000000020a1")
                val hiddenTarget = UUID.fromString("00000000-0000-0000-0000-0000000020a2")
                val visibleTarget = UUID.fromString("00000000-0000-0000-0000-0000000020a3")

                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(hiddenTarget, "Hidden", age = 35, longitude = -122.0836, latitude = 37.4226)
                dataSource.insertDiscoverableUser(visibleTarget, "Visible", age = 36, longitude = -122.0838, latitude = 37.4228)
                val service = SwipeService(JdbcSwipeRepository(dataSource))

                val originalDeck = service.queryDeck(viewer, SwipeDeckRequestDto(limit = 2))
                val staleHiddenCard = originalDeck.items.first { it.userId == hiddenTarget.toString() }
                dataSource.insertHiddenProfile(viewer, hiddenTarget)

                val refreshedDeck = service.queryDeck(viewer, SwipeDeckRequestDto(limit = 2))
                assertEquals(false, refreshedDeck.items.any { it.userId == hiddenTarget.toString() })
                assertTrue(refreshedDeck.items.any { it.userId == visibleTarget.toString() })

                val error =
                    assertFailsWith<SwipeException> {
                        service.submitAction(
                            viewer,
                            SwipeActionRequestDto(
                                actionId = UUID.randomUUID().toString(),
                                deckSessionId = originalDeck.deckSessionId,
                                targetUserId = staleHiddenCard.userId,
                                action = SwipeAction.LIKE,
                                cardToken = staleHiddenCard.rankToken,
                            ),
                        )
                    }
                assertEquals("INVALID_SWIPE_TARGET", error.code)
            }
        }

        "swipe action rejects a stale dealt card after target leaves swipe discovery" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000002011")
                val target = UUID.fromString("00000000-0000-0000-0000-000000002012")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(target, "Maya", age = 35, longitude = -122.0836, latitude = 37.4226)
                val service = SwipeService(JdbcSwipeRepository(dataSource))

                val deck = service.queryDeck(viewer, SwipeDeckRequestDto(limit = 2))
                val card = deck.items.single()
                dataSource.setSwipeVisible(target, false)
                val error =
                    assertFailsWith<SwipeException> {
                        service.submitAction(
                            viewer,
                            SwipeActionRequestDto(
                                actionId = UUID.randomUUID().toString(),
                                deckSessionId = deck.deckSessionId,
                                cardToken = card.rankToken,
                                targetUserId = target.toString(),
                                action = SwipeAction.LIKE,
                            ),
                        )
                    }

                assertEquals("INVALID_SWIPE_TARGET", error.code)
                assertEquals(0, dataSource.countSwipeActions(viewer, target))
            }
        }

        "swipe action rejects a stale dealt card after target is quarantined" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000002021")
                val target = UUID.fromString("00000000-0000-0000-0000-000000002022")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(target, "Maya", age = 35, longitude = -122.0836, latitude = 37.4226)
                val service = SwipeService(JdbcSwipeRepository(dataSource))

                val deck = service.queryDeck(viewer, SwipeDeckRequestDto(limit = 2))
                val card = deck.items.single()
                dataSource.setRiskState(target, score = 75, tier = "quarantine")
                val error =
                    assertFailsWith<SwipeException> {
                        service.submitAction(
                            viewer,
                            SwipeActionRequestDto(
                                actionId = UUID.randomUUID().toString(),
                                deckSessionId = deck.deckSessionId,
                                cardToken = card.rankToken,
                                targetUserId = target.toString(),
                                action = SwipeAction.LIKE,
                            ),
                        )
                    }

                assertEquals("INVALID_SWIPE_TARGET", error.code)
                assertEquals(0, dataSource.countSwipeActions(viewer, target))
            }
        }

        "swipe rewind soft-rewinds action and restores a fresh actionable card" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000000929")
                val target = UUID.fromString("00000000-0000-0000-0000-000000000930")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(target, "Maya", age = 35, longitude = -122.0836, latitude = 37.4226)
                val service = SwipeService(JdbcSwipeRepository(dataSource))

                val deck = service.queryDeck(viewer, SwipeDeckRequestDto(limit = 2))
                val originalRequest =
                    SwipeActionRequestDto(
                        actionId = UUID.randomUUID().toString(),
                        deckSessionId = deck.deckSessionId,
                        cardToken = deck.items.single().rankToken,
                        targetUserId = target.toString(),
                        action = SwipeAction.PASS,
                    )
                service.submitAction(viewer, originalRequest)

                val rewind = service.rewind(viewer)
                val restoredCard = assertNotNull(rewind.card)
                val replay =
                    service.submitAction(
                        viewer,
                        SwipeActionRequestDto(
                            actionId = UUID.randomUUID().toString(),
                            deckSessionId = rewind.deckSessionId,
                            cardToken = restoredCard.rankToken,
                            targetUserId = target.toString(),
                            action = SwipeAction.LIKE,
                        ),
                    )
                val oldRetry = service.submitAction(viewer, originalRequest)

                assertTrue(rewind.rewound)
                assertEquals(target.toString(), restoredCard.userId)
                assertTrue(rewind.deckSessionId.isNotBlank())
                assertTrue(restoredCard.rankToken.isNotBlank())
                assertTrue(replay.recorded)
                assertEquals(false, oldRetry.recorded)
            }
        }

        "swipe rewind atomically refunds the consumed Pineapple Like inventory row" {
            // Drives JdbcSwipeRepository directly so we can prove the SQL: the action
            // row is marked rewound AND the linked premium_inventory.quantity is
            // incremented in the same transaction. Mirrors the production path for
            // a Pineapple Like after the consume step has already decremented the row.
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000000940")
                val target = UUID.fromString("00000000-0000-0000-0000-000000000941")
                val inventoryId = UUID.fromString("00000000-0000-0000-0000-0000000009f0")
                val actionId = UUID.fromString("00000000-0000-0000-0000-0000000009f1")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(target, "Maya", age = 35, longitude = -122.0836, latitude = 37.4226)
                // Seed an already-consumed inventory row (quantity=0) so the refund
                // step has a target. Production sets quantity = 1 and decrements via
                // the CONSUME_PREMIUM_INVENTORY_QUERY before recordSwipeAction runs.
                dataSource.insertConsumedPineappleLikeInventory(inventoryId, viewer)
                val repository = JdbcSwipeRepository(dataSource)

                val service = SwipeService(repository)
                val deck = service.queryDeck(viewer, SwipeDeckRequestDto(limit = 2))
                repository.recordSwipeAction(
                    actionId = actionId,
                    actorUserId = viewer,
                    targetUserId = target,
                    action = SwipeAction.PINEAPPLE_LIKE,
                    consumedInventoryId = inventoryId,
                )
                // Sanity: action recorded with the inventory id and quantity still 0.
                assertEquals(0, dataSource.selectPremiumInventoryQuantity(inventoryId))
                assertEquals(inventoryId, dataSource.selectActionConsumedInventoryId(actionId))

                // Rewind via the public service so the rewindable filter + refund SQL
                // both execute under the same transaction the production path uses.
                val rewind = service.rewind(viewer)

                assertTrue(rewind.rewound, "Pineapple Like must be rewindable")
                assertEquals(target.toString(), assertNotNull(rewind.card).userId)
                assertEquals(
                    1,
                    dataSource.selectPremiumInventoryQuantity(inventoryId),
                    "Rewind must increment the originally-consumed premium_inventory row",
                )
                assertTrue(deck.deckSessionId.isNotBlank())
            }
        }

        "top picks generates and reuses a daily session backed by a real deck session" {
            // SWIPE_IMPLEMENTATION_SPEC L308: stable picks within the 24h window.
            // Two reads return the same session id; the backing swipe_deck_sessions
            // row is queryable so submitAction tokens validate against it.
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000000950")
                val target = UUID.fromString("00000000-0000-0000-0000-000000000951")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(target, "Maya", age = 35, longitude = -122.0836, latitude = 37.4226)
                val swipeRepository = JdbcSwipeRepository(dataSource)
                val service =
                    com.pynapli.server.swipe.TopPicksService(
                        topPicksRepository =
                            com.pynapli.server.swipe
                                .JdbcTopPicksRepository(dataSource),
                        swipeRepository = swipeRepository,
                    )

                val first = service.getCurrentPicks(viewer)
                val second = service.getCurrentPicks(viewer)

                assertEquals(first.sessionId, second.sessionId)
                assertEquals(10, first.tierLimit)
                assertEquals(1, first.picks.size)
                assertEquals(
                    target.toString(),
                    first.picks
                        .single()
                        .card.userId,
                )
                // Same raw token returned on the cached read — proves deterministic
                // reissue against the persisted card_token_hash.
                assertEquals(
                    first.picks
                        .single()
                        .card.rankToken,
                    second.picks
                        .single()
                        .card.rankToken,
                )
            }
        }

        "top picks backing deck session remains actionable inside daily window" {
            runBlocking {
                val now = Instant.parse("2026-05-17T12:00:00Z")
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000001950")
                val target = UUID.fromString("00000000-0000-0000-0000-000000001951")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(target, "Maya", age = 35, longitude = -122.0836, latitude = 37.4226)
                val swipeRepository = JdbcSwipeRepository(dataSource)
                val topPicksService =
                    com.pynapli.server.swipe.TopPicksService(
                        topPicksRepository =
                            com.pynapli.server.swipe
                                .JdbcTopPicksRepository(dataSource),
                        swipeRepository = swipeRepository,
                        clock = Clock.fixed(now, ZoneOffset.UTC),
                    )

                val picks = topPicksService.getCurrentPicks(viewer)
                val pick = assertNotNull(picks.picks.singleOrNull())
                val laterSwipeService =
                    SwipeService(
                        repository = swipeRepository,
                        clock = Clock.fixed(now.plus(Duration.ofMinutes(31)), ZoneOffset.UTC),
                    )
                val response =
                    laterSwipeService.submitAction(
                        viewer,
                        SwipeActionRequestDto(
                            actionId = UUID.randomUUID().toString(),
                            deckSessionId = picks.deckSessionId,
                            cardToken = pick.card.rankToken,
                            targetUserId = target.toString(),
                            action = SwipeAction.LIKE,
                        ),
                    )

                assertTrue(picks.deckSessionId.isNotBlank())
                assertTrue(response.recorded)
            }
        }

        "grid and swipe discovery follows active travel_locations row when present" {
            // SWIPE_IMPLEMENTATION_SPEC L339-354: an active travel_locations row
            // overrides the viewer's GPS for the deck. Viewer physically in SF,
            // travel mode set to NYC — discovery returns the NYC profile (within
            // NYC radius) and excludes the SF-local profile.
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000000961")
                val sfLocal = UUID.fromString("00000000-0000-0000-0000-000000000962")
                val nycLocal = UUID.fromString("00000000-0000-0000-0000-000000000963")
                // Viewer is physically in SF.
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.4194, latitude = 37.7749)
                // SF-local target should disappear when travel mode is active to NYC.
                dataSource.insertDiscoverableUser(sfLocal, "Sage", age = 32, longitude = -122.4180, latitude = 37.7740)
                // NYC-local target should appear when travel mode is set to NYC.
                dataSource.insertDiscoverableUser(nycLocal, "Maya", age = 34, longitude = -74.0060, latitude = 40.7128)

                // Sanity: with no travel mode the SF target shows up, NYC does not (5km radius around SF).
                val swipeRepo = JdbcSwipeRepository(dataSource)
                val gridRepo = JdbcGridRepository(dataSource)
                val localFilters = DiscoveryFilters()
                val localSwipeBefore = swipeRepo.queryDeck(viewer, radiusMeters = 5_000, limit = 10, filters = localFilters)
                val localGridBefore = gridRepo.queryNearby(viewer, radiusMeters = 5_000, limit = 10, filters = localFilters)
                assertEquals(listOf(sfLocal), localSwipeBefore.cards.map { it.userId })
                assertEquals(listOf(sfLocal), localGridBefore.cards.map { it.userId })

                // Activate travel mode to NYC.
                dataSource.insertActiveTravelLocation(
                    userId = viewer,
                    city = "New York",
                    latitude = 40.7128,
                    longitude = -74.0060,
                )

                // Same 5km radius now centered on NYC — NYC target shows up, SF does not.
                val travelSwipe = swipeRepo.queryDeck(viewer, radiusMeters = 5_000, limit = 10, filters = localFilters)
                val travelGrid = gridRepo.queryNearby(viewer, radiusMeters = 5_000, limit = 10, filters = localFilters)
                assertEquals(listOf(nycLocal), travelSwipe.cards.map { it.userId })
                assertEquals(listOf(nycLocal), travelGrid.cards.map { it.userId })
            }
        }

        "visible_while_traveling=false hides travel-mode targets from every discovery surface" {
            // PROFILE_SETTINGS_SPEC L221-245: opt-out makes a travel-mode target
            // invisible to Grid AND Swipe AND Top Picks. Existing default true
            // keeps them visible (already covered by the prior visiting test).
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000000aa0")
                val hiddenVisitor = UUID.fromString("00000000-0000-0000-0000-000000000aa1")
                val openVisitor = UUID.fromString("00000000-0000-0000-0000-000000000aa2")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -74.0060, latitude = 40.7128)
                // Both targets physically in SF with active travel to NYC.
                dataSource.insertDiscoverableUser(hiddenVisitor, "Sage", age = 32, longitude = -122.4194, latitude = 37.7749)
                dataSource.insertDiscoverableUser(openVisitor, "Maya", age = 34, longitude = -122.4180, latitude = 37.7740)
                dataSource.insertActiveTravelLocation(hiddenVisitor, "New York", 40.7128, -74.0060)
                dataSource.insertActiveTravelLocation(openVisitor, "New York", 40.7128, -74.0060)
                // hiddenVisitor opts out of travel-mode visibility.
                dataSource.setVisibleWhileTraveling(hiddenVisitor, false)

                val swipeRepo = JdbcSwipeRepository(dataSource)
                val gridRepo = JdbcGridRepository(dataSource)
                val deck = swipeRepo.queryDeck(viewer, 5_000, 10, DiscoveryFilters())
                val grid = gridRepo.queryNearby(viewer, 5_000, 10, DiscoveryFilters())

                val deckIds = deck.cards.map { it.userId }
                val gridIds = grid.cards.map { it.userId }
                assertEquals(listOf(openVisitor), deckIds, "Opt-out target must be hidden from Swipe deck")
                assertEquals(listOf(openVisitor), gridIds, "Opt-out target must be hidden from Grid")
            }
        }

        "grid cards expose visiting flag and surface travel-mode targets in destination grids" {
            // SWIPE_IMPLEMENTATION_SPEC L348 + L344-348: Grid mirrors the Swipe
            // deck — a target physically in SF with travel mode set to NYC should
            // appear in a NYC viewer's grid AND be marked visiting.
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000000a00")
                val localTarget = UUID.fromString("00000000-0000-0000-0000-000000000a01")
                val visitingTarget = UUID.fromString("00000000-0000-0000-0000-000000000a02")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -74.0060, latitude = 40.7128)
                dataSource.insertDiscoverableUser(localTarget, "Sage", age = 32, longitude = -74.0055, latitude = 40.7130)
                dataSource.insertDiscoverableUser(visitingTarget, "Maya", age = 34, longitude = -122.4194, latitude = 37.7749)
                dataSource.insertActiveTravelLocation(
                    userId = visitingTarget,
                    city = "New York",
                    latitude = 40.7128,
                    longitude = -74.0060,
                )

                val gridRepo = JdbcGridRepository(dataSource)
                val result = gridRepo.queryNearby(viewer, radiusMeters = 5_000, limit = 10, filters = DiscoveryFilters())

                val byUser = result.cards.associateBy { it.userId }
                assertEquals(false, byUser.getValue(localTarget).visiting, "Local target must not be marked visiting")
                assertEquals(true, byUser.getValue(visitingTarget).visiting, "Travel-mode target must be marked visiting")
            }
        }

        "deck cards expose visiting flag when target has active travel_locations row" {
            // SWIPE_IMPLEMENTATION_SPEC L348: target with an active travel row is
            // marked "visiting" in the deck so the viewer knows they're virtually
            // in the area rather than physically.
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000000990")
                val localTarget = UUID.fromString("00000000-0000-0000-0000-000000000991")
                val visitingTarget = UUID.fromString("00000000-0000-0000-0000-000000000992")
                // Viewer + localTarget physically in NYC.
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -74.0060, latitude = 40.7128)
                dataSource.insertDiscoverableUser(localTarget, "Sage", age = 32, longitude = -74.0055, latitude = 40.7130)
                // visitingTarget physically in SF but virtually in NYC via travel.
                dataSource.insertDiscoverableUser(visitingTarget, "Maya", age = 34, longitude = -122.4194, latitude = 37.7749)
                dataSource.insertActiveTravelLocation(
                    userId = visitingTarget,
                    city = "New York",
                    latitude = 40.7128,
                    longitude = -74.0060,
                )

                val swipeRepo = JdbcSwipeRepository(dataSource)
                val deck = swipeRepo.queryDeck(viewer, radiusMeters = 5_000, limit = 10, filters = DiscoveryFilters())

                val byUser = deck.cards.associateBy { it.userId }
                assertEquals(false, byUser.getValue(localTarget).visiting, "Local target must not be marked visiting")
                assertEquals(true, byUser.getValue(visitingTarget).visiting, "Travel-mode target must be marked visiting")
            }
        }

        "travel-mode target dealt in destination deck remains actionable" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000000993")
                val visitingTarget = UUID.fromString("00000000-0000-0000-0000-000000000994")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -74.0060, latitude = 40.7128)
                dataSource.insertDiscoverableUser(visitingTarget, "Maya", age = 34, longitude = -122.4194, latitude = 37.7749)
                dataSource.insertActiveTravelLocation(
                    userId = visitingTarget,
                    city = "New York",
                    latitude = 40.7128,
                    longitude = -74.0060,
                )
                val service = SwipeService(JdbcSwipeRepository(dataSource))

                val deck = service.queryDeck(viewer, SwipeDeckRequestDto(radiusMeters = 5_000, limit = 5))
                val card = assertNotNull(deck.items.singleOrNull { it.userId == visitingTarget.toString() })
                val response =
                    service.submitAction(
                        viewer,
                        SwipeActionRequestDto(
                            actionId = UUID.randomUUID().toString(),
                            deckSessionId = deck.deckSessionId,
                            cardToken = card.rankToken,
                            targetUserId = visitingTarget.toString(),
                            action = SwipeAction.LIKE,
                        ),
                    )

                assertTrue(card.visiting)
                assertTrue(response.recorded)
            }
        }

        "travel-mode target liked in destination deck remains rewindable" {
            // Rewind must mirror the deck's target-side travel override: a Like on
            // a visiting card (target physically in SF, traveling to NYC) stays
            // rewindable even though the target's home is outside MAX_RADIUS.
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000000995")
                val visitingTarget = UUID.fromString("00000000-0000-0000-0000-000000000996")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -74.0060, latitude = 40.7128)
                dataSource.insertDiscoverableUser(visitingTarget, "Maya", age = 34, longitude = -122.4194, latitude = 37.7749)
                dataSource.insertActiveTravelLocation(
                    userId = visitingTarget,
                    city = "New York",
                    latitude = 40.7128,
                    longitude = -74.0060,
                )
                val service = SwipeService(JdbcSwipeRepository(dataSource))

                val deck = service.queryDeck(viewer, SwipeDeckRequestDto(radiusMeters = 5_000, limit = 5))
                val card = assertNotNull(deck.items.singleOrNull { it.userId == visitingTarget.toString() })
                service.submitAction(
                    viewer,
                    SwipeActionRequestDto(
                        actionId = UUID.randomUUID().toString(),
                        deckSessionId = deck.deckSessionId,
                        cardToken = card.rankToken,
                        targetUserId = visitingTarget.toString(),
                        action = SwipeAction.LIKE,
                    ),
                )

                val rewind = service.rewind(viewer)

                assertTrue(rewind.rewound, "Like on a visiting card must be rewindable")
                val rewoundCard = assertNotNull(rewind.card)
                assertEquals(visitingTarget.toString(), rewoundCard.userId)
                assertTrue(rewoundCard.visiting, "Rewound card must keep the visiting flag")
            }
        }

        "travel-mode Like still creates a match when reciprocated within the 24h tail" {
            // SWIPE_IMPLEMENTATION_SPEC L347, L353: a Like sent in travel mode
            // remains matchable while the session is active. Active session =
            // travel_locations.active_until IS NULL.
            runBlocking {
                val sender = UUID.fromString("00000000-0000-0000-0000-000000000970")
                val recipient = UUID.fromString("00000000-0000-0000-0000-000000000971")
                dataSource.insertDiscoverableUser(sender, "Sender", age = 30, longitude = -74.0060, latitude = 40.7128)
                dataSource.insertDiscoverableUser(recipient, "Maya", age = 31, longitude = -74.0055, latitude = 40.7130)
                val travelRowId =
                    dataSource.insertActiveTravelLocationReturningId(
                        userId = sender,
                        city = "New York",
                        latitude = 40.7128,
                        longitude = -74.0060,
                    )
                // Sender Likes recipient from inside the (still-active) travel session.
                dataSource.insertSwipeAction(
                    actorUserId = sender,
                    targetUserId = recipient,
                    action = "like",
                    travelLocationId = travelRowId,
                )

                // Recipient Likes back via the repo. Match SHOULD be created.
                val swipeRepo = JdbcSwipeRepository(dataSource)
                val service = SwipeService(swipeRepo)
                val deck = service.queryDeck(recipient, SwipeDeckRequestDto(limit = 5))
                val rankToken = deck.items.single { it.userId == sender.toString() }.rankToken
                val response =
                    service.submitAction(
                        recipient,
                        SwipeActionRequestDto(
                            actionId = UUID.randomUUID().toString(),
                            deckSessionId = deck.deckSessionId,
                            cardToken = rankToken,
                            targetUserId = sender.toString(),
                            action = SwipeAction.LIKE,
                        ),
                    )

                assertNotNull(response.match, "Active travel-mode Like must still create a match")
            }
        }

        "travel-mode Like becomes inert when reciprocated outside the 24h tail" {
            // Spec L353: after 24h post-leave the Like stops creating matches. We
            // simulate by inserting a cleared travel_locations row whose
            // active_until is older than 24h. The reciprocal Like should NOT match.
            runBlocking {
                val sender = UUID.fromString("00000000-0000-0000-0000-000000000980")
                val recipient = UUID.fromString("00000000-0000-0000-0000-000000000981")
                dataSource.insertDiscoverableUser(sender, "Sender", age = 30, longitude = -74.0060, latitude = 40.7128)
                dataSource.insertDiscoverableUser(recipient, "Maya", age = 31, longitude = -74.0055, latitude = 40.7130)
                val travelRowId =
                    dataSource.insertClearedTravelLocation(
                        userId = sender,
                        city = "New York",
                        latitude = 40.7128,
                        longitude = -74.0060,
                        clearedHoursAgo = 36,
                    )
                dataSource.insertSwipeAction(
                    actorUserId = sender,
                    targetUserId = recipient,
                    action = "like",
                    travelLocationId = travelRowId,
                )

                val swipeRepo = JdbcSwipeRepository(dataSource)
                val service = SwipeService(swipeRepo)
                val deck = service.queryDeck(recipient, SwipeDeckRequestDto(limit = 5))
                val rankToken = deck.items.single { it.userId == sender.toString() }.rankToken
                val response =
                    service.submitAction(
                        recipient,
                        SwipeActionRequestDto(
                            actionId = UUID.randomUUID().toString(),
                            deckSessionId = deck.deckSessionId,
                            cardToken = rankToken,
                            targetUserId = sender.toString(),
                            action = SwipeAction.LIKE,
                        ),
                    )

                assertEquals(null, response.match, "Travel-mode Like outside the 24h tail must NOT create a match")
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
                    relationshipContext = "linked",
                )
                dataSource.insertDiscoverableUser(
                    singleTarget,
                    "Alex",
                    age = 31,
                    longitude = -122.0838,
                    latitude = 37.4228,
                    relationshipContext = "single",
                )
                dataSource.insertDiscoverableUser(partner, "Noa", age = 35, longitude = -122.0851, latitude = 37.4235)
                dataSource.insertActiveProfileLink(userId = linkedTarget, linkedUserId = partner)
                val filters =
                    DiscoveryFilters(
                        minimumAge = 35,
                        relationshipContexts = setOf("linked"),
                        linkedOnly = true,
                    )

                val gridCards =
                    JdbcGridRepository(dataSource)
                        .queryNearby(
                            viewerUserId = viewer,
                            radiusMeters = 100_000,
                            limit = 10,
                            filters = filters,
                        ).cards
                val swipeCards =
                    JdbcSwipeRepository(dataSource)
                        .queryDeck(
                            viewerUserId = viewer,
                            radiusMeters = 100_000,
                            limit = 10,
                            filters = filters,
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

                val gridCards =
                    JdbcGridRepository(dataSource)
                        .queryNearby(
                            viewerUserId = viewer,
                            radiusMeters = 100_000,
                            limit = 10,
                        ).cards
                val swipeCards =
                    JdbcSwipeRepository(dataSource)
                        .queryDeck(
                            viewerUserId = viewer,
                            radiusMeters = 100_000,
                            limit = 10,
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
                val firstPage =
                    repository.queryNearby(
                        viewerUserId = viewer,
                        radiusMeters = 100_000,
                        limit = 2,
                    )
                val secondPage =
                    repository.queryNearby(
                        viewerUserId = viewer,
                        radiusMeters = 100_000,
                        limit = 2,
                        cursor = firstPage.nextCursorAnchor,
                    )

                assertEquals(listOf(firstTarget, secondTarget), firstPage.cards.map { it.userId })
                assertEquals(GridCursorAnchor(1, firstPage.cards.last().distanceBucketMeters, secondTarget), firstPage.nextCursorAnchor)
                assertEquals(listOf(thirdTarget), secondPage.cards.map { it.userId })
                assertEquals(null, secondPage.nextCursorAnchor)
            }
        }

        "grid service filters blocked users out of the cached candidate window" {
            // Safety: the cached candidate window is shared cross-viewer, so it is
            // intentionally EXCLUSION-FREE (no per-viewer block baked in). GridService
            // applies the per-viewer block filter post-repository, so a blocked user
            // can never reach the client even when present in the cached window.
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000000961")
                val blockedTarget = UUID.fromString("00000000-0000-0000-0000-000000000962")
                val visibleTarget = UUID.fromString("00000000-0000-0000-0000-000000000963")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(blockedTarget, "Blocked", age = 35, longitude = -122.0836, latitude = 37.4226)
                dataSource.insertDiscoverableUser(visibleTarget, "Visible", age = 36, longitude = -122.0840, latitude = 37.4230)
                dataSource.insertBlock(viewer, blockedTarget)

                val service =
                    GridService(
                        repository = JdbcGridRepository(dataSource),
                        blockRepository = JdbcBlockRepository(dataSource),
                        hiddenProfileRepository = JdbcHiddenProfileRepository(dataSource),
                    )
                val response =
                    service.queryGrid(
                        viewer,
                        GridQueryRequestDto(radiusMeters = 100_000, limit = 10),
                    )

                assertEquals(false, response.items.any { it.userId == blockedTarget.toString() }, "Blocked user must never reach the client")
                assertTrue(response.items.any { it.userId == visibleTarget.toString() })
            }
        }

        "grid query handles 5000 nearby candidates with stable cursor pages" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000000971")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDenseDiscoverableUsers(
                    count = 5_000,
                    baseLongitude = -122.0841,
                    baseLatitude = 37.4220,
                )
                dataSource.analyzeGridPlannerTables()

                val repository = JdbcGridRepository(dataSource)
                val startedAt = System.nanoTime()
                val firstPage =
                    repository.queryNearby(
                        viewerUserId = viewer,
                        radiusMeters = 100_000,
                        limit = 120,
                    )
                val secondPage =
                    repository.queryNearby(
                        viewerUserId = viewer,
                        radiusMeters = 100_000,
                        limit = 120,
                        cursor = assertNotNull(firstPage.nextCursorAnchor),
                    )
                val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
                println("Grid dense 5000 candidate two-page query completed in ${elapsedMillis}ms")

                assertEquals(120, firstPage.cards.size)
                assertEquals(120, secondPage.cards.size)
                assertTrue(
                    firstPage.cards
                        .map { it.userId }
                        .intersect(secondPage.cards.map { it.userId }.toSet())
                        .isEmpty(),
                )
                assertTrue(
                    elapsedMillis < 10_000,
                    "Dense grid query should remain comfortably under 10s in local Testcontainers; actual=${elapsedMillis}ms",
                )
            }
        }

        "grid query handles 100 concurrent filtered requests over 5000 nearby candidates" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000000972")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDenseDiscoverableUsers(
                    count = 5_000,
                    baseLongitude = -122.0841,
                    baseLatitude = 37.4220,
                )
                dataSource.analyzeGridPlannerTables()

                val repository = JdbcGridRepository(dataSource)
                val filterSet =
                    listOf(
                        DiscoveryFilters(),
                        DiscoveryFilters(relationshipContexts = setOf("single")),
                        DiscoveryFilters(relationshipContexts = setOf("linked")),
                        DiscoveryFilters(minimumAge = 35, maximumAge = 45),
                    )
                val timingsMillis =
                    coroutineScope {
                        (0 until 100)
                            .map { index ->
                                async(Dispatchers.IO) {
                                    val startedAt = System.nanoTime()
                                    val result =
                                        repository.queryNearby(
                                            viewerUserId = viewer,
                                            radiusMeters = 100_000,
                                            limit = 60,
                                            filters = filterSet[index % filterSet.size],
                                        )
                                    val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
                                    assertTrue(result.viewerHadLocation)
                                    assertTrue(result.cards.isNotEmpty())
                                    elapsedMillis
                                }
                            }.awaitAll()
                    }
                val p95Millis = timingsMillis.sorted()[94]
                val maxMillis = timingsMillis.maxOrNull() ?: 0
                println("Grid dense 5000 candidate 100-concurrent filtered p95=${p95Millis}ms max=${maxMillis}ms")

                assertTrue(
                    p95Millis < 5_000,
                    "Dense concurrent grid p95 should stay below the local regression ceiling; actual=${p95Millis}ms",
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
                val verifiedOnly =
                    repository
                        .queryNearby(
                            viewerUserId = viewer,
                            radiusMeters = 100_000,
                            limit = 10,
                            filters = DiscoveryFilters(verifiedOnly = true),
                        ).cards

                assertEquals(setOf(verifiedTarget, unverifiedTarget), unfiltered.map { it.userId }.toSet())
                assertEquals(listOf(verifiedTarget), verifiedOnly.map { it.userId })
            }
        }

        "grid query with intents filters to overlapping intent and excludes empty profiles" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
                val dating = UUID.fromString("00000000-0000-0000-0000-0000000000a2")
                val events = UUID.fromString("00000000-0000-0000-0000-0000000000a3")
                val noIntent = UUID.fromString("00000000-0000-0000-0000-0000000000a4")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(dating, "Dating", age = 30, longitude = -122.0836, latitude = 37.4226)
                dataSource.insertDiscoverableUser(events, "Events", age = 31, longitude = -122.0838, latitude = 37.4228)
                dataSource.insertDiscoverableUser(noIntent, "Plain", age = 32, longitude = -122.0830, latitude = 37.4230)
                dataSource.setProfileIntents(dating, listOf("dating", "open_to_singles"))
                dataSource.setProfileIntents(events, listOf("events"))

                val repository = JdbcGridRepository(dataSource)
                val datingOnly =
                    repository
                        .queryNearby(
                            viewerUserId = viewer,
                            radiusMeters = 100_000,
                            limit = 10,
                            filters = DiscoveryFilters(intents = setOf("dating")),
                        ).cards
                val openToCouples =
                    repository
                        .queryNearby(
                            viewerUserId = viewer,
                            radiusMeters = 100_000,
                            limit = 10,
                            filters = DiscoveryFilters(intents = setOf("open_to_couples")),
                        ).cards

                assertEquals(listOf(dating), datingOnly.map { it.userId })
                assertTrue(openToCouples.isEmpty(), "Untagged profile should not match an open_to_couples filter")
            }
        }

        "grid query with tonightAvailableOnly excludes profiles whose availability is null or expired" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000000f01")
                val activeTonight = UUID.fromString("00000000-0000-0000-0000-000000000f02")
                val expiredTonight = UUID.fromString("00000000-0000-0000-0000-000000000f03")
                val noTonight = UUID.fromString("00000000-0000-0000-0000-000000000f04")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(activeTonight, "Tonight", age = 30, longitude = -122.0836, latitude = 37.4226)
                dataSource.insertDiscoverableUser(expiredTonight, "Was", age = 31, longitude = -122.0838, latitude = 37.4228)
                dataSource.insertDiscoverableUser(noTonight, "Plain", age = 32, longitude = -122.0830, latitude = 37.4230)
                dataSource.setTonightAvailableUntil(activeTonight, hoursFromNow = 6)
                dataSource.setTonightAvailableUntil(expiredTonight, hoursFromNow = -1)

                val repository = JdbcGridRepository(dataSource)
                val tonightOnly =
                    repository
                        .queryNearby(
                            viewerUserId = viewer,
                            radiusMeters = 100_000,
                            limit = 10,
                            filters = DiscoveryFilters(tonightAvailableOnly = true),
                        ).cards
                val unfiltered = repository.queryNearby(viewer, radiusMeters = 100_000, limit = 10).cards

                assertEquals(listOf(activeTonight), tonightOnly.map { it.userId })
                assertEquals(setOf(activeTonight, expiredTonight, noTonight), unfiltered.map { it.userId }.toSet())
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
                val hostsOnly =
                    repository
                        .queryNearby(
                            viewerUserId = viewer,
                            radiusMeters = 100_000,
                            limit = 10,
                            filters = DiscoveryFilters(logisticsTags = setOf("hosts")),
                        ).cards
                val publicOnly =
                    repository
                        .queryNearby(
                            viewerUserId = viewer,
                            radiusMeters = 100_000,
                            limit = 10,
                            filters = DiscoveryFilters(logisticsTags = setOf("public_meet_first")),
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
                val softOnly =
                    repository
                        .queryNearby(
                            viewerUserId = viewer,
                            radiusMeters = 100_000,
                            limit = 10,
                            filters = DiscoveryFilters(lifestyleTags = setOf("soft_swap")),
                        ).cards
                val softOrFull =
                    repository
                        .queryNearby(
                            viewerUserId = viewer,
                            radiusMeters = 100_000,
                            limit = 10,
                            filters = DiscoveryFilters(lifestyleTags = setOf("soft_swap", "full_swap")),
                        ).cards
                val anyKink =
                    repository
                        .queryNearby(
                            viewerUserId = viewer,
                            radiusMeters = 100_000,
                            limit = 10,
                            filters = DiscoveryFilters(lifestyleTags = setOf("kink_friendly")),
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
                val soloOnly =
                    repository
                        .queryNearby(
                            viewerUserId = viewer,
                            radiusMeters = 100_000,
                            limit = 10,
                            filters = DiscoveryFilters(discoveryStyles = setOf("solo")),
                        ).cards
                val soloOrTogether =
                    repository
                        .queryNearby(
                            viewerUserId = viewer,
                            radiusMeters = 100_000,
                            limit = 10,
                            filters = DiscoveryFilters(discoveryStyles = setOf("solo", "together")),
                        ).cards

                assertEquals(listOf(solo), soloOnly.map { it.userId })
                assertEquals(setOf(solo, together), soloOrTogether.map { it.userId }.toSet())
            }
        }

        "grid query with genders hard-filters to matching gender" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000000e01")
                val woman = UUID.fromString("00000000-0000-0000-0000-000000000e02")
                val man = UUID.fromString("00000000-0000-0000-0000-000000000e03")
                val blank = UUID.fromString("00000000-0000-0000-0000-000000000e04")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(woman, "Wanda", age = 30, longitude = -122.0836, latitude = 37.4226)
                dataSource.insertDiscoverableUser(man, "Manny", age = 31, longitude = -122.0838, latitude = 37.4228)
                dataSource.insertDiscoverableUser(blank, "Blank", age = 32, longitude = -122.0830, latitude = 37.4230)
                dataSource.setProfileGender(woman, "woman")
                dataSource.setProfileGender(man, "man")

                val repository = JdbcGridRepository(dataSource)
                val womenOnly =
                    repository
                        .queryNearby(
                            viewerUserId = viewer,
                            radiusMeters = 100_000,
                            limit = 10,
                            filters = DiscoveryFilters(genders = setOf("woman")),
                        ).cards
                val womenOrMen =
                    repository
                        .queryNearby(
                            viewerUserId = viewer,
                            radiusMeters = 100_000,
                            limit = 10,
                            filters = DiscoveryFilters(genders = setOf("woman", "man")),
                        ).cards

                assertEquals(listOf(woman), womenOnly.map { it.userId })
                assertEquals(setOf(woman, man), womenOrMen.map { it.userId }.toSet())
            }
        }

        "grid query with orientations hard-filters to matching orientation" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000000f01")
                val pan = UUID.fromString("00000000-0000-0000-0000-000000000f02")
                val straight = UUID.fromString("00000000-0000-0000-0000-000000000f03")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(pan, "Pat", age = 30, longitude = -122.0836, latitude = 37.4226)
                dataSource.insertDiscoverableUser(straight, "Sam", age = 31, longitude = -122.0838, latitude = 37.4228)
                dataSource.setProfileOrientation(pan, "pansexual")
                dataSource.setProfileOrientation(straight, "straight")

                val repository = JdbcGridRepository(dataSource)
                val panOnly =
                    repository
                        .queryNearby(
                            viewerUserId = viewer,
                            radiusMeters = 100_000,
                            limit = 10,
                            filters = DiscoveryFilters(orientations = setOf("pansexual")),
                        ).cards

                assertEquals(listOf(pan), panOnly.map { it.userId })
            }
        }

        "swipe deck with genders hard-filters to matching gender" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000000e11")
                val woman = UUID.fromString("00000000-0000-0000-0000-000000000e12")
                val man = UUID.fromString("00000000-0000-0000-0000-000000000e13")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(woman, "Wanda", age = 30, longitude = -122.0836, latitude = 37.4226)
                dataSource.insertDiscoverableUser(man, "Manny", age = 31, longitude = -122.0838, latitude = 37.4228)
                dataSource.setProfileGender(woman, "woman")
                dataSource.setProfileGender(man, "man")

                val womenOnly =
                    JdbcSwipeRepository(dataSource)
                        .queryDeck(
                            viewerUserId = viewer,
                            radiusMeters = 100_000,
                            limit = 10,
                            filters = DiscoveryFilters(genders = setOf("woman")),
                        ).cards

                assertEquals(listOf(woman), womenOnly.map { it.userId })
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
                val faceFiltered =
                    repository
                        .queryNearby(
                            viewerUserId = viewer,
                            radiusMeters = 100_000,
                            limit = 10,
                            filters = DiscoveryFilters(facePhotoVerifiedOnly = true),
                        ).cards
                val ageFiltered =
                    repository
                        .queryNearby(
                            viewerUserId = viewer,
                            radiusMeters = 100_000,
                            limit = 10,
                            filters = DiscoveryFilters(ageVerifiedOnly = true),
                        ).cards

                assertEquals(listOf(faceOnly), faceFiltered.map { it.userId })
                assertEquals(listOf(ageOnly), ageFiltered.map { it.userId })
            }
        }

        "swipe deck verifiedOnly excludes unverified profiles" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000000b01")
                val verifiedTarget = UUID.fromString("00000000-0000-0000-0000-000000000b02")
                val unverifiedTarget = UUID.fromString("00000000-0000-0000-0000-000000000b03")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(verifiedTarget, "Vera", age = 35, longitude = -122.0836, latitude = 37.4226)
                dataSource.insertDiscoverableUser(unverifiedTarget, "Una", age = 33, longitude = -122.0838, latitude = 37.4228)
                dataSource.markProfileVerified(verifiedTarget)

                val repository = JdbcSwipeRepository(dataSource)
                val unfiltered = repository.queryDeck(viewer, radiusMeters = 100_000, limit = 10).cards
                val verifiedOnly =
                    repository
                        .queryDeck(
                            viewerUserId = viewer,
                            radiusMeters = 100_000,
                            limit = 10,
                            filters = DiscoveryFilters(verifiedOnly = true),
                        ).cards

                assertEquals(setOf(verifiedTarget, unverifiedTarget), unfiltered.map { it.userId }.toSet())
                assertEquals(listOf(verifiedTarget), verifiedOnly.map { it.userId })
            }
        }

        "swipe deck facePhotoVerifiedOnly and ageVerifiedOnly each filter to their column" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000000b11")
                val facePhotoTarget = UUID.fromString("00000000-0000-0000-0000-000000000b12")
                val ageTarget = UUID.fromString("00000000-0000-0000-0000-000000000b13")
                val plainTarget = UUID.fromString("00000000-0000-0000-0000-000000000b14")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(facePhotoTarget, "Face", age = 30, longitude = -122.0836, latitude = 37.4226)
                dataSource.insertDiscoverableUser(ageTarget, "Aged", age = 31, longitude = -122.0838, latitude = 37.4228)
                dataSource.insertDiscoverableUser(plainTarget, "Plain", age = 32, longitude = -122.0830, latitude = 37.4230)
                dataSource.markFacePhotoVerified(facePhotoTarget)
                dataSource.markAgeVerified(ageTarget)

                val repository = JdbcSwipeRepository(dataSource)
                val facePhotoOnly =
                    repository
                        .queryDeck(
                            viewerUserId = viewer,
                            radiusMeters = 100_000,
                            limit = 10,
                            filters = DiscoveryFilters(facePhotoVerifiedOnly = true),
                        ).cards
                val ageVerifiedOnly =
                    repository
                        .queryDeck(
                            viewerUserId = viewer,
                            radiusMeters = 100_000,
                            limit = 10,
                            filters = DiscoveryFilters(ageVerifiedOnly = true),
                        ).cards

                assertEquals(listOf(facePhotoTarget), facePhotoOnly.map { it.userId })
                assertEquals(listOf(ageTarget), ageVerifiedOnly.map { it.userId })
            }
        }

        "swipe deck discoveryStyles filter to matching style" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000000b21")
                val soloTarget = UUID.fromString("00000000-0000-0000-0000-000000000b22")
                val togetherTarget = UUID.fromString("00000000-0000-0000-0000-000000000b23")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(soloTarget, "Solo", age = 30, longitude = -122.0836, latitude = 37.4226)
                dataSource.insertDiscoverableUser(togetherTarget, "Together", age = 31, longitude = -122.0838, latitude = 37.4228)
                dataSource.setProfileDiscoveryStyle(soloTarget, "solo")
                dataSource.setProfileDiscoveryStyle(togetherTarget, "together")

                val repository = JdbcSwipeRepository(dataSource)
                val soloOnly =
                    repository
                        .queryDeck(
                            viewerUserId = viewer,
                            radiusMeters = 100_000,
                            limit = 10,
                            filters = DiscoveryFilters(discoveryStyles = setOf("solo")),
                        ).cards

                assertEquals(listOf(soloTarget), soloOnly.map { it.userId })
            }
        }

        "swipe deck intents filters to overlapping intent and excludes empty profiles" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000000b31")
                val dating = UUID.fromString("00000000-0000-0000-0000-000000000b32")
                val events = UUID.fromString("00000000-0000-0000-0000-000000000b33")
                val noIntent = UUID.fromString("00000000-0000-0000-0000-000000000b34")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(dating, "Dating", age = 30, longitude = -122.0836, latitude = 37.4226)
                dataSource.insertDiscoverableUser(events, "Events", age = 31, longitude = -122.0838, latitude = 37.4228)
                dataSource.insertDiscoverableUser(noIntent, "Plain", age = 32, longitude = -122.0830, latitude = 37.4230)
                dataSource.setProfileIntents(dating, listOf("dating", "open_to_singles"))
                dataSource.setProfileIntents(events, listOf("events"))

                val repository = JdbcSwipeRepository(dataSource)
                val datingOnly =
                    repository
                        .queryDeck(
                            viewerUserId = viewer,
                            radiusMeters = 100_000,
                            limit = 10,
                            filters = DiscoveryFilters(intents = setOf("dating")),
                        ).cards
                val openToCouples =
                    repository
                        .queryDeck(
                            viewerUserId = viewer,
                            radiusMeters = 100_000,
                            limit = 10,
                            filters = DiscoveryFilters(intents = setOf("open_to_couples")),
                        ).cards

                assertEquals(listOf(dating), datingOnly.map { it.userId })
                assertTrue(openToCouples.isEmpty(), "Untagged profile should not match an open_to_couples filter")
            }
        }

        "swipe deck lifestyleTags filters to overlapping tag and excludes untagged profiles" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000000b41")
                val softSwap = UUID.fromString("00000000-0000-0000-0000-000000000b42")
                val sameRoom = UUID.fromString("00000000-0000-0000-0000-000000000b43")
                val untagged = UUID.fromString("00000000-0000-0000-0000-000000000b44")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(softSwap, "Soft", age = 30, longitude = -122.0836, latitude = 37.4226)
                dataSource.insertDiscoverableUser(sameRoom, "Same", age = 31, longitude = -122.0838, latitude = 37.4228)
                dataSource.insertDiscoverableUser(untagged, "Plain", age = 32, longitude = -122.0830, latitude = 37.4230)
                dataSource.setProfileLifestyleTags(softSwap, listOf("soft_swap"))
                dataSource.setProfileLifestyleTags(sameRoom, listOf("same_room"))

                val repository = JdbcSwipeRepository(dataSource)
                val softSwapOnly =
                    repository
                        .queryDeck(
                            viewerUserId = viewer,
                            radiusMeters = 100_000,
                            limit = 10,
                            filters = DiscoveryFilters(lifestyleTags = setOf("soft_swap")),
                        ).cards

                assertEquals(listOf(softSwap), softSwapOnly.map { it.userId })
            }
        }

        "swipe deck verifiedPreferred ranks verified profiles above unverified within the same distance band" {
            // Two candidates at the same distance from the viewer. One verified, one not.
            // With verifiedPreferred=true, the verified target should rank first.
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000000c01")
                val verifiedTarget = UUID.fromString("00000000-0000-0000-0000-000000000c02")
                val unverifiedTarget = UUID.fromString("00000000-0000-0000-0000-000000000c03")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                // Place verified slightly farther so distance alone would put unverified first.
                dataSource.insertDiscoverableUser(verifiedTarget, "Vera", age = 30, longitude = -122.0830, latitude = 37.4232)
                dataSource.insertDiscoverableUser(unverifiedTarget, "Una", age = 30, longitude = -122.0838, latitude = 37.4228)
                dataSource.markProfileVerified(verifiedTarget)

                val repository = JdbcSwipeRepository(dataSource)

                // Without the preference: nearest (unverified) ranks first.
                val unboosted = repository.queryDeck(viewer, radiusMeters = 100_000, limit = 10).cards
                assertEquals(listOf(unverifiedTarget, verifiedTarget), unboosted.map { it.userId })

                // With verifiedPreferred=true: verified outranks unverified despite being farther.
                val preferred =
                    repository
                        .queryDeck(
                            viewerUserId = viewer,
                            radiusMeters = 100_000,
                            limit = 10,
                            filters = DiscoveryFilters(verifiedPreferred = true),
                        ).cards
                assertEquals(listOf(verifiedTarget, unverifiedTarget), preferred.map { it.userId })
            }
        }

        "swipe deck logisticsTags filters to overlapping tag and excludes untagged profiles" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000000b51")
                val hosts = UUID.fromString("00000000-0000-0000-0000-000000000b52")
                val travels = UUID.fromString("00000000-0000-0000-0000-000000000b53")
                val untagged = UUID.fromString("00000000-0000-0000-0000-000000000b54")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(hosts, "Hosts", age = 30, longitude = -122.0836, latitude = 37.4226)
                dataSource.insertDiscoverableUser(travels, "Travels", age = 31, longitude = -122.0838, latitude = 37.4228)
                dataSource.insertDiscoverableUser(untagged, "Plain", age = 32, longitude = -122.0830, latitude = 37.4230)
                dataSource.setProfileLogisticsTags(hosts, listOf("hosts"))
                dataSource.setProfileLogisticsTags(travels, listOf("travels"))

                val repository = JdbcSwipeRepository(dataSource)
                val hostsOnly =
                    repository
                        .queryDeck(
                            viewerUserId = viewer,
                            radiusMeters = 100_000,
                            limit = 10,
                            filters = DiscoveryFilters(logisticsTags = setOf("hosts")),
                        ).cards

                assertEquals(listOf(hosts), hostsOnly.map { it.userId })
            }
        }

        "grid rank cache serves a second identical query without re-running the rank SQL" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000003001")
                val target = UUID.fromString("00000000-0000-0000-0000-000000003002")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(target, "Maya", age = 35, longitude = -122.0836, latitude = 37.4226)
                val counting = RankCountingDataSource(dataSource)
                val repository = JdbcGridRepository(counting)

                val first = repository.queryNearby(viewer, radiusMeters = 100_000, limit = 10)
                val rankRunsAfterFirst = counting.rankRunCount()
                val second = repository.queryNearby(viewer, radiusMeters = 100_000, limit = 10)
                val rankRunsAfterSecond = counting.rankRunCount()

                assertEquals(listOf(target), first.cards.map { it.userId })
                assertEquals(listOf(target), second.cards.map { it.userId })
                assertEquals(1, rankRunsAfterFirst, "First query must run the rank SQL exactly once")
                assertEquals(
                    1,
                    rankRunsAfterSecond,
                    "Second identical query within TTL must be served from cache without re-running the rank SQL",
                )
            }
        }

        "grid rank cache window is shared across viewers in the same bucket and filter" {
            runBlocking {
                val viewerA = UUID.fromString("00000000-0000-0000-0000-000000003011")
                val viewerB = UUID.fromString("00000000-0000-0000-0000-000000003012")
                val target = UUID.fromString("00000000-0000-0000-0000-000000003013")
                // Both viewers at the same coarse (E3 ~111m) bucket center.
                dataSource.insertDiscoverableUser(viewerA, "A", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(viewerB, "B", age = 39, longitude = -122.08409, latitude = 37.42201)
                dataSource.insertDiscoverableUser(target, "Maya", age = 35, longitude = -122.0836, latitude = 37.4226)
                val counting = RankCountingDataSource(dataSource)
                val repository = JdbcGridRepository(counting)

                val a = repository.queryNearby(viewerA, radiusMeters = 100_000, limit = 10)
                val b = repository.queryNearby(viewerB, radiusMeters = 100_000, limit = 10)

                // Each viewer sees everyone in the shared window except themselves.
                assertEquals(setOf(viewerB, target), a.cards.map { it.userId }.toSet())
                assertEquals(setOf(viewerA, target), b.cards.map { it.userId }.toSet())
                assertEquals(
                    1,
                    counting.rankRunCount(),
                    "Two viewers in the same bucket + filter must share one cached rank window",
                )
            }
        }

        "grid rank cache never returns the viewer themselves even when present in the shared window" {
            runBlocking {
                val viewerA = UUID.fromString("00000000-0000-0000-0000-000000003021")
                val viewerB = UUID.fromString("00000000-0000-0000-0000-000000003022")
                // Same bucket so viewerB's query reuses a window that contains viewerB.
                dataSource.insertDiscoverableUser(viewerA, "A", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(viewerB, "B", age = 39, longitude = -122.08409, latitude = 37.42201)
                val repository = JdbcGridRepository(dataSource)

                // viewerA populates the shared window (window contains both A and B,
                // minus A's self row).
                val a = repository.queryNearby(viewerA, radiusMeters = 100_000, limit = 10)
                val b = repository.queryNearby(viewerB, radiusMeters = 100_000, limit = 10)

                assertTrue(a.cards.none { it.userId == viewerA }, "Viewer A must never see themselves")
                assertTrue(b.cards.none { it.userId == viewerB }, "Viewer B must never see themselves from the shared window")
                assertEquals(listOf(viewerA), b.cards.map { it.userId })
            }
        }

        "grid cursor pagination over the cached window matches the uncached ordering" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000003031")
                val first = UUID.fromString("00000000-0000-0000-0000-000000003032")
                val second = UUID.fromString("00000000-0000-0000-0000-000000003033")
                val third = UUID.fromString("00000000-0000-0000-0000-000000003034")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(first, "First", age = 35, longitude = -122.0836, latitude = 37.4226)
                dataSource.insertDiscoverableUser(second, "Second", age = 36, longitude = -122.0838, latitude = 37.4228)
                dataSource.insertDiscoverableUser(third, "Third", age = 37, longitude = -122.0840, latitude = 37.4230)

                // Uncached reference ordering (linkedOnly bypasses the cache but
                // returns the same candidate set since all three are non-linked and
                // we filter linkedOnly=false). Use a fresh repo with cache disabled.
                val cachedRepo = JdbcGridRepository(dataSource)
                val uncachedRepo = JdbcGridRepository(dataSource, rankCacheTtl = Duration.ZERO)

                val cachedFirst = cachedRepo.queryNearby(viewer, radiusMeters = 100_000, limit = 2)
                val cachedSecond =
                    cachedRepo.queryNearby(
                        viewer,
                        radiusMeters = 100_000,
                        limit = 2,
                        cursor = assertNotNull(cachedFirst.nextCursorAnchor),
                    )
                val uncachedFirst = uncachedRepo.queryNearby(viewer, radiusMeters = 100_000, limit = 2)
                val uncachedSecond =
                    uncachedRepo.queryNearby(
                        viewer,
                        radiusMeters = 100_000,
                        limit = 2,
                        cursor = assertNotNull(uncachedFirst.nextCursorAnchor),
                    )

                assertEquals(listOf(first, second), cachedFirst.cards.map { it.userId })
                assertEquals(listOf(third), cachedSecond.cards.map { it.userId })
                assertEquals(
                    uncachedFirst.cards.map { it.userId },
                    cachedFirst.cards.map { it.userId },
                    "Cached page 1 must match uncached ordering",
                )
                assertEquals(
                    uncachedSecond.cards.map { it.userId },
                    cachedSecond.cards.map { it.userId },
                    "Cached page 2 must match uncached ordering",
                )
                assertEquals(null, cachedSecond.nextCursorAnchor)
            }
        }

        "grid linkedOnly filter bypasses the cache and returns only linked profiles" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000003041")
                val linkedTarget = UUID.fromString("00000000-0000-0000-0000-000000003042")
                val singleTarget = UUID.fromString("00000000-0000-0000-0000-000000003043")
                val partner = UUID.fromString("00000000-0000-0000-0000-000000003044")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(linkedTarget, "Maya", age = 36, longitude = -122.0836, latitude = 37.4226)
                dataSource.insertDiscoverableUser(singleTarget, "Alex", age = 31, longitude = -122.0838, latitude = 37.4228)
                dataSource.insertDiscoverableUser(partner, "Noa", age = 35, longitude = -122.0851, latitude = 37.4235)
                dataSource.insertActiveProfileLink(userId = linkedTarget, linkedUserId = partner)
                val counting = RankCountingDataSource(dataSource)
                val repository = JdbcGridRepository(counting)

                val linkedOnly =
                    repository
                        .queryNearby(
                            viewer,
                            radiusMeters = 100_000,
                            limit = 10,
                            filters = DiscoveryFilters(linkedOnly = true),
                        ).cards
                // A second linkedOnly query must re-run the rank SQL (cache bypassed).
                repository.queryNearby(
                    viewer,
                    radiusMeters = 100_000,
                    limit = 10,
                    filters = DiscoveryFilters(linkedOnly = true),
                )

                assertEquals(listOf(linkedTarget), linkedOnly.map { it.userId })
                assertEquals(2, counting.rankRunCount(), "linkedOnly must bypass the shared cache on every call")
            }
        }

        "grid travel-active viewer bypasses the cache and follows the travel location" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000003051")
                val sfLocal = UUID.fromString("00000000-0000-0000-0000-000000003052")
                val nycLocal = UUID.fromString("00000000-0000-0000-0000-000000003053")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.4194, latitude = 37.7749)
                dataSource.insertDiscoverableUser(sfLocal, "Sage", age = 32, longitude = -122.4180, latitude = 37.7740)
                dataSource.insertDiscoverableUser(nycLocal, "Maya", age = 34, longitude = -74.0060, latitude = 40.7128)
                dataSource.insertActiveTravelLocation(viewer, "New York", 40.7128, -74.0060)
                val counting = RankCountingDataSource(dataSource)
                val repository = JdbcGridRepository(counting)

                val first = repository.queryNearby(viewer, radiusMeters = 5_000, limit = 10)
                val second = repository.queryNearby(viewer, radiusMeters = 5_000, limit = 10)

                assertEquals(listOf(nycLocal), first.cards.map { it.userId })
                assertEquals(listOf(nycLocal), second.cards.map { it.userId })
                assertEquals(
                    2,
                    counting.rankRunCount(),
                    "Travel-active viewer must bypass the cross-viewer cache (bucket is viewer-specific)",
                )
            }
        }

        "grid cached results are byte-identical to the uncached query for a representative case" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000003061")
                val target = UUID.fromString("00000000-0000-0000-0000-000000003062")
                val partner = UUID.fromString("00000000-0000-0000-0000-000000003063")
                val boosted = UUID.fromString("00000000-0000-0000-0000-000000003064")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(target, "Maya", age = 35, longitude = -122.0836, latitude = 37.4226)
                dataSource.insertDiscoverableUser(partner, "Alex", age = 39, longitude = -122.0851, latitude = 37.4235)
                dataSource.insertDiscoverableUser(boosted, "Boosted", age = 36, longitude = -122.0900, latitude = 37.4280)
                dataSource.insertActiveProfileLink(userId = target, linkedUserId = partner)
                dataSource.insertBoostActivation(boosted)

                val cachedRepo = JdbcGridRepository(dataSource)
                val uncachedRepo = JdbcGridRepository(dataSource, rankCacheTtl = Duration.ZERO)

                val cached = cachedRepo.queryNearby(viewer, radiusMeters = 100_000, limit = 10)
                val uncached = uncachedRepo.queryNearby(viewer, radiusMeters = 100_000, limit = 10)

                assertEquals(uncached.cards, cached.cards, "Cached cards must be byte-identical to the uncached query")
                assertEquals(uncached.nextCursorAnchor, cached.nextCursorAnchor)
            }
        }

        "grid redis candidate cache shares the ranked window across separate repository instances" {
            // Headline of the Redis upgrade: two server instances (here, two repos
            // with independent in-process state and their own DB connections) share
            // ONE ranked window via Redis, so the expensive rank SQL runs once
            // fleet-wide for the same coarse bucket + filter — what an in-memory
            // per-instance cache cannot do.
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000003071")
                val target = UUID.fromString("00000000-0000-0000-0000-000000003072")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(target, "Maya", age = 35, longitude = -122.0836, latitude = 37.4226)

                val cache = redisCache()
                try {
                    val countingA = RankCountingDataSource(dataSource)
                    val countingB = RankCountingDataSource(dataSource)
                    val instanceA = JdbcGridRepository(countingA, candidateRankCache = RedisGridCandidateRankCache(cache, Duration.ofSeconds(30)))
                    val instanceB = JdbcGridRepository(countingB, candidateRankCache = RedisGridCandidateRankCache(cache, Duration.ofSeconds(30)))

                    val a = instanceA.queryNearby(viewer, radiusMeters = 100_000, limit = 10)
                    val b = instanceB.queryNearby(viewer, radiusMeters = 100_000, limit = 10)

                    assertEquals(listOf(target), a.cards.map { it.userId })
                    assertEquals(listOf(target), b.cards.map { it.userId })
                    assertEquals(1, countingA.rankRunCount(), "Instance A must populate the shared Redis window once")
                    assertEquals(
                        0,
                        countingB.rankRunCount(),
                        "Instance B must serve from the Redis window A populated, without re-running the rank SQL",
                    )
                } finally {
                    cache.close()
                }
            }
        }

        "grid redis candidate cache fails open to direct queries when redis is unavailable" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000003081")
                val target = UUID.fromString("00000000-0000-0000-0000-000000003082")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(target, "Maya", age = 35, longitude = -122.0836, latitude = 37.4226)

                val cache = redisCache()
                cache.close() // simulate a Redis outage: every command now throws.
                val repository = JdbcGridRepository(dataSource, candidateRankCache = RedisGridCandidateRankCache(cache, Duration.ofSeconds(30)))

                val result = repository.queryNearby(viewer, radiusMeters = 100_000, limit = 10)

                assertEquals(listOf(target), result.cards.map { it.userId }, "A Redis outage must degrade to direct queries, not break the Grid")
            }
        }

        "grid redis window codec round-trips ranked rows" {
            val rows =
                listOf(
                    GridRankRow(
                        userId = UUID.fromString("00000000-0000-0000-0000-0000000030a1"),
                        displayName = "Maya | with: delimiters \"and\" quotes",
                        showDistance = true,
                        boostBucket = 0,
                        distanceBucketMeters = 1234,
                        distanceMeters = 1234.56,
                        visiting = false,
                    ),
                    GridRankRow(
                        userId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),
                        displayName = "Pynapli User",
                        showDistance = false,
                        boostBucket = 1,
                        distanceBucketMeters = 0,
                        distanceMeters = 0.0,
                        visiting = true,
                    ),
                )

            val decoded = GridRankWindowCodec.decode(GridRankWindowCodec.encode(rows))

            assertEquals(rows, decoded, "Codec must round-trip the ranked window exactly (incl. unsigned-high-bit UUID and delimiter-laden names)")
        }

        "grid card surfaces tonightActive when the candidate has a live Playing Tonight session" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-0000000040a1")
                val playingTonight = UUID.fromString("00000000-0000-0000-0000-0000000040a2")
                val notTonight = UUID.fromString("00000000-0000-0000-0000-0000000040a3")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(playingTonight, "Maya", age = 35, longitude = -122.0836, latitude = 37.4226)
                dataSource.insertDiscoverableUser(notTonight, "Alex", age = 36, longitude = -122.0838, latitude = 37.4228)
                dataSource.insertActiveTonightSession(playingTonight)

                val cards =
                    JdbcGridRepository(dataSource, rankCacheTtl = Duration.ZERO)
                        .queryNearby(viewer, radiusMeters = 100_000, limit = 10)
                        .cards

                val byId = cards.associateBy { it.userId }
                assertEquals(true, byId[playingTonight]?.tonightActive, "Session owner must show tonightActive")
                assertEquals(false, byId[notTonight]?.tonightActive, "Non-session candidate must not show tonightActive")
            }
        }

        "grid tonightAvailableOnly filter returns only individuals with a live session" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-0000000040b1")
                val playingTonight = UUID.fromString("00000000-0000-0000-0000-0000000040b2")
                val notTonight = UUID.fromString("00000000-0000-0000-0000-0000000040b3")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(playingTonight, "Maya", age = 35, longitude = -122.0836, latitude = 37.4226)
                dataSource.insertDiscoverableUser(notTonight, "Alex", age = 36, longitude = -122.0838, latitude = 37.4228)
                dataSource.insertActiveTonightSession(playingTonight)

                val cards =
                    JdbcGridRepository(dataSource, rankCacheTtl = Duration.ZERO)
                        .queryNearby(
                            viewer,
                            radiusMeters = 100_000,
                            limit = 10,
                            filters = DiscoveryFilters(tonightAvailableOnly = true),
                        ).cards

                assertEquals(listOf(playingTonight), cards.map { it.userId })
            }
        }
    })

private class RankCountingDataSource(
    private val delegate: DataSource,
) : DataSource by delegate {
    private val rankRuns =
        java.util.concurrent.atomic
            .AtomicInteger(0)

    fun rankRunCount(): Int = rankRuns.get()

    override fun getConnection(): java.sql.Connection = RankCountingConnection(delegate.connection, rankRuns)

    override fun getConnection(
        username: String?,
        password: String?,
    ): java.sql.Connection = RankCountingConnection(delegate.getConnection(username, password), rankRuns)
}

private class RankCountingConnection(
    private val delegate: java.sql.Connection,
    private val rankRuns: java.util.concurrent.atomic.AtomicInteger,
) : java.sql.Connection by delegate {
    override fun prepareStatement(sql: String): java.sql.PreparedStatement {
        // The exclusion-free rank window query is the only one keyed off the
        // base_candidates CTE; counting it proves whether the rank SQL re-ran.
        if (sql.contains("base_candidates")) {
            rankRuns.incrementAndGet()
        }
        return delegate.prepareStatement(sql)
    }
}

private fun DataSource.insertDiscoverableUser(
    userId: UUID,
    displayName: String,
    age: Int,
    longitude: Double,
    latitude: Double,
    relationshipContext: String = "single",
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
                INSERT INTO profiles (
                    user_id,
                    display_name,
                    display_age,
                    relationship_context,
                    moderation_status,
                    visibility_status
                )
                VALUES (?, ?, ?, ?, 'approved', 'visible')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setString(2, displayName)
                statement.setInt(3, age)
                statement.setString(4, relationshipContext)
                statement.executeUpdate()
            }
        connection
            .prepareStatement(
                """
                INSERT INTO discovery_settings (
                    user_id,
                    grid_visible,
                    swipe_visible,
                    incognito,
                    show_distance
                )
                VALUES (?, true, true, false, true)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeUpdate()
            }
        connection
            .prepareStatement(
                """
                INSERT INTO user_locations (
                    user_id,
                    geom,
                    location_source,
                    accuracy_meters
                )
                VALUES (?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, 'gps', 12)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setDouble(2, longitude)
                statement.setDouble(3, latitude)
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

private fun DataSource.markProfileVerified(userId: UUID) = setProfileTimestamp(userId, column = "verified_at")

private fun DataSource.markFacePhotoVerified(userId: UUID) = setProfileTimestamp(userId, column = "face_photo_verified_at")

private fun DataSource.markAgeVerified(userId: UUID) = setProfileTimestamp(userId, column = "age_verified_at")

private fun DataSource.setProfileDiscoveryStyle(
    userId: UUID,
    style: String,
) {
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

private fun DataSource.setProfileGender(
    userId: UUID,
    gender: String,
) = setProfileTextColumn(userId, column = "gender", value = gender)

private fun DataSource.setProfileOrientation(
    userId: UUID,
    orientation: String,
) = setProfileTextColumn(userId, column = "orientation", value = orientation)

private fun DataSource.setProfileTextColumn(
    userId: UUID,
    column: String,
    value: String,
) {
    require(column in setOf("gender", "orientation")) { "Unexpected profile column: $column" }
    connection.use { connection ->
        connection.prepareStatement("UPDATE profiles SET $column = ? WHERE user_id = ?").use { statement ->
            statement.setString(1, value)
            statement.setObject(2, userId)
            statement.executeUpdate()
        }
    }
}

private fun DataSource.setProfileLifestyleTags(
    userId: UUID,
    tags: List<String>,
) = setProfileTagArray(userId, column = "lifestyle_tags", tags = tags)

private fun DataSource.setProfileLogisticsTags(
    userId: UUID,
    tags: List<String>,
) = setProfileTagArray(userId, column = "logistics_tags", tags = tags)

private fun DataSource.setProfileIntents(
    userId: UUID,
    tags: List<String>,
) = setProfileTagArray(userId, column = "intents", tags = tags)

private fun DataSource.setTonightAvailableUntil(
    userId: UUID,
    hoursFromNow: Long,
) {
    connection.use { connection ->
        connection
            .prepareStatement(
                "UPDATE profiles SET tonight_available_until = now() + (? || ' hours')::interval WHERE user_id = ?",
            ).use { statement ->
                statement.setString(1, hoursFromNow.toString())
                statement.setObject(2, userId)
                statement.executeUpdate()
            }
    }
}

private fun DataSource.setProfileTagArray(
    userId: UUID,
    column: String,
    tags: List<String>,
) {
    require(column in setOf("lifestyle_tags", "logistics_tags", "intents")) {
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

private fun DataSource.setProfileTimestamp(
    userId: UUID,
    column: String,
) {
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

private fun DataSource.insertMatch(
    userA: UUID,
    userB: UUID,
) {
    val (a, b) = canonicalPair(userA, userB)
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO matches (id, user_a, user_b)
                VALUES (?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, a)
                statement.setObject(3, b)
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

private fun DataSource.insertBoostActivation(userId: UUID) {
    connection.use { connection ->
        connection
            .prepareStatement(
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
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, userId)
                statement.executeUpdate()
            }
    }
}

/** Seeds a live solo Playing Tonight session (active, owner-approved) for the user. */
private fun DataSource.insertActiveTonightSession(userId: UUID) {
    val sessionId = UUID.randomUUID()
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO tonight_sessions (
                    id, created_by_user_id, mode, intent, host_mode, map_visible,
                    status, created_at, started_at, expires_at
                )
                VALUES (?, ?, 'solo', 'solo', 'public_meet_first', true,
                        'active', now() - interval '1 minute', now() - interval '1 minute', now() + interval '2 hours')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, sessionId)
                statement.setObject(2, userId)
                statement.executeUpdate()
            }
        connection
            .prepareStatement(
                """
                INSERT INTO tonight_session_participants (session_id, user_id, role, approval_status, approved_at)
                VALUES (?, ?, 'owner', 'approved', now() - interval '1 minute')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, sessionId)
                statement.setObject(2, userId)
                statement.executeUpdate()
            }
    }
}

private fun DataSource.insertDenseDiscoverableUsers(
    count: Int,
    baseLongitude: Double,
    baseLatitude: Double,
) {
    val users =
        List(count) { index ->
            DenseDiscoverableUser(
                id = UUID.nameUUIDFromBytes("dense-grid-user-$index".toByteArray()),
                displayName = "Dense ${index + 1}",
                age = 30 + (index % 25),
                longitude = baseLongitude + ((index % 100) - 50) * 0.00008,
                latitude = baseLatitude + ((index / 100) - 25) * 0.00008,
                relationshipContext = if (index % 3 == 0) "linked" else "single",
            )
        }

    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO users (id, status)
                VALUES (?, 'active')
                """.trimIndent(),
            ).use { statement ->
                users.forEach { user ->
                    statement.setObject(1, user.id)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        connection
            .prepareStatement(
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
                """.trimIndent(),
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
        connection
            .prepareStatement(
                """
                INSERT INTO discovery_settings (
                    user_id,
                    grid_visible,
                    swipe_visible,
                    incognito,
                    show_distance
                )
                VALUES (?, true, true, false, true)
                """.trimIndent(),
            ).use { statement ->
                users.forEach { user ->
                    statement.setObject(1, user.id)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        connection
            .prepareStatement(
                """
                INSERT INTO user_locations (
                    user_id,
                    geom,
                    location_source,
                    accuracy_meters
                )
                VALUES (?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, 'gps', 12)
                """.trimIndent(),
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

private fun DataSource.analyzeGridPlannerTables() {
    connection.use { connection ->
        connection.createStatement().use { statement ->
            listOf(
                "users",
                "profiles",
                "discovery_settings",
                "user_locations",
                "user_blocks",
                "user_hidden_profiles",
                "user_profile_links",
                "boost_activations",
                "media_albums",
                "media_album_items",
                "media_assets",
            ).forEach { tableName ->
                statement.execute("ANALYZE $tableName")
            }
        }
    }
}

private data class DenseDiscoverableUser(
    val id: UUID,
    val displayName: String,
    val age: Int,
    val longitude: Double,
    val latitude: Double,
    val relationshipContext: String,
)

private fun DataSource.insertBlock(
    blockerUserId: UUID,
    blockedUserId: UUID,
) {
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO user_blocks (
                    id,
                    blocker_user_id,
                    blocked_user_id
                )
                VALUES (?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, blockerUserId)
                statement.setObject(3, blockedUserId)
                statement.executeUpdate()
            }
    }
}

private fun DataSource.insertHiddenProfile(
    hiderUserId: UUID,
    hiddenUserId: UUID,
) {
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO user_hidden_profiles (
                    id,
                    hider_user_id,
                    hidden_user_id
                )
                VALUES (?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, hiderUserId)
                statement.setObject(3, hiddenUserId)
                statement.executeUpdate()
            }
    }
}

private fun DataSource.insertConsumedPineappleLikeInventory(
    inventoryId: UUID,
    userId: UUID,
) {
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO premium_inventory (id, user_id, inventory_type, quantity, source)
                VALUES (?, ?, 'pineapple_like', 0, 'subscription')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, inventoryId)
                statement.setObject(2, userId)
                statement.executeUpdate()
            }
    }
}

private fun DataSource.selectPremiumInventoryQuantity(inventoryId: UUID): Int =
    connection.use { connection ->
        connection.prepareStatement("SELECT quantity FROM premium_inventory WHERE id = ?").use { statement ->
            statement.setObject(1, inventoryId)
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

private fun DataSource.insertActiveTravelLocation(
    userId: UUID,
    city: String,
    latitude: Double,
    longitude: Double,
) {
    insertActiveTravelLocationReturningId(userId, city, latitude, longitude)
}

private fun DataSource.insertActiveTravelLocationReturningId(
    userId: UUID,
    city: String,
    latitude: Double,
    longitude: Double,
): UUID {
    val id = UUID.randomUUID()
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO travel_locations (id, user_id, city, geom, status)
                VALUES (?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, 'active')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, id)
                statement.setObject(2, userId)
                statement.setString(3, city)
                statement.setDouble(4, longitude)
                statement.setDouble(5, latitude)
                statement.executeUpdate()
            }
    }
    return id
}

private fun DataSource.insertClearedTravelLocation(
    userId: UUID,
    city: String,
    latitude: Double,
    longitude: Double,
    clearedHoursAgo: Long,
): UUID {
    val id = UUID.randomUUID()
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO travel_locations (
                    id, user_id, city, geom, status, active_from, active_until
                )
                VALUES (
                    ?, ?, ?,
                    ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                    'cleared',
                    now() - (? * INTERVAL '1 hour'),
                    now() - (? * INTERVAL '1 hour')
                )
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, id)
                statement.setObject(2, userId)
                statement.setString(3, city)
                statement.setDouble(4, longitude)
                statement.setDouble(5, latitude)
                // active_from must be < active_until, so offset start by an extra hour.
                statement.setLong(6, clearedHoursAgo + 1)
                statement.setLong(7, clearedHoursAgo)
                statement.executeUpdate()
            }
    }
    return id
}

private fun DataSource.setVisibleWhileTraveling(
    userId: UUID,
    value: Boolean,
) {
    connection.use { connection ->
        connection
            .prepareStatement(
                "UPDATE discovery_settings SET visible_while_traveling = ? WHERE user_id = ?",
            ).use { statement ->
                statement.setBoolean(1, value)
                statement.setObject(2, userId)
                statement.executeUpdate()
            }
    }
}

private fun DataSource.setSwipeVisible(
    userId: UUID,
    value: Boolean,
) {
    connection.use { connection ->
        connection
            .prepareStatement(
                "UPDATE discovery_settings SET swipe_visible = ? WHERE user_id = ?",
            ).use { statement ->
                statement.setBoolean(1, value)
                statement.setObject(2, userId)
                statement.executeUpdate()
            }
    }
}

private fun DataSource.insertSwipeAction(
    actorUserId: UUID,
    targetUserId: UUID,
    action: String,
    travelLocationId: UUID? = null,
) {
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO swipe_actions (id, actor_user_id, target_user_id, action, travel_location_id)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, actorUserId)
                statement.setObject(3, targetUserId)
                statement.setString(4, action)
                statement.setObject(5, travelLocationId)
                statement.executeUpdate()
            }
    }
}

private fun DataSource.countSwipeActions(
    actorUserId: UUID,
    targetUserId: UUID,
): Int =
    connection.use { connection ->
        connection
            .prepareStatement(
                "SELECT COUNT(*) FROM swipe_actions WHERE actor_user_id = ? AND target_user_id = ?",
            ).use { statement ->
                statement.setObject(1, actorUserId)
                statement.setObject(2, targetUserId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
    }

private fun DataSource.selectActionConsumedInventoryId(actionId: UUID): UUID? =
    connection.use { connection ->
        connection
            .prepareStatement(
                "SELECT consumed_inventory_id FROM swipe_actions WHERE id = ?",
            ).use { statement ->
                statement.setObject(1, actionId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) rs.getObject("consumed_inventory_id", UUID::class.java) else null
                }
            }
    }
