package com.pynapli.server.swipe

import com.pynapli.server.discovery.DiscoveryFilters
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.util.UUID

internal fun PreparedStatement.setNullableUuid(index: Int, value: UUID?) {
    if (value == null) {
        setNull(index, Types.OTHER)
    } else {
        setObject(index, value)
    }
}

internal fun PreparedStatement.executeDeckRankRows(): List<DeckRankRow> =
    executeQuery().use { rs ->
        buildList {
            while (rs.next()) {
                add(rs.readDeckRankRow())
            }
        }
    }

internal fun ResultSet.readDeckRankRow(): DeckRankRow =
    DeckRankRow(
        userId = getObject("user_id", UUID::class.java),
        displayName = getString("display_name"),
        displayAge = getInt("display_age").takeUnless { wasNull() },
        bio = getString("bio"),
        tags = readStringArray("profile_tags"),
        verified = getBoolean("verified"),
        visiting = getBoolean("visiting"),
        showDistance = getBoolean("show_distance"),
        distanceMeters = getDouble("distance_meters"),
        isBoosted = getBoolean("is_boosted"),
        softScore = getInt("soft_score")
    )

internal fun DiscoveryFilters.isRankCacheEligible(): Boolean =
    linkedOnly == null

internal fun DiscoveryFilters.rankCacheKey(): String =
    buildString {
        append(minimumAge ?: "")
        append(';')
        append(maximumAge ?: "")
        append(';')
        append(relationshipContexts.sorted().joinToString("|"))
        append(';')
        append(linkedOnly ?: "")
        append(';')
        append(verifiedOnly)
        append(';')
        append(facePhotoVerifiedOnly)
        append(';')
        append(ageVerifiedOnly)
        append(';')
        append(discoveryStyles.sorted().joinToString("|"))
        append(';')
        append(lifestyleTags.sorted().joinToString("|"))
        append(';')
        append(logisticsTags.sorted().joinToString("|"))
        append(';')
        append(tonightAvailableOnly)
        append(';')
        append(intents.sorted().joinToString("|"))
        append(';')
        append(verifiedPreferred)
    }

internal fun nearestCandidateWindowLimit(limit: Int, filters: DiscoveryFilters): Int {
    val multiplier = if (filters.hasAdvancedFilters || filters.verifiedPreferred) 20 else 8
    return (limit * multiplier).coerceIn(limit, 1_500)
}

internal fun ResultSet.readStringArray(column: String): List<String> {
    val sqlArray = getArray(column) ?: return emptyList()
    return try {
        @Suppress("UNCHECKED_CAST")
        (sqlArray.array as Array<String?>?)?.filterNotNull().orEmpty()
    } finally {
        sqlArray.free()
    }
}

internal fun ResultSet.readUuidArray(column: String): List<UUID> {
    val sqlArray = getArray(column) ?: return emptyList()
    return try {
        @Suppress("UNCHECKED_CAST")
        (sqlArray.array as Array<UUID?>?)?.filterNotNull().orEmpty()
    } finally {
        sqlArray.free()
    }
}
