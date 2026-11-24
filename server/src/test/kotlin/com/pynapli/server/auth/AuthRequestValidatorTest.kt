package com.pynapli.server.auth

import com.pynapli.server.plugins.userSafeMessage
import com.pynapli.shared.auth.PasswordRules
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuthRequestValidatorTest {
    private val validator = AuthRequestValidator(Clock.systemUTC())

    @Test
    fun aNewPasswordNeedsTenCharactersAndMustNotBeCommon() {
        assertEquals("PASSWORD_TOO_SHORT", assertFailsWith<AuthException> { validator.validateNewPassword("a@example.com", "123456789") }.code)
        assertEquals("PASSWORD_TOO_COMMON", assertFailsWith<AuthException> { validator.validateNewPassword("a@example.com", "QwertyUiop") }.code)
        assertEquals("PASSWORD_TOO_COMMON", assertFailsWith<AuthException> { validator.validateNewPassword("1234567890") }.code)
        validator.validateNewPassword("a@example.com", "ten-chars!")
        validator.validateNewPassword("a@example.com", "PynapliDevPass123!")
    }

    @Test
    fun signInNeverAppliesTheDenylist() {
        // An existing member whose password is on the list must still get in.
        validator.validateEmailPassword("member@example.com", "password1234")
        assertEquals("PASSWORD_TOO_SHORT", assertFailsWith<AuthException> { validator.validateEmailPassword("member@example.com", "short") }.code)
    }

    @Test
    fun theCopyComesFromTheSharedRuleTheClientShows() {
        assertEquals(PasswordRules.TOO_SHORT_MESSAGE, userSafeMessage("PASSWORD_TOO_SHORT"))
        assertEquals(PasswordRules.TOO_COMMON_MESSAGE, userSafeMessage("PASSWORD_TOO_COMMON"))
        assertEquals("Password must be at least 10 characters.", PasswordRules.TOO_SHORT_MESSAGE)
    }
}
