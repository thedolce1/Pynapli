package com.pynapli.server.grid

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.swipe.JdbcSwipeRepository
import com.pynapli.server.testsupport.PynapliTestDatabase
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GridFilterDiscoveryTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest {
            PynapliTestDatabase.reset()
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

        "grid query with recentlyJoinedOnly excludes older accounts" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000000d01")
                val freshTarget = UUID.fromString("00000000-0000-0000-0000-000000000d02")
                val staleTarget = UUID.fromString("00000000-0000-0000-0000-000000000d03")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(freshTarget, "Fern", age = 30, longitude = -122.0836, latitude = 37.4226)
                dataSource.insertDiscoverableUser(staleTarget, "Otto", age = 41, longitude = -122.0838, latitude = 37.4228)
                // Accounts are created "now" by default; push the stale one past the 7-day window.
                dataSource.setUserCreatedDaysAgo(staleTarget, days = 30)

                val repository = JdbcGridRepository(dataSource)
                val recentlyJoined =
                    repository
                        .queryNearby(
                            viewerUserId = viewer,
                            radiusMeters = 100_000,
                            limit = 10,
                            filters = DiscoveryFilters(recentlyJoinedOnly = true),
                        ).cards

                assertEquals(listOf(freshTarget), recentlyJoined.map { it.userId })
            }
        }

        "grid query with activeTodayOnly excludes profiles with no recent session" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000000c01")
                val activeTarget = UUID.fromString("00000000-0000-0000-0000-000000000c02")
                val staleTarget = UUID.fromString("00000000-0000-0000-0000-000000000c03")
                val noSessionTarget = UUID.fromString("00000000-0000-0000-0000-000000000c04")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(activeTarget, "Ada", age = 35, longitude = -122.0836, latitude = 37.4226)
                dataSource.insertDiscoverableUser(staleTarget, "Stan", age = 33, longitude = -122.0838, latitude = 37.4228)
                dataSource.insertDiscoverableUser(noSessionTarget, "Nora", age = 31, longitude = -122.0839, latitude = 37.4229)
                val now = java.time.Instant.now()
                dataSource.insertSession(activeTarget, lastSeenAt = now.minusSeconds(3_600)) // active ~1h ago
                dataSource.insertSession(staleTarget, lastSeenAt = now.minusSeconds(3L * 86_400)) // last seen 3 days ago
                // A recently-seen but REVOKED session must not count as active.
                dataSource.insertSession(activeTarget, lastSeenAt = now, revoked = true)
                // noSessionTarget has no session row at all.

                val repository = JdbcGridRepository(dataSource)
                val activeToday =
                    repository
                        .queryNearby(
                            viewerUserId = viewer,
                            radiusMeters = 100_000,
                            limit = 10,
                            filters = DiscoveryFilters(activeTodayOnly = true),
                        ).cards

                assertEquals(listOf(activeTarget), activeToday.map { it.userId })
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

        "grid Tonight badge and filter come only from live sessions" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-000000000f01")
                val session = UUID.fromString("00000000-0000-0000-0000-000000000f02")
                val noTonight = UUID.fromString("00000000-0000-0000-0000-000000000f04")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(session, "Tonight", age = 30, longitude = -122.0836, latitude = 37.4226)
                dataSource.insertDiscoverableUser(noTonight, "Plain", age = 32, longitude = -122.0830, latitude = 37.4230)
                dataSource.insertActiveTonightSession(session)

                val repository = JdbcGridRepository(dataSource, rankCacheTtl = Duration.ZERO)
                val tonightOnly =
                    repository
                        .queryNearby(
                            viewerUserId = viewer,
                            radiusMeters = 100_000,
                            limit = 10,
                            filters = DiscoveryFilters(tonightAvailableOnly = true),
                        ).cards
                val unfiltered = repository.queryNearby(viewer, radiusMeters = 100_000, limit = 10).cards
                val byId = unfiltered.associateBy { it.userId }

                assertEquals(listOf(session), tonightOnly.map { it.userId })
                assertEquals(setOf(session, noTonight), byId.keys)
                assertEquals(true, byId[session]?.tonightActive, "Active session must show the Tonight badge")
                assertEquals(false, byId[noTonight]?.tonightActive)
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

        "grid query with withPhotosOnly excludes profiles without an approved public photo" {
            runBlocking {
                val viewer = UUID.fromString("00000000-0000-0000-0000-0000000a0e01")
                val withPhoto = UUID.fromString("00000000-0000-0000-0000-0000000a0e02")
                val noPhoto = UUID.fromString("00000000-0000-0000-0000-0000000a0e03")
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(withPhoto, "Pia", age = 30, longitude = -122.0836, latitude = 37.4226)
                dataSource.insertDiscoverableUser(noPhoto, "Noah", age = 31, longitude = -122.0838, latitude = 37.4228)
                dataSource.insertApprovedProfilePhoto(withPhoto)

                val repository = JdbcGridRepository(dataSource)
                val withPhotosOnly =
                    repository
                        .queryNearby(
                            viewerUserId = viewer,
                            radiusMeters = 100_000,
                            limit = 10,
                            filters = DiscoveryFilters(withPhotosOnly = true),
                        ).cards
                val unfiltered =
                    repository
                        .queryNearby(
                            viewerUserId = viewer,
                            radiusMeters = 100_000,
                            limit = 10,
                            filters = DiscoveryFilters(),
                        ).cards

                assertEquals(listOf(withPhoto), withPhotosOnly.map { it.userId })
                assertEquals(setOf(withPhoto, noPhoto), unfiltered.map { it.userId }.toSet())
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
