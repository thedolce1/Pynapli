package com.pynapli.server.swipe

import com.pynapli.server.discovery.DiscoveryFilters

/**
 * Serializes a `DiscoveryFilters` to the canonical JSON payload persisted on
 * `swipe_deck_sessions.filter_payload`. Used by both the live deck path and the
 * Top Picks backing-session path so both produce identical scope hashes when
 * given the same filter set.
 */
internal fun DiscoveryFilters.toPersistedJson(): String =
    buildString {
        append("{")
        append("\"minimumAge\":").append(minimumAge?.toString() ?: "null")
        append(",\"maximumAge\":").append(maximumAge?.toString() ?: "null")
        append(",\"linkedOnly\":").append(linkedOnly?.toString() ?: "null")
        append(",\"relationshipContexts\":").append(relationshipContexts.toJsonArray())
        append(",\"verifiedOnly\":").append(verifiedOnly)
        append(",\"facePhotoVerifiedOnly\":").append(facePhotoVerifiedOnly)
        append(",\"ageVerifiedOnly\":").append(ageVerifiedOnly)
        append(",\"discoveryStyles\":").append(discoveryStyles.toJsonArray())
        append(",\"lifestyleTags\":").append(lifestyleTags.toJsonArray())
        append(",\"boundaryItems\":").append(boundaryItems.toJsonArray())
        append(",\"logisticsTags\":").append(logisticsTags.toJsonArray())
        append(",\"tonightAvailableOnly\":").append(tonightAvailableOnly)
        append(",\"intents\":").append(intents.toJsonArray())
        append("}")
    }

private fun Set<String>.toJsonArray(): String =
    sorted().joinToString(prefix = "[", postfix = "]") { value ->
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
