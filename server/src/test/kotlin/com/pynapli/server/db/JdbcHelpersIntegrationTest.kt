package com.pynapli.server.db

import com.pynapli.server.testsupport.PynapliTestDatabase
import kotlinx.coroutines.runBlocking
import java.sql.Connection
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class JdbcHelpersIntegrationTest {
    private val dataSource = PynapliTestDatabase.dataSource

    @BeforeTest
    fun createScratchTable() {
        PynapliTestDatabase.reset()
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("CREATE TABLE IF NOT EXISTS jdbc_helpers_probe (n INT NOT NULL)") }
            connection.createStatement().use { it.execute("DELETE FROM jdbc_helpers_probe") }
        }
    }

    @Test
    fun committedWorkIsVisibleAndAutoCommitIsRestored() =
        runBlocking {
            val autoCommitAfter =
                dataSource.withConnection { connection ->
                    connection.inTransaction { insert(1) }
                    connection.autoCommit
                }

            assertTrue(autoCommitAfter, "the pooled connection must go back with autoCommit restored")
            assertEquals(1, rowCount())
        }

    @Test
    fun anyThrowableRollsBackAndRestoresAutoCommit() =
        runBlocking {
            var autoCommitAfter = false
            assertFailsWith<IllegalStateException> {
                dataSource.withConnection { connection ->
                    try {
                        connection.inTransaction {
                            insert(1)
                            error("boom")
                        }
                    } finally {
                        autoCommitAfter = connection.autoCommit
                    }
                }
            }

            assertTrue(autoCommitAfter)
            assertEquals(0, rowCount(), "the insert before the failure must be rolled back")
        }

    // Hand-rolled transactions that were converted keep their own `rollback(); return x` exits:
    // inTransaction is inline, so the return leaves the enclosing function without committing.
    @Test
    fun explicitRollbackThenNonLocalReturnLeavesNothingCommitted() =
        runBlocking {
            val result = dataSource.withConnection { connection -> insertThenBailOut(connection) }

            assertEquals("bailed", result)
            assertEquals(0, rowCount())
        }

    @Test
    fun withConnectionRunsOffTheCallersThread() =
        runBlocking {
            val caller = Thread.currentThread()
            val worker = dataSource.withConnection { Thread.currentThread() }

            assertNotEquals(caller, worker, "JDBC must not block the calling (event-loop) thread")
        }

    private fun insertThenBailOut(connection: Connection): String {
        connection.inTransaction {
            insert(1)
            rollback()
            return "bailed"
        }
    }

    private fun Connection.insert(n: Int) {
        prepareStatement("INSERT INTO jdbc_helpers_probe (n) VALUES (?)").use {
            it.setInt(1, n)
            it.executeUpdate()
        }
    }

    private fun rowCount(): Int =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM jdbc_helpers_probe").use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }
}
