package com.pynapli.server.account

import com.pynapli.server.db.withConnection
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock
import java.util.UUID
import javax.sql.DataSource

/** The serialized archive bytes plus a suggested filename + content type. */
data class ExportArchive(
    val filename: String,
    val contentType: String,
    val bytes: ByteArray,
)

interface AccountDataExportArchiver {
    suspend fun buildArchive(userId: UUID): ExportArchive
}

/**
 * Builds a GDPR-style JSON archive of a user's **own** data: account basics, profile,
 * discovery/privacy settings, the messages they sent, and the activity directed at them. Other
 * users' private content is not included. Rows are serialized generically (column -> string) so the
 * export stays correct as columns evolve; sensitive auth columns (password/email hashes, tokens)
 * live in other tables and are never queried here.
 */
class JdbcAccountDataExportArchiver(
    private val dataSource: DataSource,
    private val clock: Clock = Clock.systemUTC(),
) : AccountDataExportArchiver {
    override suspend fun buildArchive(userId: UUID): ExportArchive =
        dataSource.withConnection { connection ->
            val document =
                buildJsonObject {
                    put("exportedAt", clock.instant().toString())
                    put("userId", userId.toString())
                    put("account", connection.queryRows("SELECT id, created_at FROM users WHERE id = ?", userId))
                    put("profile", connection.queryRows("SELECT * FROM profiles WHERE user_id = ?", userId))
                    put("discoverySettings", connection.queryRows("SELECT * FROM discovery_settings WHERE user_id = ?", userId))
                    put(
                        "sentMessages",
                        connection.queryRows("SELECT * FROM messages WHERE sender_user_id = ? ORDER BY created_at", userId),
                    )
                    put(
                        "activity",
                        connection.queryRows("SELECT * FROM activity_events WHERE recipient_user_id = ? ORDER BY occurred_at", userId),
                    )
                }
            ExportArchive(
                filename = "pynapli-data-export-$userId.json",
                contentType = "application/json",
                bytes = PRETTY_JSON.encodeToString(JsonObject.serializer(), document).encodeToByteArray(),
            )
        }

    private fun Connection.queryRows(
        sql: String,
        userId: UUID,
    ): JsonArray =
        prepareStatement(sql).use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { rs -> rs.rowsAsJson() }
        }

    private fun ResultSet.rowsAsJson(): JsonArray {
        val columns = (1..metaData.columnCount).map { metaData.getColumnLabel(it) }
        val rows =
            buildList {
                while (next()) {
                    add(
                        buildJsonObject {
                            columns.forEachIndexed { index, name -> put(name, getString(index + 1)) }
                        },
                    )
                }
            }
        return JsonArray(rows)
    }

    companion object {
        private val PRETTY_JSON = kotlinx.serialization.json.Json { prettyPrint = true }
    }
}
