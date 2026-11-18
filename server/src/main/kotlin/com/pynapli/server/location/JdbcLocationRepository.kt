package com.pynapli.server.location

import com.pynapli.server.db.withConnection
import java.util.UUID
import javax.sql.DataSource

class JdbcLocationRepository(
    private val dataSource: DataSource,
) : LocationRepository {
    override suspend fun upsertCurrentLocation(
        userId: UUID,
        update: LocationUpdate,
    ): CurrentLocationRecord =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO user_locations (
                        user_id, geom, geohash, city, region, country,
                        location_source, accuracy_meters, updated_at
                    )
                    VALUES (
                        ?,
                        ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                        ?, ?, ?, ?, ?, ?, now()
                    )
                    ON CONFLICT (user_id) DO UPDATE SET
                        geom = excluded.geom,
                        geohash = excluded.geohash,
                        city = excluded.city,
                        region = excluded.region,
                        country = excluded.country,
                        location_source = excluded.location_source,
                        accuracy_meters = excluded.accuracy_meters,
                        updated_at = now()
                    RETURNING updated_at, accuracy_meters, location_source, geohash, city, region, country
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setDouble(2, update.longitude)
                    statement.setDouble(3, update.latitude)
                    statement.setString(4, update.geohash)
                    statement.setString(5, update.city)
                    statement.setString(6, update.region)
                    statement.setString(7, update.country)
                    statement.setString(8, update.source)
                    update.accuracyMeters?.let { statement.setInt(9, it) } ?: statement.setNull(9, java.sql.Types.INTEGER)

                    statement.executeQuery().use { rs ->
                        rs.next()
                        CurrentLocationRecord(
                            updatedAt = rs.getTimestamp("updated_at").toInstant(),
                            accuracyMeters = rs.getInt("accuracy_meters").takeUnless { rs.wasNull() },
                            source = rs.getString("location_source"),
                            geohash = rs.getString("geohash"),
                            city = rs.getString("city"),
                            region = rs.getString("region"),
                            country = rs.getString("country"),
                        )
                    }
                }
        }

    override suspend fun getCurrentCoordinates(userId: UUID): CurrentCoordinatesRecord? =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT ST_Y(geom::geometry) AS latitude,
                           ST_X(geom::geometry) AS longitude,
                           location_source,
                           updated_at,
                           EXISTS (
                               SELECT 1
                               FROM travel_locations tl
                               WHERE tl.user_id = user_locations.user_id
                                 AND tl.status = 'active'
                                 AND tl.active_until IS NULL
                           ) AS travel_session_active
                    FROM user_locations
                    WHERE user_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.executeQuery().use { rs ->
                        if (!rs.next()) {
                            null
                        } else {
                            CurrentCoordinatesRecord(
                                latitude = rs.getDouble("latitude"),
                                longitude = rs.getDouble("longitude"),
                                source = rs.getString("location_source"),
                                updatedAt = rs.getTimestamp("updated_at").toInstant(),
                                travelSessionActive = rs.getBoolean("travel_session_active"),
                            )
                        }
                    }
                }
        }
}
