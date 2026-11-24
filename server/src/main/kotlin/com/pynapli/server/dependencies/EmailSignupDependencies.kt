package com.pynapli.server.dependencies

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.BoundedWorkLauncher
import com.pynapli.server.auth.EmailSignupService
import com.pynapli.server.auth.JdbcEmailSignupRepository
import com.pynapli.server.auth.LocalFileSignupMailer
import com.pynapli.server.auth.UnconfiguredSignupMailer
import com.pynapli.server.config.ServerConfig
import com.pynapli.server.safety.RateLimitService
import kotlinx.coroutines.CoroutineScope

internal class EmailSignupDependencies(
    val service: EmailSignupService,
    /** Non-null only when the local-dev sign-up shortcuts may be served (see [buildEmailSignupDependencies]). */
    val devMailbox: LocalFileSignupMailer?,
)

/**
 * Email sign-up (ONBOARDING_AUTH_SPEC, "Email sign-up"). Mailer selection follows the reset
 * mailer exactly: the local file mailer only for PYNAPLI_PASSWORD_RESET_MAILER=log with
 * PYNAPLI_ENV=local, otherwise no provider (sign-up answers SIGNUP_UNAVAILABLE) until a real one is
 * wired (founder setup). Member-only work runs on [backgroundScope], the application's background
 * scope, which is cancelled when the server stops (on Cloud Run it needs CPU after the response:
 * docs/PATH_TO_TESTERS.md).
 */
internal fun buildEmailSignupDependencies(
    config: ServerConfig,
    database: Database,
    authService: AuthService,
    rateLimitService: RateLimitService,
    backgroundScope: CoroutineScope,
    // "Hide from people you know": binds rows naming the address before the first session exists.
    onAccountVerified: (suspend (userId: java.util.UUID, normalisedEmail: String) -> Unit)? = null,
): EmailSignupDependencies {
    val localMailer = if (config.usesLocalFileMailer()) LocalFileSignupMailer() else null
    val service =
        EmailSignupService(
            repository = JdbcEmailSignupRepository(database.dataSource),
            mailer = localMailer ?: UnconfiguredSignupMailer,
            authService = authService,
            rateLimitService = rateLimitService,
            // Bounded: a flood of sign-ups can't take the whole database pool; a dropped job only
            // means no email, and the request answers the same either way.
            launchSignupWork = BoundedWorkLauncher(backgroundScope)::launch,
            onAccountVerified = onAccountVerified,
        )
    val devMailbox = localMailer?.takeIf { config.devSignupShortcutsAllowed() }
    return EmailSignupDependencies(service = service, devMailbox = devMailbox)
}

/** The mailer that writes codes to a local file: refused anywhere but PYNAPLI_ENV=local, as for reset. */
internal fun ServerConfig.usesLocalFileMailer(): Boolean = passwordResetMailer == "log" && environment == "local"

/**
 * Three locks on wiring the dev sign-up helpers: the flag (off by default, refused at startup in
 * production-like environments), a local environment, and the local file mailer actually being in
 * use. The routes add a fourth: direct loopback requests only.
 */
internal fun ServerConfig.devSignupShortcutsAllowed(): Boolean = devSignupShortcutsEnabled && usesLocalFileMailer()
