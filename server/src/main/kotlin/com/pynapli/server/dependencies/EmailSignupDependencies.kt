package com.pynapli.server.dependencies

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.EmailSignupService
import com.pynapli.server.auth.JdbcEmailSignupRepository
import com.pynapli.server.auth.LocalFileSignupMailer
import com.pynapli.server.auth.UnconfiguredSignupMailer
import com.pynapli.server.config.ServerConfig
import com.pynapli.server.safety.RateLimitService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class EmailSignupDependencies(
    val service: EmailSignupService,
    /** Non-null only when the local-dev sign-up shortcuts may be served (see [buildEmailSignupDependencies]). */
    val devMailbox: LocalFileSignupMailer?,
)

/**
 * Email sign-up (ONBOARDING_AUTH_SPEC, "Membership privacy"). Mailer selection follows the reset
 * mailer exactly: the local file mailer only for PYNAPLI_PASSWORD_RESET_MAILER=log with
 * PYNAPLI_ENV=local, otherwise no provider (sign-up answers SIGNUP_UNAVAILABLE) until a real one is
 * wired (founder setup). Member-only work runs on [backgroundScope], the application's background
 * scope, which is cancelled when the server stops.
 */
internal fun buildEmailSignupDependencies(
    config: ServerConfig,
    database: Database,
    authService: AuthService,
    rateLimitService: RateLimitService,
    backgroundScope: CoroutineScope,
): EmailSignupDependencies {
    val localMailer = if (config.usesLocalFileMailer()) LocalFileSignupMailer() else null
    val service =
        EmailSignupService(
            repository = JdbcEmailSignupRepository(database.dataSource),
            mailer = localMailer ?: UnconfiguredSignupMailer,
            authService = authService,
            rateLimitService = rateLimitService,
            launchSignupWork = { work -> backgroundScope.launch { work() } },
        )
    val devMailbox = localMailer?.takeIf { config.devSignupShortcutsAllowed() }
    return EmailSignupDependencies(service = service, devMailbox = devMailbox)
}

/** The mailer that writes codes to a local file: refused anywhere but PYNAPLI_ENV=local, as for reset. */
internal fun ServerConfig.usesLocalFileMailer(): Boolean = passwordResetMailer == "log" && environment == "local"

/**
 * Three locks on the dev sign-up helpers: the flag (refused at startup in production-like
 * environments), a local environment, and the local file mailer actually being in use.
 */
internal fun ServerConfig.devSignupShortcutsAllowed(): Boolean = devSignupShortcutsEnabled && usesLocalFileMailer()
