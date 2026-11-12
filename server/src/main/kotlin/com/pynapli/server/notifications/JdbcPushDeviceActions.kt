package com.pynapli.server.notifications

import com.pynapli.server.db.withConnection
import java.util.UUID
import javax.sql.DataSource

internal class JdbcPushDeviceActions(
    private val dataSource: DataSource,
) {
    suspend fun upsertPushDevice(
        userId: UUID,
        registration: PushDeviceRegistration,
        deviceIdFactory: () -> UUID,
    ): PushDeviceRecord =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO push_devices (
                        id,
                        user_id,
                        platform,
                        device_token,
                        app_version,
                        locale,
                        timezone,
                        notifications_enabled,
                        session_id
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (platform, device_token) DO UPDATE
                    SET user_id = EXCLUDED.user_id,
                        app_version = EXCLUDED.app_version,
                        locale = EXCLUDED.locale,
                        timezone = EXCLUDED.timezone,
                        notifications_enabled = EXCLUDED.notifications_enabled,
                        session_id = EXCLUDED.session_id,
                        disabled_at = NULL,
                        last_seen_at = now(),
                        updated_at = now()
                    RETURNING id, user_id, platform, notifications_enabled, last_seen_at
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, deviceIdFactory())
                    statement.setObject(2, userId)
                    statement.setString(3, registration.platform)
                    statement.setString(4, registration.deviceToken)
                    statement.setString(5, registration.appVersion)
                    statement.setString(6, registration.locale)
                    statement.setString(7, registration.timezone)
                    statement.setBoolean(8, registration.notificationsEnabled)
                    statement.setObject(9, registration.sessionId)
                    statement.executeQuery().use { rs ->
                        require(rs.next()) { "push device upsert returned no rows" }
                        rs.toPushDeviceRecord()
                    }
                }
        }

    suspend fun unregisterPushDevice(
        userId: UUID,
        platform: String,
        deviceToken: String,
    ): Boolean =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    UPDATE push_devices
                    SET disabled_at = now(),
                        notifications_enabled = false,
                        updated_at = now()
                    WHERE user_id = ?
                      AND platform = ?
                      AND device_token = ?
                      AND disabled_at IS NULL
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setString(2, platform)
                    statement.setString(3, deviceToken)
                    statement.executeUpdate() > 0
                }
        }

    suspend fun deactivatePushDevice(deviceId: UUID): Boolean =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    UPDATE push_devices
                    SET disabled_at = now(),
                        notifications_enabled = false,
                        updated_at = now()
                    WHERE id = ?
                      AND disabled_at IS NULL
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, deviceId)
                    statement.executeUpdate() > 0
                }
        }

    suspend fun listActivePushTargets(userId: UUID): List<PushDeliveryTarget> =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT id, user_id, platform, device_token
                    FROM push_devices
                    WHERE user_id = ?
                      AND disabled_at IS NULL
                      AND notifications_enabled = true
                      AND (
                          session_id IS NULL
                          OR EXISTS (
                              SELECT 1 FROM user_sessions s
                              WHERE s.id = push_devices.session_id
                                AND s.revoked_at IS NULL
                                AND s.refresh_expires_at > now()
                          )
                      )
                    ORDER BY last_seen_at DESC, id ASC
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) add(rs.toPushDeliveryTarget())
                        }
                    }
                }
        }
}
