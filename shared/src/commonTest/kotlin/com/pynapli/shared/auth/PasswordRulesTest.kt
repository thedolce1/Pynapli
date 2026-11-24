package com.pynapli.shared.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PasswordRulesTest {
    @Test
    fun tenCharactersIsTheMinimum() {
        assertEquals(10, PasswordRules.MIN_LENGTH)
        assertEquals(PasswordRules.TOO_SHORT_CODE, PasswordRules.problemWith("nine-char"))
        assertNull(PasswordRules.problemWith("ten-chars!"))
    }

    @Test
    fun theDenylistIsCaseInsensitive() {
        assertEquals(PasswordRules.TOO_COMMON_CODE, PasswordRules.problemWith("password123"))
        assertEquals(PasswordRules.TOO_COMMON_CODE, PasswordRules.problemWith("PassWord123"))
        assertEquals(PasswordRules.TOO_COMMON_CODE, PasswordRules.problemWith("QWERTYUIOP"))
        assertEquals(PasswordRules.TOO_COMMON_MESSAGE, PasswordRules.messageFor("1234567890"))
        assertNull(PasswordRules.messageFor("correct horse battery"))
    }

    @Test
    fun blankOrPaddedPasswordsAreJudgedWithoutTheirSurroundingWhitespace() {
        assertEquals(PasswordRules.TOO_SHORT_CODE, PasswordRules.problemWith("            "))
        assertEquals(PasswordRules.TOO_SHORT_CODE, PasswordRules.problemWith("   nine-char   "))
        assertEquals(PasswordRules.TOO_COMMON_CODE, PasswordRules.problemWith("  password123  "))
    }

    @Test
    fun lengthCountsCharactersAPersonSeesNotUtf16Units() {
        val emoji = "😀" // one code point, two UTF-16 units
        assertEquals(PasswordRules.TOO_SHORT_CODE, PasswordRules.problemWith(emoji.repeat(5)))
        assertNull(PasswordRules.problemWith(emoji.repeat(10)))
    }

    @Test
    fun theCallersNormaliserIsAppliedBeforeLengthAndTheList() {
        // Stand-in for the server's NFKC: full-width ASCII folds to ASCII.
        val fold: (String) -> String = { s -> s.map { c -> if (c in '！'..'～') (c.code - 0xFEE0).toChar() else c }.joinToString("") }
        val fullWidth = "Password123".map { (it.code + 0xFEE0).toChar() }.joinToString("")

        assertNull(PasswordRules.problemWith(fullWidth), "without a normaliser the look-alike passes (the client gap)")
        assertEquals(PasswordRules.TOO_COMMON_CODE, PasswordRules.problemWith(fullWidth, fold))
    }

    @Test
    fun commonPaddedShapesAreListed() {
        listOf("password1!", "Password123", "Qwerty12345!", "1234567890", "qwertyuiop", "iloveyou12", "Football2025", "Welcome123!")
            .forEach { assertEquals(PasswordRules.TOO_COMMON_CODE, PasswordRules.problemWith(it), it) }
    }

    @Test
    fun everyListedPasswordIsLongEnoughToMatterAndLowercase() {
        assertTrue(PasswordRules.commonPasswords.size >= 300)
        for (entry in PasswordRules.commonPasswords) {
            assertTrue(entry.length >= PasswordRules.MIN_LENGTH, "'$entry' is refused by length already")
            assertEquals(entry.lowercase(), entry)
        }
    }

    @Test
    fun theDevAndQaPasswordsAreNotOnTheList() {
        // Seeds, dev login and the QA scripts sign up with these; the list must never lock them out.
        listOf("PynapliDevPass123!", "PynapliLoadPass123!", "OnboardingQaPass123!", "WebQaSignupPass123!", "LongEnoughPassword1!")
            .forEach { assertNull(PasswordRules.problemWith(it), it) }
    }
}
