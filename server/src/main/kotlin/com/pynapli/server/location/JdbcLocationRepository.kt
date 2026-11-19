package com.pynapli.server.location

import com.pynapli.server.db.withConnection
import java.util.UUID
import javax.sql.DataSource

class JdbcLocationRepository(
    private val dataSource: DataSource,
) : LocationRepository {
    /**
     * The ONE writer of `user_locations` (location privacy): the point is snapped to its cell in SQL
     * with `pynapli_location_cell`, so whatever a caller passes, only the cell centre is stored. The
     * accuracy is always NULL (it described a precise fix that is no longer kept) and the geohash is
     * the stored cell's label (`pynapli_location_cell_label`, V119), derived from the same snapped point.
     */
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
                    SELECT ?, cell.geom, pynapli_location_cell_label(cell.geom), ?, ?, ?, ?, NULL::integer, now()
                    FROM (
                        SELECT pynapli_location_cell(ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography) AS geom
                    ) cell
                    ON CONFLICT (user_id) DO UPDATE SET
                        geom = excluded.geom,
                        geohash = excluded.geohash,
                        city = excluded.city,
                        region = excluded.region,
                        country = excluded.country,
                        location_source = excluded.location_source,
                        accuracy_meters = NULL,
                        updated_at = now()
                    RETURNING updated_at, location_source, city, region, country
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setString(2, update.city)
                    statement.setString(3, update.region)
                    statement.setString(4, update.country)
                    statement.setString(5, update.source)
                    statement.setDouble(6, update.longitude)
                    statement.setDouble(7, update.latitude)

                    statement.executeQuery().use { rs ->
                        rs.next()
                        CurrentLocationRecord(
                            updatedAt = rs.getTimestamp("updated_at").toInstant(),
                            source = rs.getString("location_source"),
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
