package com.pynapli.shared.profile

/**
 * The tag-bearing parts of one profile, as server tokens ([DiscoveryTagTaxonomies]): what the
 * profile peek shows as lifestyle terms, experience, logistics and intents. Boundaries are left out
 * on purpose: an "ask first" or "not for me" is a limit, not something two people share.
 */
data class ProfileTagSet(
    val lifestyleTags: List<String> = emptyList(),
    val experienceLevel: String? = null,
    val logisticsTags: List<String> = emptyList(),
    val intents: List<String> = emptyList(),
)

/** One of a profile's tag tokens, and whether the viewer's own profile carries it too. */
data class ProfileTagMatch(
    val token: String,
    val inCommon: Boolean,
)

/**
 * A profile's tags ordered for the peek (Grindr "My Tags" in common): within each list the tags the
 * viewer also has come first, then the rest, each half in the profile's own order.
 */
data class InCommonTags(
    val lifestyleTags: List<ProfileTagMatch>,
    val experienceInCommon: Boolean,
    val logisticsTags: List<ProfileTagMatch>,
    val intents: List<ProfileTagMatch>,
) {
    /** How many of the profile's tags the viewer shares. */
    val count: Int
        get() {
            val tags = listOf(lifestyleTags, logisticsTags, intents).sumOf { list -> list.count { it.inCommon } }
            return if (experienceInCommon) tags + 1 else tags
        }
}

/**
 * [theirs] with the tokens also in [mine] first and flagged, each half in [theirs]' order. Blank and
 * repeated tokens are dropped. Only reorders and flags what [theirs] already holds: it never adds a
 * tag, so whatever the server chose to send (or withhold) is exactly what shows.
 */
fun inCommonFirst(
    theirs: List<String>,
    mine: Collection<String>,
): List<ProfileTagMatch> {
    val own = mine.filterTo(HashSet()) { it.isNotBlank() }
    val (shared, rest) = theirs.filter { it.isNotBlank() }.distinct().partition { it in own }
    return shared.map { ProfileTagMatch(it, inCommon = true) } + rest.map { ProfileTagMatch(it, inCommon = false) }
}

/**
 * [profile]'s tags with the ones [viewer] shares first. A null [viewer] (own profile not loaded yet,
 * or the viewer previewing their own profile) flags nothing and keeps the profile's order.
 */
fun inCommonTags(
    viewer: ProfileTagSet?,
    profile: ProfileTagSet,
): InCommonTags {
    val own = viewer ?: ProfileTagSet()
    return InCommonTags(
        lifestyleTags = inCommonFirst(profile.lifestyleTags, own.lifestyleTags),
        experienceInCommon = !profile.experienceLevel.isNullOrBlank() && profile.experienceLevel == viewer?.experienceLevel,
        logisticsTags = inCommonFirst(profile.logisticsTags, own.logisticsTags),
        intents = inCommonFirst(profile.intents, own.intents),
    )
}
