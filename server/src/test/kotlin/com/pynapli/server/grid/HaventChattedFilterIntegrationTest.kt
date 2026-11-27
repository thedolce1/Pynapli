package com.pynapli.server.grid

import com.pynapli.server.discovery.DiscoveryCursorCodec
import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.GridQueryRequestDto
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

/**
 * "Haven't chatted (24 h)" (plan G2-4): hides people the viewer exchanged a direct message with in the
 * last 24 hours, either direction. An inbound message counts only once it is visible to the viewer, so
 * a held message never makes a card vanish. Older and deleted messages never count.
 */
class HaventChattedFilterIntegrationTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest {
            PynapliTestDatabase.reset()
        }

        "haventChattedOnly hides exactly the people with a message in the last 24 hours" {
            runBlocking {
                val viewer = UUID.randomUUID()
                val sentTo = UUID.randomUUID()
                val heardFrom = UUID.randomUUID()
                val heldInbound = UUID.randomUUID()
                val oldChat = UUID.randomUUID()
                val deletedOnly = UUID.randomUUID()
                val stranger = UUID.randomUUID()
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                listOf(sentTo, heardFrom, heldInbound, oldChat, deletedOnly, stranger).forEachIndexed { index, id ->
                    dataSource.insertDiscoverableUser(id, "P$index", age = 30 + index, longitude = -122.0836, latitude = 37.4226)
                }
                val now = Instant.now()
                // The viewer wrote 2 h ago (their own message counts whatever its moderation state).
                dataSource.insertDirectMessage(viewer, sentTo, sender = viewer, at = now.minus(Duration.ofHours(2)), status = "held")
                // They wrote to the viewer 3 h ago, visible: counts.
                dataSource.insertDirectMessage(heardFrom, viewer, sender = heardFrom, at = now.minus(Duration.ofHours(3)))
                // Held inbound: the viewer never saw it, so the card must not vanish.
                dataSource.insertDirectMessage(viewer, heldInbound, sender = heldInbound, at = now.minus(Duration.ofHours(1)), status = "held")
                // Last message 30 h ago: outside the window.
                dataSource.insertDirectMessage(viewer, oldChat, sender = viewer, at = now.minus(Duration.ofHours(30)))
                // Only a deleted message.
                dataSource.insertDirectMessage(viewer, deletedOnly, sender = viewer, at = now.minus(Duration.ofHours(1)), deleted = true)

                val repository = JdbcGridRepository(dataSource)
                val filtered =
                    repository
                        .queryNearby(viewer, 100_000, 20, DiscoveryFilters(haventChattedOnly = true))
                        .cards
                        .map { it.userId }
                        .toSet()
                assertEquals(setOf(heldInbound, oldChat, deletedOnly, stranger), filtered)

                val everyone = repository.queryNearby(viewer, 100_000, 20, DiscoveryFilters()).cards.map { it.userId }.toSet()
                assertEquals(setOf(sentTo, heardFrom, heldInbound, oldChat, deletedOnly, stranger), everyone)
            }
        }

        "the filter is per viewer: another viewer's chats never hide anyone" {
            runBlocking {
                val viewer = UUID.randomUUID()
                val other = UUID.randomUUID()
                val target = UUID.randomUUID()
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(other, "Other", age = 39, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(target, "Target", age = 33, longitude = -122.0836, latitude = 37.4226)
                dataSource.insertDirectMessage(other, target, sender = other, at = Instant.now().minus(Duration.ofMinutes(5)))

                val repository = JdbcGridRepository(dataSource)
                val filters = DiscoveryFilters(haventChattedOnly = true)
                // The other viewer asks first; a shared window would then leak their chats to the viewer.
                val forOther = repository.queryNearby(other, 100_000, 20, filters).cards.map { it.userId }
                val forViewer = repository.queryNearby(viewer, 100_000, 20, filters).cards.map { it.userId }

                assertFalse(target in forOther)
                assertEquals(true, target in forViewer)
                assertFalse(filters.isGridRankCacheEligible())
            }
        }

        "it rides through the Grid request, is in the cursor scope and saved presets keep it" {
            runBlocking {
                val viewer = UUID.randomUUID()
                val chatted = UUID.randomUUID()
                dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
                dataSource.insertDiscoverableUser(chatted, "Chatted", age = 33, longitude = -122.0836, latitude = 37.4226)
                dataSource.insertDirectMessage(viewer, chatted, sender = chatted, at = Instant.now().minus(Duration.ofMinutes(30)))

                val service = GridService(JdbcGridRepository(dataSource))
                val on = service.queryGrid(viewer, GridQueryRequestDto(radiusMeters = 100_000, haventChattedOnly = true))
                assertEquals(emptyList<String>(), on.items.map { it.userId })
                val off = service.queryGrid(viewer, GridQueryRequestDto(radiusMeters = 100_000))
                assertEquals(listOf(chatted.toString()), off.items.map { it.userId })

                fun scope(filters: DiscoveryFilters) =
                    DiscoveryCursorCodec.gridScopeHash(viewer, 50_000, 60, filters, onlineOnly = false)
                assertNotEquals(scope(DiscoveryFilters()), scope(DiscoveryFilters(haventChattedOnly = true)))
                assertNotEquals(
                    DiscoveryFilters().gridRankCacheKey(),
                    DiscoveryFilters(haventChattedOnly = true).gridRankCacheKey(),
                )
            }
        }
    })

/** A direct conversation between [a] and [b] (created if needed) holding one message from [sender] at [at]. */
private fun DataSource.insertDirectMessage(
    a: UUID,
    b: UUID,
    sender: UUID,
    at: Instant,
    status: String = "visible",
    deleted: Boolean = false,
) {
    connection.use { connection ->
        val existing =
            connection
                .prepareStatement(
                    """
                    SELECT conversation_id FROM direct_conversations
                    WHERE LEAST(user_a_id, user_b_id) = LEAST(?::uuid, ?::uuid)
                      AND GREATEST(user_a_id, user_b_id) = GREATEST(?::uuid, ?::uuid)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, a)
                    statement.setObject(2, b)
                    statement.setObject(3, a)
                    statement.setObject(4, b)
                    statement.executeQuery().use { rs -> if (rs.next()) rs.getObject(1, UUID::class.java) else null }
                }
        val conversationId = existing ?: UUID.randomUUID()
        if (existing == null) {
            connection
                .prepareStatement("INSERT INTO conversations (id, kind, origin, created_by_user_id) VALUES (?, 'direct', 'grid', ?)")
                .use { statement ->
                    statement.setObject(1, conversationId)
                    statement.setObject(2, a)
                    statement.executeUpdate()
                }
            connection
                .prepareStatement("INSERT INTO direct_conversations (conversation_id, user_a_id, user_b_id) VALUES (?, ?, ?)")
                .use { statement ->
                    statement.setObject(1, conversationId)
                    statement.setObject(2, a)
                    statement.setObject(3, b)
                    statement.executeUpdate()
                }
            listOf(a, b).forEach { member ->
                connection
                    .prepareStatement("INSERT INTO conversation_participants (conversation_id, user_id) VALUES (?, ?)")
                    .use { statement ->
                        statement.setObject(1, conversationId)
                        statement.setObject(2, member)
                        statement.executeUpdate()
                    }
            }
        }
        connection
            .prepareStatement(
                """
                INSERT INTO messages (id, conversation_id, sender_user_id, body, moderation_status, created_at, deleted_at)
                VALUES (?, ?, ?, 'hi', ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, conversationId)
                statement.setObject(3, sender)
                statement.setString(4, status)
                statement.setTimestamp(5, Timestamp.from(at))
                statement.setTimestamp(6, if (deleted) Timestamp.from(at) else null)
                statement.executeUpdate()
            }
    }
}
