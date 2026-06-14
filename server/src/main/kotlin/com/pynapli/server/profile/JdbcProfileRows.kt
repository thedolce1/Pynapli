package com.pynapli.server.profile

import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

internal fun Connection.toTextArrayOrNull(values: List<String>?): java.sql.Array? = values?.takeIf { it.isNotEmpty() }?.let { createArrayOf("text", it.toTypedArray()) }

private fun ResultSet.getStringList(column: String): List<String> = (getArray(column)?.array as? Array<*>)?.mapNotNull { it as? String } ?: emptyList()

private fun ResultSet.getUuidList(column: String): List<UUID> = (getArray(column)?.array as? Array<*>)?.mapNotNull { it as? UUID } ?: emptyList()

internal fun ResultSet.toPublicProfileRecord(): PublicProfileRecord {
    val distanceMeters = getDouble("distance_meters").takeUnless { wasNull() }
    return PublicProfileRecord(
        userId = getObject("user_id", UUID::class.java),
        displayName = getString("display_name"),
        profilePhotoMediaAssetId = getObject("profile_photo_media_asset_id", UUID::class.java),
        profilePhotoMediaAssetIds = getUuidList("profile_photo_ids"),
        verified = getBoolean("verified"),
        linkedPartnerUserId = getObject("linked_partner_user_id", UUID::class.java),
        linkedPartnerDisplayName = getString("linked_partner_display_name"),
        linkedPartnerThumbnailMediaAssetId = getObject("linked_partner_thumbnail_media_asset_id", UUID::class.java),
        displayAge = getInt("display_age").takeUnless { wasNull() },
        bio = getString("bio"),
        neighborhood = getString("neighborhood"),
        gender = getString("gender"),
        genderDetail = getString("gender_detail"),
        orientation = getString("orientation"),
        orientationDetail = getString("orientation_detail"),
        relationshipContext = getString("relationship_context"),
        discoveryStyle = getString("discovery_style"),
        lifestyleTags = getStringList("lifestyle_tags"),
        logisticsTags = getStringList("logistics_tags"),
        intents = getStringList("intents"),
        distanceMeters = distanceMeters,
        privateAlbumAccessStatus = getString("private_album_access_status"),
        allowGridMessages = getBoolean("allow_grid_messages"),
        allowFlirts = getBoolean("allow_flirts"),
        allowAlbumRequests = getBoolean("allow_album_requests"),
    )
}

internal fun ResultSet.toMeRecord(): MeRecord {
    val userId = getObject("user_id", UUID::class.java)
    return MeRecord(
        userId = userId,
        userStatus = getString("user_status"),
        profile =
            ProfileRecord(
                userId = userId,
                displayName = getString("display_name"),
                birthdate = getDate("birthdate")?.toLocalDate(),
                displayAge = getInt("display_age").takeUnless { wasNull() },
                bio = getString("bio"),
                neighborhood = getString("neighborhood"),
                gender = getString("gender"),
                genderDetail = getString("gender_detail"),
                orientation = getString("orientation"),
                orientationDetail = getString("orientation_detail"),
                relationshipContext = getString("relationship_context"),
                discoveryStyle = getString("discovery_style"),
                lifestyleTags = getStringList("lifestyle_tags"),
                logisticsTags = getStringList("logistics_tags"),
                intents = getStringList("intents"),
                profileCompletenessScore = getInt("profile_completeness_score"),
                moderationStatus = getString("moderation_status"),
                visibilityStatus = getString("visibility_status"),
                ageVerified = getBoolean("age_verified"),
            ),
        discoverySettings =
            DiscoverySettingsRecord(
                gridVisible = getBoolean("grid_visible"),
                swipeVisible = getBoolean("swipe_visible"),
                eventsVisible = getBoolean("events_visible"),
                showDistance = getBoolean("show_distance"),
                showInViewedLists = getBoolean("show_in_viewed_lists"),
                incognito = getBoolean("incognito"),
                allowGridMessages = getBoolean("allow_grid_messages"),
                gridMessagePolicy = getString("grid_message_policy"),
                allowFlirts = getBoolean("allow_flirts"),
                allowIntroNotes = getBoolean("allow_intro_notes"),
                allowAlbumRequests = getBoolean("allow_album_requests"),
                soloEnabled = getBoolean("solo_enabled"),
                togetherEnabled = getBoolean("together_enabled"),
                socialEnabled = getBoolean("social_enabled"),
                visibleWhileTraveling = getBoolean("visible_while_traveling"),
                sharePsyncSoloMatches = getBoolean("share_psync_solo_matches"),
                partnerVisibility = getString("partner_visibility"),
            ),
    )
}
