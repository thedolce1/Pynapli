package com.pynapli.server.swipe

import com.pynapli.server.grid.insertDiscoverableUser
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.MarkMatchesCelebratedRequestDto
import com.pynapli.shared.network.dto.SwipeAction
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The "It's a match" moment shows exactly once per person per match, across devices: the server owns
 * what each person has not seen yet (V108 `match_celebrations`).
 */
class MatchCelebrationIntegrationTest {
    private val dataSource: DataSource = PynapliTestDatabase.dataSource
    private val service = MatchCelebrationService(JdbcMatchCelebrationRepository(dataSource))

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    @Test
    fun aNewMatchIsUnseenForBothPeopleUntilEachMarksItSeen() =
        runBlocking {
            val (a, b) = seedPair()
            val matchId = match(a, b)

            val forA = service.listUncelebrated(a).items.single()
            assertEquals(matchId.toString(), forA.matchId)
            assertEquals(b.toString(), forA.matchedUserId)
            assertEquals("Blake", forA.matchedDisplayName)
            assertNull(forA.pairUserId)
            assertEquals(
                a.toString(),
                service
                    .listUncelebrated(b)
                    .items
                    .single()
                    .matchedUserId,
            )

            // a's device shows it and marks it; a second device (or a retry) marking it again is a no-op.
            assertEquals(1, service.markCelebrated(a, MarkMatchesCelebratedRequestDto(listOf(matchId.toString()))).marked)
            assertEquals(0, service.markCelebrated(a, MarkMatchesCelebratedRequestDto(listOf(matchId.toString()))).marked)

            assertTrue(service.listUncelebrated(a).items.isEmpty())
            // b has still not seen it.
            assertEquals(
                matchId.toString(),
                service
                    .listUncelebrated(b)
                    .items
                    .single()
                    .matchId,
            )
        }

    @Test
    fun nobodyCanMarkAMatchTheyAreNotPartOf() =
        runBlocking {
            val (a, b) = seedPair()
            val stranger = UUID.randomUUID()
            dataSource.insertDiscoverableUser(stranger, "Casey", 40, -79.382, 43.652)
            val matchId = match(a, b)

            assertEquals(0, service.markCelebrated(stranger, MarkMatchesCelebratedRequestDto(listOf(matchId.toString(), "not-a-uuid"))).marked)
            assertEquals(1, service.listUncelebrated(a).items.size)
            assertEquals(1, service.listUncelebrated(b).items.size)
        }

    @Test
    fun aBlockedOrOldMatchIsNotCelebrated() =
        runBlocking {
            val (a, b) = seedPair()
            match(a, b)
            dataSource.execute("INSERT INTO user_blocks (id, blocker_user_id, blocked_user_id) VALUES (?, ?, ?)", UUID.randomUUID(), b, a)
            assertTrue(service.listUncelebrated(a).items.isEmpty())
            assertTrue(service.listUncelebrated(b).items.isEmpty())

            val (c, d) = seedPair()
            match(c, d)
            val weeksLater = MatchCelebrationService(JdbcMatchCelebrationRepository(dataSource), clock = Clock.offset(Clock.systemUTC(), Duration.ofDays(30)))
            assertTrue(weeksLater.listUncelebrated(c).items.isEmpty())
        }

    @Test
    fun theMigrationBackfillMarksExistingMatchesSeenForBothPeople() =
        runBlocking {
            val (a, b) = seedPair()
            match(a, b)
            assertEquals(1, service.listUncelebrated(a).items.size)

            // V108's backfill, run as written against matches that existed before it.
            dataSource.execute(backfillStatement())

            assertTrue(service.listUncelebrated(a).items.isEmpty())
            assertTrue(service.listUncelebrated(b).items.isEmpty())
        }

    @Test
    fun aPsyncTogetherMatchIsOneMomentWithTheLinkedPartnerAsThePair() =
        runBlocking {
            val maya = UUID.randomUUID()
            val alex = UUID.randomUUID()
            val target = UUID.randomUUID()
            dataSource.insertDiscoverableUser(maya, "Maya", 33, -79.38, 43.65)
            dataSource.insertDiscoverableUser(alex, "Alex", 35, -79.381, 43.651)
            dataSource.insertDiscoverableUser(target, "Sam", 36, -79.382, 43.652)
            // Psync resolution writes one canonical match per partner and a together row.
            val withMaya = insertMatch(maya, target)
            val withAlex = insertMatch(alex, target)
            val sessionId = insertTogetherSession(maya, alex, target, withAlex)

            val moments = service.listUncelebrated(target).items
            val moment = moments.single()
            assertEquals(withMaya.toString(), moment.matchId)
            assertEquals("Maya", moment.matchedDisplayName)
            assertEquals(withAlex.toString(), moment.pairMatchId)
            assertEquals(alex.toString(), moment.pairUserId)
            assertEquals("Alex", moment.pairDisplayName)
            assertNotNull(sessionId)

            // Each partner's own moment is individual: a swipe acts on the individual only.
            assertNull(
                service
                    .listUncelebrated(maya)
                    .items
                    .single()
                    .pairUserId,
            )

            service.markCelebrated(target, MarkMatchesCelebratedRequestDto(listOfNotNull(moment.matchId, moment.pairMatchId)))
            assertTrue(service.listUncelebrated(target).items.isEmpty())
        }

    private fun seedPair(): Pair<UUID, UUID> {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        dataSource.insertDiscoverableUser(a, "Avery", 35, -79.38, 43.65)
        dataSource.insertDiscoverableUser(b, "Blake", 34, -79.381, 43.651)
        return a to b
    }

    /** A real mutual like through the Swipe repository. */
    private suspend fun match(
        a: UUID,
        b: UUID,
    ): UUID {
        val repository = JdbcSwipeRepository(dataSource)
        repository.recordSwipeAction(UUID.randomUUID(), a, b, SwipeAction.LIKE)
        return assertNotNull(repository.recordSwipeAction(UUID.randomUUID(), b, a, SwipeAction.LIKE).match).matchId
    }

    private fun insertMatch(
        a: UUID,
        b: UUID,
    ): UUID {
        val id = UUID.randomUUID()
        val (first, second) = canonicalPair(a, b)
        dataSource.execute("INSERT INTO matches (id, user_a, user_b) VALUES (?, ?, ?)", id, first, second)
        return id
    }

    private fun insertTogetherSession(
        initiator: UUID,
        partner: UUID,
        target: UUID,
        matchId: UUID,
    ): UUID {
        val linkId = UUID.randomUUID()
        dataSource.execute(
            "INSERT INTO user_profile_links (id, user_id, linked_user_id, status, accepted_at) VALUES (?, ?, ?, 'active', now())",
            linkId,
            initiator,
            partner,
        )
        val sessionId = UUID.randomUUID()
        dataSource.execute(
            "INSERT INTO psync_sessions (id, partner_link_id, initiator_user_id, status, deck_size, expires_at) VALUES (?, ?, ?, 'completed', 10, now() + interval '1 day')",
            sessionId,
            linkId,
            initiator,
        )
        dataSource.execute(
            "INSERT INTO psync_session_participants (session_id, user_id, role, consent_status, approved) VALUES (?, ?, 'initiator', 'accepted', true), (?, ?, 'partner', 'accepted', true)",
            sessionId,
            initiator,
            sessionId,
            partner,
        )
        dataSource.execute(
            "INSERT INTO psync_session_matches (id, session_id, target_user_id, kind, owner_user_id, match_id) VALUES (?, ?, ?, 'together', NULL, ?)",
            UUID.randomUUID(),
            sessionId,
            target,
            matchId,
        )
        return sessionId
    }

    private fun backfillStatement(): String {
        val migration =
            assertNotNull(javaClass.classLoader.getResource("db/migration/V108__create_match_celebrations.sql")).readText()
        return migration.substring(migration.indexOf("INSERT INTO match_celebrations")).trim().removeSuffix(";")
    }
}

private fun DataSource.execute(
    sql: String,
    vararg params: Any,
) {
    connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }
    }
}
