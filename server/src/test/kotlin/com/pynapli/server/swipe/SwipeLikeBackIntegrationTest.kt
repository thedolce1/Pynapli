package com.pynapli.server.swipe

import com.pynapli.server.grid.insertDiscoverableUser
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.LikeBackRequestDto
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class SwipeLikeBackIntegrationTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest {
            PynapliTestDatabase.reset()
        }

        fun service() =
            SwipeService(
                JdbcSwipeRepository(dataSource),
                blockRepository =
                    com.pynapli.server.safety
                        .JdbcBlockRepository(dataSource),
            )

        "liking back someone who liked you makes the match, even after an earlier pass" {
            runBlocking {
                val viewer = UUID.randomUUID()
                val liker = UUID.randomUUID()
                dataSource.insertDiscoverableUser(viewer, "Viewer", 35, -79.38, 43.65)
                dataSource.insertDiscoverableUser(liker, "Liker", 34, -79.381, 43.651)
                dataSource.insertAction(viewer, liker, "pass")
                dataSource.insertAction(liker, viewer, "pineapple_like")

                val response = service().likeBack(viewer, LikeBackRequestDto(liker.toString()))

                assertNotNull(response.match)
                assertEquals(liker.toString(), response.match!!.matchedUserId)
                assertEquals(listOf("like"), dataSource.liveActions(viewer, liker))
                // Repeating it is harmless: same match, no second like.
                assertEquals(response.match!!.matchId, service().likeBack(viewer, LikeBackRequestDto(liker.toString())).match?.matchId)
                assertEquals(listOf("like"), dataSource.liveActions(viewer, liker))
            }
        }

        "there is nothing to like back without a live like from them" {
            runBlocking {
                val viewer = UUID.randomUUID()
                val other = UUID.randomUUID()
                dataSource.insertDiscoverableUser(viewer, "Viewer", 35, -79.38, 43.65)
                dataSource.insertDiscoverableUser(other, "Other", 34, -79.381, 43.651)

                val error = assertFailsWith<SwipeException> { service().likeBack(viewer, LikeBackRequestDto(other.toString())) }
                assertEquals("LIKE_BACK_NOT_AVAILABLE", error.code)
            }
        }

        "a blocked pair can't like back" {
            runBlocking {
                val viewer = UUID.randomUUID()
                val liker = UUID.randomUUID()
                dataSource.insertDiscoverableUser(viewer, "Viewer", 35, -79.38, 43.65)
                dataSource.insertDiscoverableUser(liker, "Liker", 34, -79.381, 43.651)
                dataSource.insertAction(liker, viewer, "like")
                dataSource.connection.use { c ->
                    c.prepareStatement("INSERT INTO user_blocks (id, blocker_user_id, blocked_user_id) VALUES (?, ?, ?)").use { s ->
                        s.setObject(1, UUID.randomUUID())
                        s.setObject(2, liker)
                        s.setObject(3, viewer)
                        s.executeUpdate()
                    }
                }

                val error = assertFailsWith<SwipeException> { service().likeBack(viewer, LikeBackRequestDto(liker.toString())) }
                assertEquals("INVALID_SWIPE_TARGET", error.code)
            }
        }
    })

private fun DataSource.insertAction(
    actor: UUID,
    target: UUID,
    action: String,
) {
    connection.use { c ->
        c.prepareStatement("INSERT INTO swipe_actions (id, actor_user_id, target_user_id, action) VALUES (?, ?, ?, ?)").use { s ->
            s.setObject(1, UUID.randomUUID())
            s.setObject(2, actor)
            s.setObject(3, target)
            s.setString(4, action)
            s.executeUpdate()
        }
    }
}

private fun DataSource.liveActions(
    actor: UUID,
    target: UUID,
): List<String> =
    connection.use { c ->
        c.prepareStatement("SELECT action FROM swipe_actions WHERE actor_user_id = ? AND target_user_id = ? AND rewound_at IS NULL").use { s ->
            s.setObject(1, actor)
            s.setObject(2, target)
            s.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }
