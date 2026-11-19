package com.pynapli.server.grid

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.swipe.JdbcSwipeRepository
import com.pynapli.server.swipe.SwipeService
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.SwipeAction
import com.pynapli.shared.network.dto.SwipeActionRequestDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TravelPassportDiscoveryTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest {
            PynapliTestDatabase.reset()
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
            // invisible to Grid AND Swipe. Existing default true
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

        // A traveller is matched on the Grid only at their EFFECTIVE location (travel point), never by
        // their home GPS (L-09/L-12: membership near home would reveal where a traveller lives). Grid
        // narrows candidates with a UNION of two index lookups first, so a user whose home AND travel
        // point are both near must still appear exactly once.
        "grid radius matches a traveller only at their travel point and never duplicates" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000000e00")
                val homeNearTravelFar = UUID.fromString("00000000-0000-0000-0000-000000000e01")
                val homeFarTravelNear = UUID.fromString("00000000-0000-0000-0000-000000000e02")
                val bothNear = UUID.fromString("00000000-0000-0000-0000-000000000e03")
                val bothFar = UUID.fromString("00000000-0000-0000-0000-000000000e04")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.4194, latitude = 37.7749)
                dataSource.insertDiscoverableUser(homeNearTravelFar, "Ari", age = 31, longitude = -122.4180, latitude = 37.7740)
                dataSource.insertActiveTravelLocation(userId = homeNearTravelFar, city = "New York", latitude = 40.7128, longitude = -74.0060)
                dataSource.insertDiscoverableUser(homeFarTravelNear, "Bo", age = 33, longitude = -74.0060, latitude = 40.7128)
                dataSource.insertActiveTravelLocation(userId = homeFarTravelNear, city = "San Francisco", latitude = 37.7745, longitude = -122.4185)
                dataSource.insertDiscoverableUser(bothNear, "Cy", age = 35, longitude = -122.4170, latitude = 37.7745)
                dataSource.insertActiveTravelLocation(userId = bothNear, city = "San Francisco", latitude = 37.7750, longitude = -122.4190)
                dataSource.insertDiscoverableUser(bothFar, "Di", age = 36, longitude = -74.0060, latitude = 40.7128)
                dataSource.insertActiveTravelLocation(userId = bothFar, city = "Boston", latitude = 42.3601, longitude = -71.0589)

                val cards =
                    JdbcGridRepository(dataSource)
                        .queryNearby(viewer, radiusMeters = 5_000, limit = 10, filters = DiscoveryFilters())
                        .cards
                val ids = cards.map { it.userId }

                assertEquals(setOf(homeFarTravelNear, bothNear), ids.toSet(), "home-near traveller must not match: $ids")
                assertEquals(ids.size, ids.toSet().size, "a user near on both sides must appear once: $ids")
            }
        }

        "grid query with travelingHereOnly returns only travel-mode visitors" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000000d11")
                val visitor = UUID.fromString("00000000-0000-0000-0000-000000000d12")
                val localResident = UUID.fromString("00000000-0000-0000-0000-000000000d13")
                // Viewer + a local resident both physically in SF.
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.4194, latitude = 37.7749)
                dataSource.insertDiscoverableUser(localResident, "Sage", age = 32, longitude = -122.4180, latitude = 37.7740)
                // Visitor lives in NYC (far) but is traveling to SF — their travel point sits by the viewer.
                dataSource.insertDiscoverableUser(visitor, "Maya", age = 34, longitude = -74.0060, latitude = 40.7128)
                dataSource.insertActiveTravelLocation(userId = visitor, city = "San Francisco", latitude = 37.7745, longitude = -122.4185)

                val repository = JdbcGridRepository(dataSource)
                val travelingHere =
                    repository
                        .queryNearby(
                            viewerUserId = viewer,
                            radiusMeters = 5_000,
                            limit = 10,
                            filters = DiscoveryFilters(travelingHereOnly = true),
                        ).cards

                assertEquals(listOf(visitor), travelingHere.map { it.userId })
                assertEquals(true, travelingHere.single().visiting)
            }
        }
    })
