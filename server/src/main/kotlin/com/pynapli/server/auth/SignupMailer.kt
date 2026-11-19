package com.pynapli.server.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Delivers the two sign-up emails (ONBOARDING_AUTH_SPEC, "Membership privacy"). Production needs a
 * real email provider (founder setup, docs/PATH_TO_TESTERS.md); until one is wired, sign-up answers
 * SIGNUP_UNAVAILABLE for every address alike.
 *
 * A provider must send exactly the wording in [SignupEmailWording]: it names no product and no
 * lifestyle, so a shared inbox, a lock-screen preview or a mail-provider category label gives
 * nothing away.
 */
interface SignupMailer {
    val isConfigured: Boolean

    /** The 6-digit code for a new sign-up. */
    suspend fun sendSignupCode(
        email: String,
        code: String,
    )

    /** Someone tried to sign up with an address that already has an account. Creates nothing. */
    suspend fun sendAlreadyRegisteredNotice(email: String)
}

/**
 * The sign-up email copy. There was no existing email template to follow (the reset mailer has no
 * provider yet and the local mailer writes only "timestamp address code"), so these set the
 * convention: a neutral sender name, a neutral subject, no product name, no link, and nothing that
 * says what kind of app this is. Founder to confirm before a provider goes live.
 */
object SignupEmailWording {
    const val SENDER_NAME = "Account verification"
    const val CODE_SUBJECT = "Your verification code"
    const val NOTICE_SUBJECT = "Account notice"

    fun codeBody(code: String): String =
        """
        Your verification code is $code

        Enter it in the app to finish creating your account. It works for 30 minutes.

        If you didn't ask for this, ignore this email. No account is created without the code.
        """.trimIndent()

    val NOTICE_BODY: String =
        """
        Someone just tried to create a new account with this email address.

        You already have an account with this address, so nothing was created and nothing was changed.
        To get in, sign in with your password, or choose "Forgot password?" on the sign-in screen to set a new one.

        If this wasn't you, you can ignore this email. Your account and password are unchanged.
        """.trimIndent()
}

/**
 * Local development only: appends each sign-up email to a git-ignored file (default
 * `.pynapli-local-mail/signup-codes.txt`, next to the reset codes) so the flow can be exercised
 * without an email provider, and remembers the last one per address in memory for the dev-only
 * code lookup (`POST /v1/dev/auth/email/signup/latest-code`) that the QA scripts use. Codes never go
 * to the server log. Never wired outside PYNAPLI_ENV=local.
 */
class LocalFileSignupMailer(
    private val file: File = File(".pynapli-local-mail/signup-codes.txt"),
) : SignupMailer {
    override val isConfigured: Boolean = true

    private val latest = ConcurrentHashMap<String, LocalSignupMail>()

    override suspend fun sendSignupCode(
        email: String,
        code: String,
    ) {
        latest[email.trim().lowercase()] = LocalSignupMail(code = code, alreadyRegisteredNotice = false)
        append("${Instant.now()} $email $code")
    }

    override suspend fun sendAlreadyRegisteredNotice(email: String) {
        latest[email.trim().lowercase()] = LocalSignupMail(code = null, alreadyRegisteredNotice = true)
        append("${Instant.now()} $email already-registered-notice")
    }

    /** The last sign-up email "sent" to [email] by this process, if any. */
    fun latestFor(email: String): LocalSignupMail? = latest[email.trim().lowercase()]

    private suspend fun append(line: String) {
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            file.appendText(line + System.lineSeparator())
        }
    }
}

data class LocalSignupMail(
    val code: String?,
    val alreadyRegisteredNotice: Boolean,
)

/** No provider configured: sign-up answers SIGNUP_UNAVAILABLE (503) before doing anything. */
object UnconfiguredSignupMailer : SignupMailer {
    override val isConfigured: Boolean = false

    override suspend fun sendSignupCode(
        email: String,
        code: String,
    ) = throw IllegalStateException("No email provider configured")

    override suspend fun sendAlreadyRegisteredNotice(email: String) = throw IllegalStateException("No email provider configured")
}
