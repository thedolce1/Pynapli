package com.pynapli.server.profile

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Section 4: a profile without a display name reads "New member" everywhere. The old branded
 * "Pynapli User" fallback looked like a fake account, so no source may bring it back.
 * (Gradle runs server tests from the server module directory.)
 */
class PlaceholderDisplayNameGuardTest {
    @Test
    fun theBrandedPlaceholderNameIsGone() {
        val roots = listOf(File("src/main"), File("../composeApp/src"), File("../shared/src"))
        assertTrue(roots.first().isDirectory, "run from the server module directory")
        val offenders =
            roots
                .filter { it.isDirectory }
                .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "sql") }.toList() }
                .filter { file -> file.readText().contains("Pynapli User") }
                .map { it.path }
        assertEquals(emptyList(), offenders)
    }

    @Test
    fun membersCannotChooseThePlaceholderOrAStaffName() {
        for (name in listOf("New member", "  new   MEMBER ", "Pynapli", "Pynapli Support", "Moderator")) {
            val error =
                kotlin.test.assertFailsWith<ProfileException>(name) {
                    com.pynapli.shared.network.dto
                        .UpdateProfileRequestDto(displayName = name)
                        .toProfileUpdate(java.time.Clock.systemUTC())
                }
            assertEquals("DISPLAY_NAME_RESERVED", error.code)
        }
        assertTrue(!"New members club".isReservedDisplayName())
        assertTrue(!"Maya".isReservedDisplayName())
    }

    @Test
    fun lookAlikeSpellingsOfReservedNamesAreCaught() {
        val reserved =
            listOf(
                "New${NBSP}member", // no-break space
                "New${EM_SPACE}member", // em space
                "N${ZWSP}ew mem${ZWJ}ber", // zero-width space / joiner
                "N${CYRILLIC_E}w member", // Cyrillic e (U+0435)
                "New m${CYRILLIC_E}mb${CYRILLIC_E}r", // Cyrillic e twice
                "New-Member!", // punctuation
                "New member 2", // suffix
                "new_member_", // underscores
                "$FULLWIDTH_NEW member", // fullwidth "New"
                "Nèw member", // diacritic
                "Pynapli Support Team",
                "Official Pynapli",
                "pynapli_admin",
                "P${CYRILLIC_U}napli Staff", // Cyrillic u (U+0443)
                "${GREEK_RHO}ynapli team", // Greek capital Rho (U+03A1)
                "Admin",
                "admin2",
                "Moderator.",
                "Staff",
                "Official",
                "Support",
                "Team",
                "Pynapli",
                "Pynapli User",
            )
        for (name in reserved) {
            assertTrue(name.isReservedDisplayName(), "expected reserved: $name -> ${displayNameSkeleton(name)}")
        }

        val allowed = listOf("Maya", "Alex & Sam", "Supporter of jazz", "Teamwork Tom", "Steam", "Adminton", "New York", "Membership", "Élodie", "Дмитрий")
        for (name in allowed) {
            assertTrue(!name.isReservedDisplayName(), "expected allowed: $name -> ${displayNameSkeleton(name)}")
        }
    }

    // Built from code points so no invisible or look-alike character sits in the source.
    private companion object {
        val NBSP = Char(0x00A0)
        val EM_SPACE = Char(0x2003)
        val ZWSP = Char(0x200B)
        val ZWJ = Char(0x200D)
        val CYRILLIC_E = Char(0x0435)
        val CYRILLIC_U = Char(0x0443)
        val GREEK_RHO = Char(0x03A1)
        val FULLWIDTH_NEW = String(charArrayOf(Char(0xFF2E), Char(0xFF45), Char(0xFF57)))
    }
}
