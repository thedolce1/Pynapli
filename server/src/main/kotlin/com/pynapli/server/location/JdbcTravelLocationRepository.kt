package com.pynapli.server.location

import com.pynapli.server.db.inTransaction
import com.pynapli.server.db.withConnection
import com.pynapli.shared.network.dto.TravelLocationStatus
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcTravelLocationRepository(
    private val dataSource: DataSource,
) : TravelLocationRepository {
    override suspend fun findActive(userId: UUID): TravelLocationRecord? =
        dataSource.withConnection { connection ->
            connection.selectActive(userId)
        }

    override suspend fun setActive(
        userId: UUID,
        request: TravelLocationUpsert,
        now: Instant,
    ): TravelLocationRecord =
        dataSource.withConnection { connection ->
            connection.inTransaction {
                // Clear any existing active row so the partial unique index lets us insert.
                connection.clearActiveRow(userId, now)

                val newId = UUID.randomUUID()
                connection
                    .prepareStatement(
                        """
                        INSERT INTO travel_locations (
                            id, user_id, city, region, country, geom,
                            active_from, status
                        )
                        VALUES (
                            ?, ?, ?, ?, ?,
                            ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                            ?, ?
                        )
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, newId)
                        statement.setObject(2, userId)
                        statement.setString(3, request.city)
                        statement.setString(4, request.region)
                        statement.setString(5, request.country)
                        statement.setDouble(6, request.longitude)
                        statement.setDouble(7, request.latitude)
                        statement.setTimestamp(8, Timestamp.from(now))
                        statement.setString(9, TravelLocationStatus.ACTIVE)
                        statement.executeUpdate()
                    }

                val record =
                    connection.selectActive(userId)
                        ?: error("INSERT ... travel_locations returned no readable active row")
                connection.commit()
                record
            }
        }

    override suspend fun clearActive(
        userId: UUID,
        now: Instant,
    ): Boolean =
        dataSource.withConnection { connection ->
            connection.clearActiveRow(userId, now) > 0
        }

    override suspend fun countActivationsSince(
        userId: UUID,
        since: Instant,
    ): Int =
        dataSource.withConnection { connection ->
            // Uses idx_travel_locations_user_active_from (V50) for an
            // index-only count over the recent window.
            connection
                .prepareStatement(
                    """
                    SELECT count(*)::integer
                    FROM travel_locations
                    WHERE user_id = ?
                      AND active_from >= ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setTimestamp(2, Timestamp.from(since))
                    statement.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
        }

    private fun Connection.clearActiveRow(
        userId: UUID,
        now: Instant,
    ): Int =
        prepareStatement(
            """
            UPDATE travel_locations
            SET status = '${TravelLocationStatus.CLEARED}',
                active_until = ?
            WHERE user_id = ?
              AND status = '${TravelLocationStatus.ACTIVE}'
              AND active_until IS NULL
            """.trimIndent(),
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(now))
            statement.setObject(2, userId)
            statement.executeUpdate()
        }

    private fun Connection.selectActive(userId: UUID): TravelLocationRecord? =
        prepareStatement(
            """
            SELECT id, user_id, city, region, country,
                   ST_Y(geom::geometry) AS latitude,
                   ST_X(geom::geometry) AS longitude,
                   active_from, active_until, status
            FROM travel_locations
            WHERE user_id = ?
              AND status = '${TravelLocationStatus.ACTIVE}'
              AND active_until IS NULL
            ORDER BY active_from DESC
            LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { rs ->
                if (!rs.next()) return@use null
                TravelLocationRecord(
                    id = rs.getObject("id", UUID::class.java),
                    userId = rs.getObject("user_id", UUID::class.java),
                    city = rs.getString("city"),
                    region = rs.getString("region"),
                    country = rs.getString("country"),
                    latitude = rs.getDouble("latitude"),
                    longitude = rs.getDouble("longitude"),
                    activeFrom = rs.getTimestamp("active_from").toInstant(),
                    activeUntil = rs.getTimestamp("active_until")?.toInstant(),
                    status = rs.getString("status"),
                )
            }
        }
}
