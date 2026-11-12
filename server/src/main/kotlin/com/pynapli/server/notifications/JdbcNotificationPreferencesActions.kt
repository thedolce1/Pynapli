package com.pynapli.server.notifications

import com.pynapli.server.db.withConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.sql.DataSource

internal class JdbcNotificationPreferencesActions(
    private val dataSource: DataSource,
) {
    suspend fun getPreferences(userId: UUID): NotificationPreferencesRecord? =
        dataSource.withConnection { connection ->
            connection.ensureNotificationPreferences(userId)
            connection.prepareStatement(PREFERENCES_QUERY).use { statement ->
                statement.setObject(1, userId)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) return@withConnection null
                    rs.toNotificationPreferencesRecord()
                }
            }
        }

    suspend fun getDeliveryGate(userId: UUID): NotificationDeliveryGate? =
        dataSource.withConnection { connection ->
            // Create the default row first, as its own statement: a row inserted by a CTE is not
            // visible to the SELECT in the same statement, so the first notification to someone
            // who never opened notification settings used to be dropped.
            connection.ensureNotificationPreferences(userId)
            connection
                .prepareStatement(
                    """
                    SELECT np.user_id,
                           np.detail_level,
                           np.messages_enabled,
                           np.matches_enabled,
                           np.activity_enabled,
                           np.flirts_enabled,
                           np.album_requests_enabled,
                           np.partner_invites_enabled,
                           np.system_safety_enabled,
                           np.quiet_hours_enabled,
                           np.quiet_hours_start_minute,
                           np.quiet_hours_end_minute,
                           np.quiet_hours_timezone,
                           COUNT(pd.id) FILTER (
                               WHERE pd.disabled_at IS NULL
                                 AND pd.notifications_enabled = true
                                 AND (
                                     pd.session_id IS NULL
                                     OR EXISTS (
                                         SELECT 1 FROM user_sessions s
                                         WHERE s.id = pd.session_id
                                           AND s.revoked_at IS NULL
                                           AND s.refresh_expires_at > now()
                                     )
                                 )
                           ) AS active_device_count
                    FROM notification_preferences np
                    LEFT JOIN push_devices pd
                      ON pd.user_id = np.user_id
                    WHERE np.user_id = ?
                    GROUP BY np.user_id,
                             np.detail_level,
                             np.messages_enabled,
                             np.matches_enabled,
                             np.activity_enabled,
                             np.flirts_enabled,
                             np.album_requests_enabled,
                             np.partner_invites_enabled,
                             np.system_safety_enabled,
                             np.quiet_hours_enabled,
                             np.quiet_hours_start_minute,
                             np.quiet_hours_end_minute,
                             np.quiet_hours_timezone
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.executeQuery().use { rs ->
                        if (!rs.next()) return@withConnection null
                        NotificationDeliveryGate(
                            preferences = rs.toNotificationPreferencesRecord(),
                            activeDeviceCount = rs.getInt("active_device_count"),
                        )
                    }
                }
        }

    suspend fun updatePreferences(
        userId: UUID,
        update: NotificationPreferencesUpdate,
    ): NotificationPreferencesRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.ensureNotificationPreferences(userId)
                connection
                    .prepareStatement(
                        """
                        UPDATE notification_preferences
                        SET detail_level = COALESCE(?::text, detail_level),
                            messages_enabled = COALESCE(?::boolean, messages_enabled),
                            matches_enabled = COALESCE(?::boolean, matches_enabled),
                            activity_enabled = COALESCE(?::boolean, activity_enabled),
                            flirts_enabled = COALESCE(?::boolean, flirts_enabled),
                            album_requests_enabled = COALESCE(?::boolean, album_requests_enabled),
                            partner_invites_enabled = COALESCE(?::boolean, partner_invites_enabled),
                            system_safety_enabled = COALESCE(?::boolean, system_safety_enabled),
                            quiet_hours_enabled = COALESCE(?::boolean, quiet_hours_enabled),
                            quiet_hours_start_minute = COALESCE(?::integer, quiet_hours_start_minute),
                            quiet_hours_end_minute = COALESCE(?::integer, quiet_hours_end_minute),
                            quiet_hours_timezone = COALESCE(?::text, quiet_hours_timezone),
                            updated_at = now()
                        WHERE user_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, update.detailLevel)
                        statement.setObject(2, update.messagesEnabled)
                        statement.setObject(3, update.matchesEnabled)
                        statement.setObject(4, update.activityEnabled)
                        statement.setObject(5, update.flirtsEnabled)
                        statement.setObject(6, update.albumRequestsEnabled)
                        statement.setObject(7, update.partnerInvitesEnabled)
                        statement.setObject(8, update.systemSafetyEnabled)
                        statement.setObject(9, update.quietHoursEnabled)
                        statement.setObject(10, update.quietHoursStartMinute)
                        statement.setObject(11, update.quietHoursEndMinute)
                        statement.setString(12, update.quietHoursTimezone)
                        statement.setObject(13, userId)
                        if (statement.executeUpdate() == 0) return@withContext null
                    }
            }

            getPreferences(userId)
        }

    private companion object {
        private val PREFERENCES_QUERY =
            """
            SELECT user_id,
                   detail_level,
                   messages_enabled,
                   matches_enabled,
                   activity_enabled,
                   flirts_enabled,
                   album_requests_enabled,
                   partner_invites_enabled,
                   system_safety_enabled,
                   quiet_hours_enabled,
                   quiet_hours_start_minute,
                   quiet_hours_end_minute,
                   quiet_hours_timezone
            FROM notification_preferences
            WHERE user_id = ?
            """.trimIndent()
    }
}
