package com.pynapli.server.savedfilters

import com.pynapli.server.db.withConnection
import org.postgresql.util.PGobject
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Which discovery surface a saved filter belongs to. Each surface keeps its own table
 * (`saved_grid_filters`, `saved_swipe_filters`); both tables have the same columns.
 */
enum class SavedFilterSurface(
    internal val table: String,
) {
    Grid("saved_grid_filters"),
    Swipe("saved_swipe_filters"),
}

interface SavedFilterRepository {
    suspend fun list(userId: UUID): List<SavedFilterRecord>

    suspend fun create(
        id: UUID,
        userId: UUID,
        name: String,
        payloadJson: String,
    ): SavedFilterRecord

    suspend fun delete(
        userId: UUID,
        id: UUID,
    ): Boolean

    suspend fun countForUser(userId: UUID): Int
}

data class SavedFilterRecord(
    val id: UUID,
    val userId: UUID,
    val name: String,
    val payloadJson: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

class SavedFilterDuplicateNameException : RuntimeException("A saved filter with that name already exists")

/** One JDBC store for both surfaces; [surface] picks the table (a fixed enum value, never user input). */
class JdbcSavedFilterRepository(
    private val dataSource: DataSource,
    surface: SavedFilterSurface,
) : SavedFilterRepository {
    private val table = surface.table

    override suspend fun list(userId: UUID): List<SavedFilterRecord> =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    "SELECT id, user_id, name, payload::text AS payload, created_at, updated_at " +
                        "FROM $table WHERE user_id = ? ORDER BY created_at DESC, id ASC",
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(rs.toRecord())
                            }
                        }
                    }
                }
        }

    override suspend fun create(
        id: UUID,
        userId: UUID,
        name: String,
        payloadJson: String,
    ): SavedFilterRecord =
        dataSource.withConnection { connection ->
            try {
                connection
                    .prepareStatement(
                        "INSERT INTO $table (id, user_id, name, payload) VALUES (?, ?, ?, ?::jsonb) " +
                            "RETURNING id, user_id, name, payload::text AS payload, created_at, updated_at",
                    ).use { statement ->
                        statement.setObject(1, id)
                        statement.setObject(2, userId)
                        statement.setString(3, name)
                        statement.setObject(
                            4,
                            PGobject().apply {
                                type = "jsonb"
                                value = payloadJson
                            },
                        )
                        statement.executeQuery().use { rs ->
                            check(rs.next()) { "INSERT ... RETURNING returned no row" }
                            rs.toRecord()
                        }
                    }
            } catch (e: SQLException) {
                if (e.sqlState == UNIQUE_VIOLATION_SQLSTATE) {
                    throw SavedFilterDuplicateNameException()
                }
                throw e
            }
        }

    override suspend fun delete(
        userId: UUID,
        id: UUID,
    ): Boolean =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement("DELETE FROM $table WHERE user_id = ? AND id = ?")
                .use { statement ->
                    statement.setObject(1, userId)
                    statement.setObject(2, id)
                    statement.executeUpdate() > 0
                }
        }

    override suspend fun countForUser(userId: UUID): Int =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement("SELECT count(*)::integer FROM $table WHERE user_id = ?")
                .use { statement ->
                    statement.setObject(1, userId)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
        }

    private fun java.sql.ResultSet.toRecord() =
        SavedFilterRecord(
            id = getObject("id", UUID::class.java),
            userId = getObject("user_id", UUID::class.java),
            name = getString("name"),
            payloadJson = getString("payload"),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
        )

    private companion object {
        const val UNIQUE_VIOLATION_SQLSTATE = "23505"
    }
}
