package com.pynapli.server.profile

import com.pynapli.shared.profile.ProfileTaxonomies
import java.time.LocalDate

internal val DISCOVERY_STYLES = setOf("solo", "together", "both", "social", "ask_in_chat")
internal val GRID_MESSAGE_POLICIES = setOf("everyone", "matches_only", "no_one")
internal val PARTNER_VISIBILITIES = setOf("public", "matches_only", "verified_only", "hidden")

/**
 * The free-text label is only meaningful with the self_describe key (where it is
 * required); structured keys drop any stray detail so filters stay structured.
 */
internal fun selfDescribeDetail(
    normalizedKey: String?,
    detail: String?,
    errorCode: String,
): String? {
    if (normalizedKey != ProfileTaxonomies.SELF_DESCRIBE) return null
    val cleaned = detail?.trim()?.takeIf { it.isNotEmpty() } ?: throw ProfileException(errorCode)
    if (cleaned.length > ProfileTaxonomies.SELF_DESCRIBE_MAX_LENGTH) throw ProfileException(errorCode)
    return cleaned
}

internal fun List<String>?.cleanTags(allowed: Set<String>): List<String>? {
    if (this == null) return null
    val cleaned =
        map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
    cleaned.forEach { value ->
        if (value !in allowed) throw ProfileException("INVALID_PROFILE_TAG")
    }
    return cleaned
}

internal fun String?.cleanOptional(maxLength: Int): String? {
    val value = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (value.length > maxLength) throw ProfileException("INVALID_PROFILE_FIELD")
    return value.lowercase()
}

internal fun profileCompleteness(
    displayName: String?,
    birthdate: LocalDate?,
    gender: String?,
    orientation: String?,
    relationshipContext: String?,
    discoveryStyle: String?,
): Int {
    var score = 0
    if (displayName != null) score += 20
    if (birthdate != null) score += 25
    if (!gender.isNullOrBlank()) score += 15
    if (!orientation.isNullOrBlank()) score += 15
    if (!relationshipContext.isNullOrBlank()) score += 10
    if (!discoveryStyle.isNullOrBlank()) score += 15
    return score.coerceIn(0, 100)
}
