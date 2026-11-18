package com.pynapli.server.subscriptions

import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.server.testsupport.raceTogether
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** A double-tap on Boost must start one Boost and spend one, however close together the taps land. */
class BoostActivationConcurrencyIntegrationTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource
        val iterations = 40

        beforeTest {
            PynapliTestDatabase.reset()
        }

        "two Boost activations at the same moment spend one Boost and start one" {
            runBlocking {
                val repository = JdbcSubscriptionRepository(dataSource)
                val now = Instant.now()
                var wrong = 0
                repeat(iterations) {
                    val user = UUID.randomUUID()
                    dataSource.insertUserWithBoosts(user, quantity = 5)
                    raceTogether(
                        { repository.activateBoost(user, now, Duration.ofMinutes(30)) },
                        { repository.activateBoost(user, now, Duration.ofMinutes(30)) },
                    )
                    if (dataSource.boostsLeft(user) != 4 || dataSource.activeBoostCount(user) != 1) wrong++
                }
                assertEquals(0, wrong, "iterations out of $iterations that did not spend exactly one Boost")
            }
        }

        "the database itself refuses a second active Boost for the same person" {
            val user = UUID.randomUUID()
            dataSource.insertUserWithBoosts(user, quantity = 0)
            val now = Instant.now()
            dataSource.insertActiveBoostRow(user, now, now.plusSeconds(1_800))

            val refused = runCatching { dataSource.insertActiveBoostRow(user, now, now.plusSeconds(1_800)) }

            assertTrue(refused.isFailure, "a second active Boost row should violate the one-active-Boost index")
            assertEquals(1, dataSource.activeBoostCount(user))
        }

        "a Boost that ran out still lets the next one start, and spends for it" {
            runBlocking {
                val repository = JdbcSubscriptionRepository(dataSource)
                val user = UUID.randomUUID()
                dataSource.insertUserWithBoosts(user, quantity = 5)
                val now = Instant.now()

                val first = repository.activateBoost(user, now, Duration.ofMinutes(30))!!
                // Nothing has swept the first one: it is past its end but still says 'active'.
                val second = repository.activateBoost(user, now.plus(Duration.ofMinutes(31)), Duration.ofMinutes(30))!!

                assertTrue(first.consumedInventory)
                assertTrue(second.consumedInventory)
                assertNotEquals(first.activation.activationId, second.activation.activationId)
                assertEquals(3, dataSource.boostsLeft(user))
                assertEquals(1, dataSource.activeBoostCount(user))
            }
        }

        "two activations at the same moment after a Boost ran out start one new Boost and spend one" {
            runBlocking {
                val repository = JdbcSubscriptionRepository(dataSource)
                val start = Instant.now()
                val later = start.plus(Duration.ofMinutes(31))
                var wrong = 0
                repeat(iterations) {
                    val user = UUID.randomUUID()
                    dataSource.insertUserWithBoosts(user, quantity = 5)
                    repository.activateBoost(user, start, Duration.ofMinutes(30))
                    raceTogether(
                        { repository.activateBoost(user, later, Duration.ofMinutes(30)) },
                        { repository.activateBoost(user, later, Duration.ofMinutes(30)) },
                    )
                    if (dataSource.boostsLeft(user) != 3 || dataSource.activeBoostCount(user) != 1) wrong++
                }
                assertEquals(0, wrong, "iterations out of $iterations that did not restart exactly one Boost")
            }
        }
    })

private fun DataSource.insertActiveBoostRow(
    userId: UUID,
    startsAt: Instant,
    endsAt: Instant,
) {
    connection.use { c ->
        c
            .prepareStatement(
                """
                INSERT INTO boost_activations (id, user_id, boost_type, source, starts_at, ends_at, status)
                VALUES (?, ?, 'boost', 'dev', ?, ?, 'active')
                """.trimIndent(),
            ).use { s ->
                s.setObject(1, UUID.randomUUID())
                s.setObject(2, userId)
                s.setTimestamp(3, java.sql.Timestamp.from(startsAt))
                s.setTimestamp(4, java.sql.Timestamp.from(endsAt))
                s.executeUpdate()
            }
    }
}

private fun DataSource.insertUserWithBoosts(
    userId: UUID,
    quantity: Int,
) {
    connection.use { c ->
        c.prepareStatement("INSERT INTO users (id, status) VALUES (?, 'active')").use { s ->
            s.setObject(1, userId)
            s.executeUpdate()
        }
        c
            .prepareStatement(
                "INSERT INTO premium_inventory (id, user_id, inventory_type, quantity, source) VALUES (?, ?, 'boost', ?, 'dev')",
            ).use { s ->
                s.setObject(1, UUID.randomUUID())
                s.setObject(2, userId)
                s.setInt(3, quantity)
                s.executeUpdate()
            }
    }
}

private fun DataSource.boostsLeft(userId: UUID): Int =
    connection.use { c ->
        c
            .prepareStatement(
                "SELECT COALESCE(sum(quantity), 0)::integer FROM premium_inventory WHERE user_id = ? AND inventory_type = 'boost'",
            ).use { s ->
                s.setObject(1, userId)
                s.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
    }

private fun DataSource.activeBoostCount(userId: UUID): Int =
    connection.use { c ->
        c.prepareStatement("SELECT COUNT(*) FROM boost_activations WHERE user_id = ? AND status = 'active'").use { s ->
            s.setObject(1, userId)
            s.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }
