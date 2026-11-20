package com.pynapli.server.contactblocks

import com.pynapli.server.account.JdbcAccountDataExportArchiver
import com.pynapli.server.account.JdbcAccountLifecycleRepository
import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.JdbcAuthRepository
import com.pynapli.server.auth.security.PasswordHasher
import com.pynapli.server.grid.GridService
import com.pynapli.server.grid.JdbcGridRepository
import com.pynapli.server.grid.insertActiveProfileLink
import com.pynapli.server.grid.insertDiscoverableUser
import com.pynapli.server.grid.markProfileVerified
import com.pynapli.server.safety.JdbcHiddenProfileRepository
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.SafetyException
import com.pynapli.server.testsupport.CountingRateLimitRepository
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.server.testsupport.raceTogether
import com.pynapli.server.testsupport.signUpVerified
import com.pynapli.shared.network.dto.GridQueryRequestDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How contact blocks bind (TRUST_AND_SAFETY_SPEC, "Contact blocks"): the prior-interaction exemption
 * per interaction type, unverified accounts, members who verify later, partner cards, the shared rank
 * cache, caps, deletion, export, remove-then-add, and add racing resolve.
 */
class ContactBlockBindingIntegrationTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest { PynapliTestDatabase.reset() }

        fun seedPair(graph: ContactBlockTestGraph) {
            dataSource.insertDiscoverableUser(BLOCKER, "Alex", age = 34, longitude = -122.0800, latitude = 37.4200)
            dataSource.insertDiscoverableUser(TARGET, "Sam", age = 41, longitude = -122.0810, latitude = 37.4210)
        }

        suspend fun addAndResolve(graph: ContactBlockTestGraph) {
            graph.contactBlocks.add(BLOCKER, TARGET_ADDRESS)
            graph.resolver.resolveRecent()
        }

        // Interaction rows from before the block (an hour ago), or initiated by X at any time: exempt.
        val exemptCases =
            mapOf(
                "a profile view by A before the block" to
                    "INSERT INTO activity_events (id, recipient_user_id, actor_user_id, event_type, target_user_id, first_occurred_at, occurred_at) VALUES (gen_random_uuid(), '$TARGET', '$BLOCKER', 'profile_view', '$TARGET', now() - interval '1 hour', now() - interval '1 hour')",
                "a profile view by X, at any time" to
                    "INSERT INTO activity_events (id, recipient_user_id, actor_user_id, event_type, target_user_id) VALUES (gen_random_uuid(), '$BLOCKER', '$TARGET', 'profile_view', '$BLOCKER')",
                "a flirt by A before the block" to
                    "INSERT INTO activity_events (id, recipient_user_id, actor_user_id, event_type, target_user_id, first_occurred_at, occurred_at) VALUES (gen_random_uuid(), '$TARGET', '$BLOCKER', 'flirt', '$TARGET', now() - interval '1 hour', now() - interval '1 hour')",
                "a like by A before the block" to
                    "INSERT INTO swipe_actions (id, actor_user_id, target_user_id, action, created_at) VALUES (gen_random_uuid(), '$BLOCKER', '$TARGET', 'like', now() - interval '1 hour')",
                "a like by X, at any time" to
                    "INSERT INTO swipe_actions (id, actor_user_id, target_user_id, action) VALUES (gen_random_uuid(), '$TARGET', '$BLOCKER', 'like')",
                "a match" to
                    "INSERT INTO matches (id, user_a, user_b) VALUES (gen_random_uuid(), LEAST('$BLOCKER'::uuid, '$TARGET'::uuid), GREATEST('$BLOCKER'::uuid, '$TARGET'::uuid))",
                "a favourite by X" to
                    "INSERT INTO favorite_profiles (user_id, favorite_user_id) VALUES ('$TARGET', '$BLOCKER')",
                "a partner link invite by X" to
                    "INSERT INTO user_profile_links (id, user_id, linked_user_id, status) VALUES (gen_random_uuid(), '$TARGET', '$BLOCKER', 'pending')",
                "an ordinary block by X" to
                    "INSERT INTO user_blocks (id, blocker_user_id, blocked_user_id) VALUES (gen_random_uuid(), '$TARGET', '$BLOCKER')",
                "an ordinary block by A before the block" to
                    "INSERT INTO user_blocks (id, blocker_user_id, blocked_user_id, created_at) VALUES (gen_random_uuid(), '$BLOCKER', '$TARGET', now() - interval '1 hour')",
            )

        exemptCases.forEach { (name, sql) ->
            "pairs with prior interaction are exempt: $name" {
                runBlocking<Unit> {
                    val graph = ContactBlockTestGraph(dataSource)
                    seedPair(graph)
                    graph.verifyAddress(TARGET, TARGET_ADDRESS)
                    graph.execute(sql)
                    addAndResolve(graph)

                    assertTrue(graph.visibility.hiddenFrom(TARGET).isEmpty(), "exempt pair must stay visible ($name)")
                    // Settled once (exempt token), never retried into a bind later.
                    assertEquals(0, graph.count("SELECT COUNT(*) FROM contact_blocks WHERE target_token IS NULL"))
                }
            }
        }

        "a pass by A, or anything A does after adding the address, doesn't exempt the pair" {
            runBlocking<Unit> {
                val graph = ContactBlockTestGraph(dataSource)
                seedPair(graph)
                graph.verifyAddress(TARGET, TARGET_ADDRESS)
                graph.execute(
                    "INSERT INTO swipe_actions (id, actor_user_id, target_user_id, action, created_at) VALUES (gen_random_uuid(), ?, ?, 'pass', now() - interval '1 hour')",
                    BLOCKER,
                    TARGET,
                )
                graph.contactBlocks.add(BLOCKER, TARGET_ADDRESS)
                // A views X between the add and the (delayed) resolution.
                graph.execute(
                    "INSERT INTO activity_events (id, recipient_user_id, actor_user_id, event_type, target_user_id, first_occurred_at) VALUES (gen_random_uuid(), ?, ?, 'profile_view', ?, now() + interval '1 minute')",
                    TARGET,
                    BLOCKER,
                    TARGET,
                )
                graph.resolver.resolveRecent()

                assertEquals(setOf(BLOCKER), graph.visibility.hiddenFrom(TARGET))
            }
        }

        "an account whose address isn't verified is never bound, even after signing in with it" {
            runBlocking<Unit> {
                val graph = ContactBlockTestGraph(dataSource)
                seedPair(graph)
                graph.resolver.recordAddressAtSignIn(TARGET, TARGET_ADDRESS)
                addAndResolve(graph)
                graph.resolver.resolveAllPending()

                assertTrue(graph.visibility.hiddenFrom(TARGET).isEmpty())
                assertEquals(0, graph.count("SELECT COUNT(*) FROM users WHERE contact_token IS NOT NULL AND id = ?", TARGET))
                assertEquals(1, graph.count("SELECT COUNT(*) FROM contact_blocks WHERE target_token IS NULL"))
            }
        }

        "an account verified before contact tokens existed is bound at its next sign-in, before the session" {
            runBlocking<Unit> {
                val graph = ContactBlockTestGraph(dataSource)
                seedPair(graph)
                graph.execute("UPDATE users SET email_verified_at = now() WHERE id = ?", TARGET)
                addAndResolve(graph)
                assertTrue(graph.visibility.hiddenFrom(TARGET).isEmpty(), "no token yet, so nothing can match")

                graph.resolver.recordAddressAtSignIn(TARGET, TARGET_ADDRESS)

                assertEquals(setOf(BLOCKER), graph.visibility.hiddenFrom(TARGET))
            }
        }

        "a member who verifies after the block is hidden from before their first Grid, with no resolver run" {
            runBlocking<Unit> {
                val graph = ContactBlockTestGraph(dataSource)
                dataSource.insertDiscoverableUser(BLOCKER, "Alex", age = 34, longitude = -122.0800, latitude = 37.4200)
                graph.contactBlocks.add(BLOCKER, "  NewMember@Example.com")
                val auth = AuthService(JdbcAuthRepository(dataSource), passwordHasher = PasswordHasher(iterations = 1_000))

                val session =
                    auth.signUpVerified(
                        email = "newmember@example.com",
                        password = "long-enough-password-1",
                        onAccountVerified = graph.resolver::bindNewlyVerifiedAccount,
                    )
                val newMember = UUID.fromString(session.user.userId)

                assertEquals(setOf(BLOCKER), graph.visibility.hiddenFrom(newMember))
                graph.execute(
                    "INSERT INTO user_locations (user_id, geom, location_source, accuracy_meters) VALUES (?, ST_SetSRID(ST_MakePoint(-122.0805, 37.4205), 4326)::geography, 'gps', 12)",
                    newMember,
                )
                val firstGrid = graph.grid.queryGrid(newMember, GridQueryRequestDto(radiusMeters = 50_000))
                assertTrue(firstGrid.items.none { it.userId == BLOCKER.toString() })
            }
        }

        "the blocker's face comes off their partner's card for X, and the couple no longer passes verified-couples" {
            runBlocking<Unit> {
                val graph = ContactBlockTestGraph(dataSource)
                seedPair(graph)
                dataSource.insertDiscoverableUser(PARTNER, "Jo", age = 36, longitude = -122.0805, latitude = 37.4205)
                dataSource.insertDiscoverableUser(BYSTANDER, "Riley", age = 30, longitude = -122.0806, latitude = 37.4206)
                dataSource.insertActiveProfileLink(userId = PARTNER, linkedUserId = BLOCKER)
                dataSource.markProfileVerified(PARTNER)
                dataSource.markProfileVerified(BLOCKER)
                graph.verifyAddress(TARGET, TARGET_ADDRESS)
                addAndResolve(graph)

                val partnerCardForX = graph.grid.queryGrid(TARGET, GridQueryRequestDto(radiusMeters = 50_000)).items.single { it.userId == PARTNER.toString() }
                assertNull(partnerCardForX.linkedPartner)
                assertEquals(false, partnerCardForX.coupleVerified)
                val partnerCardForBystander = graph.grid.queryGrid(BYSTANDER, GridQueryRequestDto(radiusMeters = 50_000)).items.single { it.userId == PARTNER.toString() }
                assertEquals(BLOCKER.toString(), assertNotNull(partnerCardForBystander.linkedPartner).userId)

                val swipeCard = graph.swipe.queryDeck(TARGET, SwipeDeckRequestDto(radiusMeters = 50_000)).items.single { it.userId == PARTNER.toString() }
                assertNull(swipeCard.linkedPartner)
                assertNull(graph.profiles.getPublicProfile(TARGET, PARTNER).linkedPartner)

                val verifiedCouples = GridQueryRequestDto(radiusMeters = 50_000, verifiedCouplesOnly = true)
                assertTrue(graph.grid.queryGrid(TARGET, verifiedCouples).items.none { it.userId == PARTNER.toString() })
                assertTrue(graph.grid.queryGrid(BYSTANDER, verifiedCouples).items.any { it.userId == PARTNER.toString() })
            }
        }

        "a Playing Tonight session of the blocker never shows (nor its map zone) for X, and does for others" {
            runBlocking<Unit> {
                val graph = ContactBlockTestGraph(dataSource)
                seedPair(graph)
                dataSource.insertDiscoverableUser(BYSTANDER, "Riley", age = 30, longitude = -122.0806, latitude = 37.4206)
                graph.verifyAddress(TARGET, TARGET_ADDRESS)
                addAndResolve(graph)
                graph.execute(
                    """
                    WITH session AS (
                        INSERT INTO tonight_sessions (id, created_by_user_id, mode, intent, host_mode, status, started_at, expires_at)
                        VALUES (gen_random_uuid(), ?, 'solo', 'solo', 'hosting', 'active', now(), now() + interval '2 hours')
                        RETURNING id
                    )
                    INSERT INTO tonight_session_participants (session_id, user_id, role, approval_status, approved_at)
                    SELECT id, ?, 'owner', 'approved', now() FROM session
                    """.trimIndent(),
                    BLOCKER,
                    BLOCKER,
                )

                val forTarget = graph.tonight.feed(TARGET, 50_000, null, 50).items
                val forBystander = graph.tonight.feed(BYSTANDER, 50_000, null, 50).items

                assertTrue(forTarget.none { it.person.userId == BLOCKER.toString() })
                assertTrue(forBystander.any { it.person.userId == BLOCKER.toString() })
            }
        }

        "the shared rank cache stays free of the rule: two viewers share the window and differ only after it" {
            runBlocking<Unit> {
                val graph = ContactBlockTestGraph(dataSource)
                seedPair(graph)
                dataSource.insertDiscoverableUser(BYSTANDER, "Riley", age = 30, longitude = -122.0811, latitude = 37.4211)
                graph.verifyAddress(TARGET, TARGET_ADDRESS)
                addAndResolve(graph)
                val cachedRepository = JdbcGridRepository(dataSource, rankCacheTtl = Duration.ofMinutes(5))
                val cachedGrid =
                    GridService(
                        repository = cachedRepository,
                        hiddenProfileRepository = JdbcHiddenProfileRepository(dataSource),
                        contactBlockVisibility = graph.visibility,
                    )

                val bystanderSees = cachedGrid.queryGrid(BYSTANDER, GridQueryRequestDto(radiusMeters = 50_000)).items.map { it.userId }
                val targetSees = cachedGrid.queryGrid(TARGET, GridQueryRequestDto(radiusMeters = 50_000)).items.map { it.userId }
                // The window both were served from still holds the blocker: the rule ran after it.
                val window = cachedRepository.queryNearby(TARGET, 50_000, 60).cards.map { it.userId }

                assertTrue(BLOCKER.toString() in bystanderSees)
                assertTrue(BLOCKER.toString() !in targetSees)
                assertTrue(BLOCKER in window)
                assertEquals(bystanderSees.toSet() - BYSTANDER.toString() - TARGET.toString(), targetSees.toSet() + BLOCKER.toString() - BYSTANDER.toString() - TARGET.toString())
            }
        }

        "caps: 1,000 addresses (a duplicate still answers the same) and 100 adds a day, for members and non-members alike" {
            runBlocking<Unit> {
                val graph = ContactBlockTestGraph(dataSource)
                seedPair(graph)
                graph.execute(
                    "INSERT INTO contact_blocks (id, blocker_user_id, email_token) SELECT gen_random_uuid(), ?, 'filler-' || n FROM generate_series(1, 999) AS n",
                    BLOCKER,
                )
                graph.contactBlocks.add(BLOCKER, "thousandth@example.com")
                assertEquals(1_000, graph.contactBlocks.summary(BLOCKER).count)
                graph.contactBlocks.add(BLOCKER, "thousandth@example.com")
                val full = assertFailsWith<SafetyException> { graph.contactBlocks.add(BLOCKER, "one-too-many@example.com") }
                assertEquals("CONTACT_BLOCK_LIMIT_REACHED", full.code)

                val limited =
                    ContactBlockService(
                        JdbcContactBlockRepository(dataSource),
                        graph.tokens,
                        graph.trigger,
                        rateLimitService = RateLimitService(CountingRateLimitRepository()),
                    )
                repeat(ContactBlockService.MAX_ADDS_PER_DAY) { index -> limited.add(TARGET, "daily-$index@example.com") }
                val overDaily = assertFailsWith<SafetyException> { limited.add(TARGET, "one-more@example.com") }
                assertEquals("RATE_LIMITED", overDaily.code)
            }
        }

        "deleting the blocker removes their rows; deleting the target sends rows back to pending until the address verifies again" {
            runBlocking<Unit> {
                val graph = ContactBlockTestGraph(dataSource)
                seedPair(graph)
                graph.verifyAddress(TARGET, TARGET_ADDRESS)
                addAndResolve(graph)
                assertEquals(setOf(BLOCKER), graph.visibility.hiddenFrom(TARGET))
                val lifecycle = JdbcAccountLifecycleRepository(dataSource, graph.tokens)

                assertNotNull(lifecycle.deleteAccount(TARGET))
                assertEquals(1, graph.count("SELECT COUNT(*) FROM contact_blocks WHERE target_token IS NULL"))
                assertEquals(0, graph.count("SELECT COUNT(*) FROM users WHERE id = ? AND contact_token IS NOT NULL", TARGET))

                // The address registers again as a new account and is verified: bound to the new account.
                dataSource.insertDiscoverableUser(RETURNING, "Sam again", age = 41, longitude = -122.0810, latitude = 37.4210)
                graph.verifyAddress(RETURNING, TARGET_ADDRESS)
                assertEquals(setOf(BLOCKER), graph.visibility.hiddenFrom(RETURNING))

                assertNotNull(lifecycle.deleteAccount(BLOCKER))
                assertEquals(0, graph.count("SELECT COUNT(*) FROM contact_blocks WHERE blocker_user_id = ?", BLOCKER))
            }
        }

        "the blocker's export shows when each address was added and nothing else" {
            runBlocking<Unit> {
                val graph = ContactBlockTestGraph(dataSource)
                seedPair(graph)
                graph.verifyAddress(TARGET, TARGET_ADDRESS)
                addAndResolve(graph)
                graph.contactBlocks.add(BLOCKER, "nobody@example.com")

                val archive = JdbcAccountDataExportArchiver(dataSource, contactBlockTokens = graph.tokens).buildArchive(BLOCKER)
                val text = archive.bytes.decodeToString()
                val rows = Json.parseToJsonElement(text).jsonObject["hideFromPeopleYouKnow"] as JsonArray

                assertEquals(2, rows.size)
                rows.forEach { row -> assertEquals(setOf("created_at"), row.jsonObject.keys) }
                assertTrue(TARGET.toString() !in text, "the export never says whom an address belongs to")
            }
        }

        "removing an address and adding it again leaves the pair exempt (A's hidden actions surfaced in between)" {
            runBlocking<Unit> {
                val graph = ContactBlockTestGraph(dataSource)
                seedPair(graph)
                graph.verifyAddress(TARGET, TARGET_ADDRESS)
                addAndResolve(graph)
                graph.activity.sendFlirt(BLOCKER, TARGET)
                assertEquals(setOf(BLOCKER), graph.visibility.hiddenFrom(TARGET))

                graph.contactBlocks.remove(BLOCKER, TARGET_ADDRESS)
                // The flirt sent while hidden now shows for X.
                assertTrue(graph.activity.listActivity(TARGET).items.any { it.actorUserId == BLOCKER.toString() })
                graph.execute("UPDATE activity_events SET first_occurred_at = now() - interval '1 minute' WHERE actor_user_id = ?", BLOCKER)
                addAndResolve(graph)

                assertTrue(graph.visibility.hiddenFrom(TARGET).isEmpty(), "documented: a re-add after a remove finds the surfaced rows")
            }
        }

        "an add racing the resolver always ends bound, once, with no duplicate rows" {
            runBlocking<Unit> {
                val graph = ContactBlockTestGraph(dataSource)
                seedPair(graph)
                graph.verifyAddress(TARGET, TARGET_ADDRESS)
                repeat(10) {
                    raceTogether(
                        first = { graph.contactBlocks.add(BLOCKER, TARGET_ADDRESS) },
                        second = { graph.resolver.resolveRecent() },
                    )
                }
                graph.resolver.resolveRecent()

                assertEquals(1, graph.count("SELECT COUNT(*) FROM contact_blocks WHERE blocker_user_id = ?", BLOCKER))
                assertEquals(setOf(BLOCKER), graph.visibility.hiddenFrom(TARGET))
            }
        }
    })

private const val TARGET_ADDRESS = "sam.target@example.com"
private val BLOCKER: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c1a1")
private val TARGET: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c1a2")
private val PARTNER: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c1a3")
private val BYSTANDER: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c1a4")
private val RETURNING: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c1a5")
