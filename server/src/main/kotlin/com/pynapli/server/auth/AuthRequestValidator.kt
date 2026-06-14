package com.pynapli.server.auth

import com.pynapli.server.auth.security.normalizeEmail
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.Period

internal class AuthRequestValidator(
    private val clock: Clock,
) {
    fun validateEmailPassword(
        email: String,
        password: String,
    ) {
        if (!normalizeEmail(email).contains("@")) throw AuthException("INVALID_EMAIL")
        if (password.length < 12) throw AuthException("PASSWORD_TOO_SHORT")
    }

    fun validateSignupAgeGate(
        birthdate: String?,
        confirmedAdult: Boolean,
        acceptedTerms: Boolean,
        acceptedPrivacy: Boolean,
    ): SignupAgeGate {
        if (!confirmedAdult) throw AuthException("ADULT_CONFIRMATION_REQUIRED")
        if (!acceptedTerms) throw AuthException("TERMS_ACCEPTANCE_REQUIRED")
        if (!acceptedPrivacy) throw AuthException("PRIVACY_ACCEPTANCE_REQUIRED")

        val parsedBirthdate =
            birthdate
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { value ->
                    runCatching { LocalDate.parse(value) }.getOrElse { throw AuthException("INVALID_BIRTHDATE") }
                }
                ?: throw AuthException("BIRTHDATE_REQUIRED")

        val age = Period.between(parsedBirthdate, LocalDate.now(clock)).years
        if (age < 18) throw AuthException("AGE_RESTRICTED")

        return SignupAgeGate(
            birthdate = parsedBirthdate,
            displayAge = age,
            acceptedAt = clock.instant(),
        )
    }
}

internal data class SignupAgeGate(
    val birthdate: LocalDate,
    val displayAge: Int,
    val acceptedAt: Instant,
)
