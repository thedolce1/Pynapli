package com.pynapli.server.swipe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.postgresql.util.PGobject
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

class JdbcSavedSwipeFilterRepository(
    private val dataSource: DataSource
) : SavedSwipeFilterRepository {

    override suspend fun list(userId: UUID): List<SavedSwipeFilterRecord> = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT id, user_id, name, payload::text AS payload, created_at, updated_at " +
                    "FROM saved_swipe_filters WHERE user_id = ? ORDER BY created_at DESC, id ASC"
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
    }

    override suspend fun create(
        id: UUID,
        userId: UUID,
        name: String,
        payloadJson: String
    ): SavedSwipeFilterRecord = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            try {
                connection.prepareStatement(
                    "INSERT INTO saved_swipe_filters (id, user_id, name, payload) VALUES (?, ?, ?, ?::jsonb) " +
                        "RETURNING id, user_id, name, payload::text AS payload, created_at, updated_at"
                ).use { statement ->
                    statement.setObject(1, id)
                    statement.setObject(2, userId)
                    statement.setString(3, name)
                    statement.setObject(4, PGobject().apply { type = "jsonb"; value = payloadJson })
                    statement.executeQuery().use { rs ->
                        check(rs.next()) { "INSERT ... RETURNING returned no row" }
                        rs.toRecord()
                    }
                }
            } catch (e: SQLException) {
                if (e.sqlState == UNIQUE_VIOLATION_SQLSTATE) {
                    throw SavedSwipeFilterDuplicateNameException()
                }
                throw e
            }
        }
    }

    override suspend fun delete(userId: UUID, id: UUID): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "DELETE FROM saved_swipe_filters WHERE user_id = ? AND id = ?"
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setObject(2, id)
                statement.executeUpdate() > 0
            }
        }
    }

    override suspend fun countForUser(userId: UUID): Int = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT count(*)::integer FROM saved_swipe_filters WHERE user_id = ?"
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }
    }

    private fun java.sql.ResultSet.toRecord() = SavedSwipeFilterRecord(
        id = getObject("id", UUID::class.java),
        userId = getObject("user_id", UUID::class.java),
        name = getString("name"),
        payloadJson = getString("payload"),
        createdAt = getTimestamp("created_at").toInstant(),
        updatedAt = getTimestamp("updated_at").toInstant()
    )

    companion object {
        private const val UNIQUE_VIOLATION_SQLSTATE = "23505"
    }
}

class SavedSwipeFilterDuplicateNameException :
    RuntimeException("A saved swipe filter with that name already exists")
