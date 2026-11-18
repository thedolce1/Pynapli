package com.pynapli.server.profile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Date
import java.util.UUID
import javax.sql.DataSource

internal class JdbcProfileMutationActions(
    private val dataSource: DataSource,
    private val readActions: JdbcProfileReadActions,
) {
    suspend fun updateProfile(
        userId: UUID,
        update: ProfileUpdate,
    ): MeRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        UPDATE profiles
                        SET display_name = ?,
                            birthdate = ?,
                            display_age = ?,
                            gender = ?,
                            gender_detail = ?,
                            orientation = ?,
                            orientation_detail = ?,
                            relationship_context = ?,
                            discovery_style = COALESCE(?, discovery_style),
                            bio = CASE WHEN ? THEN ?::text ELSE bio END,
                            lifestyle_tags = CASE WHEN ? THEN ?::text[] ELSE lifestyle_tags END,
                            logistics_tags = CASE WHEN ? THEN ?::text[] ELSE logistics_tags END,
                            intents = CASE WHEN ? THEN ?::text[] ELSE intents END,
                            neighborhood = CASE WHEN ? THEN ?::text ELSE neighborhood END,
                            experience_level = CASE WHEN ? THEN ?::text ELSE experience_level END,
                            boundaries = CASE WHEN ? THEN ?::text[] ELSE boundaries END,
                            profile_completeness_score = ?,
                            updated_at = now()
                        WHERE user_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, update.displayName)
                        statement.setDate(2, update.birthdate?.let { Date.valueOf(it) })
                        update.displayAge?.let { statement.setInt(3, it) } ?: statement.setNull(3, java.sql.Types.INTEGER)
                        statement.setString(4, update.gender)
                        statement.setString(5, update.genderDetail)
                        statement.setString(6, update.orientation)
                        statement.setString(7, update.orientationDetail)
                        statement.setString(8, update.relationshipContext)
                        statement.setString(9, update.discoveryStyle)
                        statement.setBoolean(10, update.bioProvided)
                        statement.setString(11, update.bio)
                        // Provided-but-empty tag lists store NULL: the V36 "opted out"
                        // shape, so cleared profiles drop out of tag-filtered discovery.
                        statement.setBoolean(12, update.lifestyleTags != null)
                        statement.setArray(13, connection.toTextArrayOrNull(update.lifestyleTags))
                        statement.setBoolean(14, update.logisticsTags != null)
                        statement.setArray(15, connection.toTextArrayOrNull(update.logisticsTags))
                        statement.setBoolean(16, update.intents != null)
                        statement.setArray(17, connection.toTextArrayOrNull(update.intents))
                        statement.setBoolean(18, update.neighborhoodProvided)
                        statement.setString(19, update.neighborhood)
                        statement.setBoolean(20, update.experienceLevelProvided)
                        statement.setString(21, update.experienceLevel)
                        statement.setBoolean(22, update.boundaries != null)
                        statement.setArray(23, connection.toTextArrayOrNull(update.boundaries))
                        statement.setInt(24, update.profileCompletenessScore)
                        statement.setObject(25, userId)
                        if (statement.executeUpdate() == 0) return@withContext null
                    }
            }

            readActions.getMe(userId)
        }

    suspend fun updateDiscoverySettings(
        userId: UUID,
        update: DiscoverySettingsUpdate,
    ): MeRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        UPDATE discovery_settings
                        SET grid_visible = COALESCE(?::boolean, grid_visible),
                            swipe_visible = COALESCE(?::boolean, swipe_visible),
                            show_distance = COALESCE(?::boolean, show_distance),
                            show_in_viewed_lists = COALESCE(?::boolean, show_in_viewed_lists),
                            allow_grid_messages = COALESCE(?::boolean, allow_grid_messages),
                            grid_message_policy = COALESCE(?::text, grid_message_policy),
                            allow_flirts = COALESCE(?::boolean, allow_flirts),
                            allow_intro_notes = COALESCE(?::boolean, allow_intro_notes),
                            allow_album_requests = COALESCE(?::boolean, allow_album_requests),
                            visible_while_traveling = COALESCE(?::boolean, visible_while_traveling),
                            share_psync_solo_matches = COALESCE(?::boolean, share_psync_solo_matches),
                            partner_visibility = COALESCE(?::text, partner_visibility),
                            updated_at = now()
                        WHERE user_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, update.gridVisible)
                        statement.setObject(2, update.swipeVisible)
                        statement.setObject(3, update.showDistance)
                        statement.setObject(4, update.showInViewedLists)
                        statement.setObject(5, update.allowGridMessages)
                        statement.setString(6, update.gridMessagePolicy)
                        statement.setObject(7, update.allowFlirts)
                        statement.setObject(8, update.allowIntroNotes)
                        statement.setObject(9, update.allowAlbumRequests)
                        statement.setObject(10, update.visibleWhileTraveling)
                        statement.setObject(11, update.sharePsyncSoloMatches)
                        statement.setString(12, update.partnerVisibility)
                        statement.setObject(13, userId)
                        if (statement.executeUpdate() == 0) return@withContext null
                    }
            }

            readActions.getMe(userId)
        }
}
