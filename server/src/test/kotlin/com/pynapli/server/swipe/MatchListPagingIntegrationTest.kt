package com.pynapli.server.swipe

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.JdbcAuthRepository
import com.pynapli.server.auth.security.PasswordHasher
import com.pynapli.server.testsupport.PynapliTestDatabase
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MatchListPagingIntegrationTest {
    private val dataSource = PynapliTestDatabase.dataSource
    private val auth = AuthService(JdbcAuthRepository(dataSource), passwordHasher = PasswordHasher(iterations = 1_000))

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    private suspend fun user(email: String) = UUID.fromString(auth.signupEmail(email, "match-paging-1", "1990-01-01", true, true, true, null, "android", "1.0").user.userId)

    @Test
    fun matchesPageNewestFirstWithoutGapsOrRepeats() =
        runBlocking<Unit> {
            val viewer = user("viewer@example.com")
            // Two matches share a timestamp, so the id tie-break is exercised too.
            val matchIds =
                listOf(5, 4, 4, 2, 1).mapIndexed { index, minutesAgo ->
                    val other = user("other$index@example.com")
                    val id = UUID.randomUUID()
                    val (a, b) = canonicalPair(viewer, other)
                    dataSource.connection.use { connection ->
                        connection
                            .prepareStatement(
                                "INSERT INTO matches (id, user_a, user_b, created_at) VALUES (?, ?, ?, timestamptz '2026-11-13 12:00:00+00' - make_interval(mins => ?))",
                            ).use { statement ->
                                statement.setObject(1, id)
                                statement.setObject(2, a)
                                statement.setObject(3, b)
                                statement.setInt(4, minutesAgo)
                                statement.executeUpdate()
                            }
                    }
                    id
                }

            val actions = JdbcSwipeMatchActions(dataSource)
            val seen = mutableListOf<UUID>()
            var after: MatchPageCursor? = null
            do {
                val page = actions.listMatches(viewer, limit = 2, after = after)
                seen += page.map { it.matchId }
                after = page.lastOrNull()?.let { MatchPageCursor(it.createdAt, it.matchId) }
            } while (page.size == 2)

            assertEquals(matchIds.toSet(), seen.toSet(), "every match once")
            assertEquals(matchIds.size, seen.size, "no repeats")
            assertEquals(matchIds.last(), seen.first(), "newest first")
        }
}
