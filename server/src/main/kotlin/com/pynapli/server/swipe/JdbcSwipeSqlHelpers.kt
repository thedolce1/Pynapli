package com.pynapli.server.swipe

import com.pynapli.server.discovery.DiscoveryFilters
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.util.UUID

internal fun PreparedStatement.setNullableUuid(
    index: Int,
    value: UUID?,
) {
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
        softScore = getInt("soft_score"),
        orderBucket = getInt("order_bucket"),
        orderTiebreak = getLong("order_tiebreak"),
        naturalRank = getLong("natural_rank"),
    )

// linkedOnly and verifiedCouplesOnly test the linked partner against this viewer (blocks, partner
// visibility, hidden list), so their windows are never shared across viewers.
internal fun DiscoveryFilters.isRankCacheEligible(): Boolean = linkedOnly == null && !verifiedCouplesOnly

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
        append(boundaryItems.sorted().joinToString("|"))
        append(';')
        append(logisticsTags.sorted().joinToString("|"))
        append(';')
        append(tonightAvailableOnly)
        append(';')
        append(withPhotosOnly)
        append(';')
        append(hasPrivateAlbumOnly)
        append(';')
        append(intents.sorted().joinToString("|"))
        append(';')
        // Identity filters are hard predicates in the deck SQL, so they MUST be part of the
        // cache class — otherwise two viewers with different gender/orientation filters would
        // share one ranked window. (Added with withPhotosOnly; previously omitted.)
        append(genders.sorted().joinToString("|"))
        append(';')
        append(orientations.sorted().joinToString("|"))
        append(';')
        append(verifiedPreferred)
        append(';')
        // Never cached (see isRankCacheEligible); keyed anyway so a future eligibility change cannot
        // let filtered and unfiltered viewers share a window.
        append(verifiedCouplesOnly)
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
