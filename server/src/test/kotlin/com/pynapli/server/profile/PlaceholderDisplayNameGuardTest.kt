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
}
