package com.pynapli.server.psync

import com.pynapli.server.contactblocks.ContactBlockTestGraph
import com.pynapli.server.grid.insertActiveProfileLink
import com.pynapli.server.grid.insertDiscoverableUser
import com.pynapli.server.grid.markProfileVerified
import com.pynapli.server.swipe.SwipeDeckTokenCodec
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.SwipeCardDto
import com.pynapli.shared.network.dto.SwipeDealbreakerKey
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Plan G2-3 at the Psync coordinator, against the real JDBC repository: a deck dealt with a partner
 * filter keeps a candidate only when their partner is shown to BOTH session partners and satisfies
 * the filter. Run twice: with the production couple ranker (both views in the rank), and with a
 * one-view ranker, which only the coordinator's defensive re-check
 * (PsyncSessionRepository.partnerFilterMatches) can correct.
 *
 * A is photo-verified, B is not. W is a public couple; V shows the partner to verified members only
 * (A may see V2, B may not); X2 hides from B ("Hide from people you know"); Y2 hides from A. Every
 * "2" is a woman, so `partnerGenders = [woman]` keeps W1 only.
 */
class PsyncPartnerFiltersCoordinatorIntegrationTest {
    private val dataSource: DataSource = PynapliTestDatabase.dataSource
    private lateinit var graph: ContactBlockTestGraph

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
        graph = ContactBlockTestGraph(dataSource)
        nextOffset = 0
    }

    @Test
    fun theDealtDeckKeepsOnlyPartnersBothSessionPartnersSeeAndThatMatch() =
        runBlocking {
            val world = seedWorld()
            val dealt = deal(world, coupleRanker())
            assertEquals(setOf(world.w1), dealt.intersect(world.firsts), "with the couple ranker")
        }

    @Test
    fun theCoordinatorRecheckCatchesAOneViewRanker() =
        runBlocking {
            val world = seedWorld()
            // Sanity: ranked from A alone, V1 (A is verified) and X1 (X2 hides from B only) would pass.
            val oneView = oneViewRanker().rankCandidatesForCouple(world.a, world.b, request(), 60).ids()
            assertTrue(world.v1 in oneView && world.x1 in oneView, "the one-view ranker lets them through")
            val dealt = deal(world, oneViewRanker())
            assertEquals(setOf(world.w1), dealt.intersect(world.firsts), "the coordinator drops V1, X1 and Y1")
            assertFalse(world.v1 in dealt)
            assertFalse(world.x1 in dealt)
            assertFalse(world.y1 in dealt)
        }

    // --- the coordinator ------------------------------------------------------------------------------

    private suspend fun deal(
        world: World,
        generator: PsyncDeckGenerator,
    ): Set<UUID> {
        var captured: List<PsyncSessionCard> = emptyList()
        val jdbc = JdbcPsyncSessionRepository(dataSource)
        val repository =
            object : PsyncSessionRepository by jdbc {
                override suspend fun activateWithDeck(
                    sessionId: UUID,
                    deckSessionId: UUID,
                    cards: List<PsyncSessionCard>,
                    now: Instant,
                ): PsyncSessionRepository.MutateOutcome {
                    captured = cards
                    return PsyncSessionRepository.MutateOutcome.InvalidState
                }
            }
        val coordinator =
            PsyncDeckCoordinator(
                repository = repository,
                deckGenerator = generator,
                blockRepository = null,
                hiddenProfileRepository = null,
                presenceService = null,
                mediaAssetUrlResolver = null,
                deckTokenCodec = SwipeDeckTokenCodec("test-psync-secret"),
                clock = Clock.systemUTC(),
                contactBlockVisibility = graph.visibility,
            )
        coordinator.activateWithGeneratedDeck(SESSION_ID, session(world))
        return captured.map { it.targetUserId }.toSet()
    }

    private fun session(world: World): PsyncSession {
        val now = Instant.now()
        return PsyncSession(
            id = SESSION_ID,
            partnerLinkId = UUID.randomUUID(),
            initiatorUserId = world.a,
            status = PSYNC_STATUS_READY,
            filters = request(),
            deckSize = 40,
            deckSessionId = null,
            createdAt = now,
            startedAt = null,
            expiresAt = now + Duration.ofHours(1),
            endedAt = null,
            participants =
                listOf(
                    PsyncSessionParticipant(world.a, PSYNC_ROLE_INITIATOR, PSYNC_CONSENT_ACCEPTED, approved = true, started = true),
                    PsyncSessionParticipant(world.b, PSYNC_ROLE_PARTNER, PSYNC_CONSENT_ACCEPTED, approved = true, started = true),
                ),
        )
    }

    private fun coupleRanker(): PsyncDeckGenerator = SwipeServicePsyncDeckGenerator(graph.swipe)

    /** Ranks from the initiator's view only, ignoring the partner: what a regression would look like. */
    private fun oneViewRanker(): PsyncDeckGenerator =
        object : PsyncDeckGenerator {
            private val production = SwipeServicePsyncDeckGenerator(graph.swipe)

            override suspend fun rankCandidates(
                viewerUserId: UUID,
                filters: SwipeDeckRequestDto,
                limit: Int,
            ): List<SwipeCardDto> = production.rankCandidatesForCouple(viewerUserId, viewerUserId, filters, limit)

            override suspend fun rankCandidatesForCouple(
                initiatorUserId: UUID,
                partnerUserId: UUID,
                filters: SwipeDeckRequestDto,
                limit: Int,
            ): List<SwipeCardDto> = rankCandidates(initiatorUserId, filters, limit)
        }

    private fun request() =
        SwipeDeckRequestDto(
            radiusMeters = 100_000,
            limit = 60,
            partnerGenders = listOf("woman"),
            dealbreakers = setOf(SwipeDealbreakerKey.DISTANCE),
        )

    private fun List<SwipeCardDto>.ids(): Set<UUID> = map { UUID.fromString(it.userId) }.toSet()

    // --- fixtures ---------------------------------------------------------------------------------

    private class World(
        val a: UUID,
        val b: UUID,
        val w1: UUID,
        val v1: UUID,
        val x1: UUID,
        val y1: UUID,
    ) {
        val firsts: Set<UUID> = setOf(w1, v1, x1, y1)
    }

    private fun seedWorld(): World {
        val a = person("A", "non_binary")
        val b = person("B", "non_binary")
        dataSource.markProfileVerified(a)
        val (w1, _) = couple("W")
        val (v1, _) = couple("V")
        runSql("UPDATE discovery_settings SET partner_visibility = 'verified_only' WHERE user_id = ?", v1)
        val (x1, x2) = couple("X")
        insertContactBlock(blocker = x2, viewer = b)
        val (y1, y2) = couple("Y")
        insertContactBlock(blocker = y2, viewer = a)
        return World(a = a, b = b, w1 = w1, v1 = v1, x1 = x1, y1 = y1)
    }

    private var nextOffset = 0

    private fun person(
        name: String,
        gender: String,
    ): UUID {
        val id = UUID.randomUUID()
        nextOffset += 1
        dataSource.insertDiscoverableUser(id, name, age = 35, longitude = -122.0841 + nextOffset * 0.0002, latitude = 37.4220)
        runSql("UPDATE profiles SET gender = ? WHERE user_id = ?", gender, id)
        return id
    }

    private fun couple(name: String): Pair<UUID, UUID> {
        val first = person("${name}1", "man")
        val second = person("${name}2", "woman")
        dataSource.insertActiveProfileLink(first, second)
        dataSource.insertActiveProfileLink(second, first)
        return first to second
    }

    private fun insertContactBlock(
        blocker: UUID,
        viewer: UUID,
    ) = runSql(
        "INSERT INTO contact_blocks (id, blocker_user_id, email_token, target_token) VALUES (?, ?, ?, ?)",
        UUID.randomUUID(),
        blocker,
        "email-token-${UUID.randomUUID()}",
        graph.tokens.targetToken(viewer),
    )

    private fun runSql(
        statement: String,
        vararg params: Any,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(statement).use { prepared ->
                params.forEachIndexed { index, value -> prepared.setObject(index + 1, value) }
                prepared.executeUpdate()
            }
        }
    }

    private companion object {
        val SESSION_ID: UUID = UUID.randomUUID()
    }
}
