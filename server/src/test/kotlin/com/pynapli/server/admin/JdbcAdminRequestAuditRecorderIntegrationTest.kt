package com.pynapli.server.admin

import com.pynapli.server.testsupport.PynapliTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Before
import org.junit.Test
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JdbcAdminRequestAuditRecorderIntegrationTest {
    private val dataSource: DataSource = PynapliTestDatabase.dataSource

    @Before
    fun reset() {
        PynapliTestDatabase.reset()
    }

    @Test
    fun writesRequestLevelAdminAuditRow() =
        runBlocking {
            val recorder = JdbcAdminRequestAuditRecorder(dataSource)

            recorder.record(actorId = "ops-1", method = "POST", path = "/v1/admin/reports/x/resolve", status = 200)

            val row = dataSource.latestAdminRequestRow()
            assertEquals("ops-1", row.actorId)
            assertEquals("admin_request", row.action)
            assertEquals("route", row.targetType)
            assertNull(row.reason)
            // Parse the metadata rather than string-match: Postgres jsonb::text reorders keys and
            // adds spaces after colons, so a raw substring check would be brittle.
            val metadata = Json.parseToJsonElement(row.metadata).jsonObject
            assertEquals("POST", metadata.getValue("method").jsonPrimitive.content)
            assertEquals("/v1/admin/reports/x/resolve", metadata.getValue("path").jsonPrimitive.content)
            assertEquals(200, metadata.getValue("status").jsonPrimitive.int)
        }
}

private data class AdminAuditRow(
    val actorId: String,
    val action: String,
    val targetType: String,
    val reason: String?,
    val metadata: String,
)

private fun DataSource.latestAdminRequestRow(): AdminAuditRow =
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                SELECT admin_actor_id, action, target_type, reason, metadata::text AS metadata
                FROM admin_audit_log
                WHERE action = 'admin_request'
                ORDER BY created_at DESC
                LIMIT 1
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { rs ->
                    check(rs.next()) { "expected an admin_request audit row" }
                    AdminAuditRow(
                        actorId = rs.getString("admin_actor_id"),
                        action = rs.getString("action"),
                        targetType = rs.getString("target_type"),
                        reason = rs.getString("reason"),
                        metadata = rs.getString("metadata"),
                    )
                }
            }
    }
