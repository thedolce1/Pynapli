package com.pynapli.server.grid

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.swipe.JdbcSwipeRepository
import com.pynapli.server.swipe.SwipeException
import com.pynapli.server.swipe.SwipeService
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.SwipeAction
import com.pynapli.shared.network.dto.SwipeActionRequestDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SwipeDeckDiscoveryTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest {
            PynapliTestDatabase.reset()
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

        "grid and swipe query with hasPrivateAlbumOnly excludes profiles without a private album" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000000e01")
                val withPrivate = UUID.fromString("00000000-0000-0000-0000-000000000e02")
                val withoutPrivate = UUID.fromString("00000000-0000-0000-0000-000000000e03")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(withPrivate, "Priya", age = 30, longitude = -122.0836, latitude = 37.4226)
                dataSource.insertDiscoverableUser(withoutPrivate, "Pat", age = 33, longitude = -122.0838, latitude = 37.4228)
                // withPrivate owns a private album with an approved photo; withoutPrivate only a profile album.
                dataSource.insertApprovedPrivateAlbumPhoto(withPrivate)
                dataSource.insertApprovedProfilePhoto(withoutPrivate)

                val filters = DiscoveryFilters(hasPrivateAlbumOnly = true)
                val grid =
                    JdbcGridRepository(dataSource)
                        .queryNearby(viewer, radiusMeters = 100_000, limit = 10, filters = filters)
                        .cards
                val swipe =
                    JdbcSwipeRepository(dataSource)
                        .queryDeck(viewer, radiusMeters = 100_000, limit = 10, filters = filters)
                        .cards

                assertEquals(listOf(withPrivate), grid.map { it.userId })
                assertEquals(listOf(withPrivate), swipe.map { it.userId })
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

        "swipe deck with withPhotosOnly excludes profiles without an approved public photo" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-0000000a0e11")
                val withPhoto = UUID.fromString("00000000-0000-0000-0000-0000000a0e12")
                val noPhoto = UUID.fromString("00000000-0000-0000-0000-0000000a0e13")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(withPhoto, "Pia", age = 30, longitude = -122.0836, latitude = 37.4226)
                dataSource.insertDiscoverableUser(noPhoto, "Noah", age = 31, longitude = -122.0838, latitude = 37.4228)
                dataSource.insertApprovedProfilePhoto(withPhoto)

                val withPhotosOnly =
                    JdbcSwipeRepository(dataSource)
                        .queryDeck(
                            viewerUserId = viewer,
                            radiusMeters = 100_000,
                            limit = 10,
                            filters = DiscoveryFilters(withPhotosOnly = true),
                        ).cards

                assertEquals(listOf(withPhoto), withPhotosOnly.map { it.userId })
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

                // Without the preference both are in the same 1 km order bucket, so their order is the
                // stable pseudo-random tiebreak, not exact distance (L-08).
                val unboosted = repository.queryDeck(viewer, radiusMeters = 100_000, limit = 10).cards
                assertEquals(setOf(unverifiedTarget, verifiedTarget), unboosted.map { it.userId }.toSet())

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
    })
