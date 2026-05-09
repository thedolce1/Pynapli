package com.pynapli.server.discovery

data class DiscoveryFilters(
    val minimumAge: Int? = null,
    val maximumAge: Int? = null,
    val relationshipContexts: Set<String> = emptySet(),
    val linkedOnly: Boolean? = null,
    val verifiedOnly: Boolean = false,
    val facePhotoVerifiedOnly: Boolean = false,
    val ageVerifiedOnly: Boolean = false
) {
    val hasAdvancedFilters: Boolean
        get() = minimumAge != null ||
            maximumAge != null ||
            relationshipContexts.isNotEmpty() ||
            linkedOnly != null ||
            verifiedOnly ||
            facePhotoVerifiedOnly ||
            ageVerifiedOnly
}

fun discoveryFiltersFrom(
    minimumAge: Int?,
    maximumAge: Int?,
    relationshipContexts: List<String>,
    linkedOnly: Boolean?,
    verifiedOnly: Boolean = false,
    facePhotoVerifiedOnly: Boolean = false,
    ageVerifiedOnly: Boolean = false,
    errorFactory: (String) -> RuntimeException
): DiscoveryFilters {
    val min = minimumAge?.also { age ->
        if (age !in MIN_FILTER_AGE..MAX_FILTER_AGE) throw errorFactory("INVALID_DISCOVERY_FILTER")
    }
    val max = maximumAge?.also { age ->
        if (age !in MIN_FILTER_AGE..MAX_FILTER_AGE) throw errorFactory("INVALID_DISCOVERY_FILTER")
    }
    if (min != null && max != null && min > max) throw errorFactory("INVALID_DISCOVERY_FILTER")

    if (relationshipContexts.size > MAX_RELATIONSHIP_CONTEXT_FILTERS) {
        throw errorFactory("INVALID_DISCOVERY_FILTER")
    }
    val contexts = relationshipContexts
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .onEach { value ->
            if (value.length > MAX_RELATIONSHIP_CONTEXT_LENGTH) {
                throw errorFactory("INVALID_DISCOVERY_FILTER")
            }
        }
        .toSet()

    return DiscoveryFilters(
        minimumAge = min,
        maximumAge = max,
        relationshipContexts = contexts,
        linkedOnly = linkedOnly,
        verifiedOnly = verifiedOnly,
        facePhotoVerifiedOnly = facePhotoVerifiedOnly,
        ageVerifiedOnly = ageVerifiedOnly
    )
}

private const val MIN_FILTER_AGE = 18
private const val MAX_FILTER_AGE = 99
private const val MAX_RELATIONSHIP_CONTEXT_FILTERS = 8
private const val MAX_RELATIONSHIP_CONTEXT_LENGTH = 40
