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
    })

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
