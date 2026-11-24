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
    fun everyListedPasswordIsLongEnoughToMatterAndLowercase() {
        assertTrue(PasswordRules.commonPasswords.size >= 100)
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
