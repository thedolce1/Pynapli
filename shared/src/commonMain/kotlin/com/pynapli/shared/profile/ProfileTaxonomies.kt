package com.pynapli.shared.profile

/**
 * Canonical gender/orientation taxonomies (PROFILE_SETTINGS_SPEC, founder decision
 * 2026-06-10): Grindr-style structured chips plus free-text self-describe, with
 * option lists tuned for the swingers lifestyle community. Profiles store the
 * structured key; a self-describe entry stores the custom label alongside the
 * [SELF_DESCRIBE] key and is excluded from structured filters.
 *
 * Shared between the Ktor server (write-path validation) and the Compose clients
 * (chip options) so the two can never drift.
 */
object ProfileTaxonomies {
    const val SELF_DESCRIBE = "self_describe"

    data class Option(
        val key: String,
        val label: String,
    )

    val genderOptions: List<Option> =
        listOf(
            Option("man", "Man"),
            Option("woman", "Woman"),
            Option("trans_man", "Trans Man"),
            Option("trans_woman", "Trans Woman"),
            Option("non_binary", "Non-Binary"),
            Option(SELF_DESCRIBE, "Self-describe"),
        )

    val orientationOptions: List<Option> =
        listOf(
            Option("straight", "Straight"),
            Option("heteroflexible", "Heteroflexible"),
            Option("bicurious", "Bicurious"),
            Option("bisexual", "Bisexual"),
            Option("pansexual", "Pansexual"),
            Option("gay", "Gay"),
            Option("lesbian", "Lesbian"),
            Option("queer", "Queer"),
            Option(SELF_DESCRIBE, "Self-describe"),
        )

    val genderKeys: Set<String> = genderOptions.map { it.key }.toSet()

    val orientationKeys: Set<String> = orientationOptions.map { it.key }.toSet()

    /** Display label for a stored key; self-describe renders its custom label upstream. */
    fun genderLabel(key: String): String? = genderOptions.firstOrNull { it.key == key }?.label

    fun orientationLabel(key: String): String? = orientationOptions.firstOrNull { it.key == key }?.label

    const val BIO_MAX_LENGTH = 500
    const val SELF_DESCRIBE_MAX_LENGTH = 40

    // User-declared neighborhood / area label (e.g. "Mission", "Soho"). Short by design —
    // it's a one-line meta tag in the peek, not free-form text.
    const val NEIGHBORHOOD_MAX_LENGTH = 50
}
