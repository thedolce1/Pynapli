package com.pynapli.server.profile

import java.text.Normalizer
import java.util.Locale

/**
 * Display names a member can't choose (TRUST_AND_SAFETY_SPEC "Photo pre-screening", placeholder
 * name): the "New member" placeholder shown for a profile without a name, names that pass for
 * Pynapli staff, and bare staff words. Existing members who already carry such a name are not
 * migrated; the check runs when a name is set.
 *
 * Matching is on a letters-only skeleton so look-alike spellings can't get round it:
 * NFKC (fullwidth, ligatures) -> drop format/zero-width characters -> any Unicode whitespace is a
 * space -> lowercase with [Locale.ROOT] -> strip diacritics -> fold common Cyrillic/Greek/Latin
 * confusables to Latin -> keep letters only. "New member!" with a no-break space, "New member"
 * with a zero-width space inside, "New member" spelt with Cyrillic U+0435 for the e, and
 * "New member 2" all become "newmember".
 */
internal fun String.isReservedDisplayName(): Boolean {
    val skeleton = displayNameSkeleton(this)
    if (skeleton.isEmpty()) return false
    if (skeleton in RESERVED_SKELETONS || skeleton in STAFF_WORDS) return true
    return BRAND in skeleton && STAFF_WORDS.any { it in skeleton }
}

internal fun displayNameSkeleton(name: String): String {
    val compatible = Normalizer.normalize(name, Normalizer.Form.NFKC)
    val visible =
        buildString {
            compatible.forEach { char ->
                when {
                    Character.getType(char) == Character.FORMAT.toInt() -> Unit
                    char in ZERO_WIDTH -> Unit
                    Character.isWhitespace(char) || Character.isSpaceChar(char) -> append(' ')
                    else -> append(char)
                }
            }
        }
    val lower = visible.lowercase(Locale.ROOT)
    // Strip diacritics: decompose, then drop combining marks.
    val bare =
        Normalizer
            .normalize(lower, Normalizer.Form.NFD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
    return bare
        .map { CONFUSABLES[it] ?: it }
        .filter { it in 'a'..'z' }
        .joinToString("")
}

private const val BRAND = "pynapli"

private val STAFF_WORDS = setOf("support", "team", "admin", "moderator", "staff", "official")

private val RESERVED_SKELETONS = setOf("newmember", BRAND, "pynapliuser")

// Zero-width space/non-joiner/joiner, word joiner, BOM, Mongolian vowel separator.
private val ZERO_WIDTH = setOf(0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF, 0x180E).map { Char(it) }.toSet()

// Lowercase look-alikes of Latin letters (Unicode confusables, the common subset).
private val CONFUSABLES: Map<Char, Char> =
    mapOf(
        // Cyrillic
        'а' to 'a',
        'в' to 'b',
        'е' to 'e',
        'ё' to 'e',
        'к' to 'k',
        'м' to 'm',
        'н' to 'h',
        'о' to 'o',
        'р' to 'p',
        'с' to 'c',
        'т' to 't',
        'у' to 'y',
        'х' to 'x',
        'і' to 'i',
        'ї' to 'i',
        'ј' to 'j',
        'ѕ' to 's',
        'ԁ' to 'd',
        'ԛ' to 'q',
        'ԝ' to 'w',
        'һ' to 'h',
        'ո' to 'n',
        // Greek
        'α' to 'a',
        'β' to 'b',
        'ε' to 'e',
        'η' to 'n',
        'ι' to 'i',
        'κ' to 'k',
        'μ' to 'u',
        'ν' to 'v',
        'ο' to 'o',
        'ρ' to 'p',
        'τ' to 't',
        'υ' to 'u',
        'χ' to 'x',
        'ω' to 'w',
        'ς' to 's',
        // Latin look-alikes
        'ı' to 'i',
        'ł' to 'l',
        'ø' to 'o',
        'đ' to 'd',
        'ħ' to 'h',
        'ŀ' to 'l',
        'ɑ' to 'a',
        'ɡ' to 'g',
    )
