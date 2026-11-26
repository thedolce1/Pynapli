package com.pynapli.server.auth

import com.pynapli.server.auth.security.PasswordHash
import com.pynapli.server.db.inTransaction
import com.pynapli.server.db.withConnection
import java.sql.Connection
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

internal class JdbcAuthUserActions(
    private val dataSource: DataSource,
) {
    suspend fun createEmailUser(
        userId: UUID,
        identityId: UUID,
        emailHash: String,
        passwordHash: PasswordHash,
        birthdate: LocalDate,
        displayAge: Int,
        adultConfirmedAt: Instant,
        termsAcceptedAt: Instant,
        termsVersion: String,
        privacyAcceptedAt: Instant,
        privacyVersion: String,
    ): UserRecord =
        dataSource.withConnection { connection ->
            connection.inTransaction {
                insertEmailAccount(
                    userId = userId,
                    identityId = identityId,
                    emailHash = emailHash,
                    passwordHash = passwordHash,
                    birthdate = birthdate,
                    displayAge = displayAge,
                    adultConfirmedAt = adultConfirmedAt,
                    termsAcceptedAt = termsAcceptedAt,
                    termsVersion = termsVersion,
                    privacyAcceptedAt = privacyAcceptedAt,
                    privacyVersion = privacyVersion,
                    emailVerifiedAt = null,
                )
            }
        }

    suspend fun findEmailIdentity(emailHash: String): EmailIdentityRecord? =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT ai.user_id, ai.password_hash, ai.password_salt, ai.password_iterations, u.status
                    FROM auth_identities ai
                    JOIN users u ON u.id = ai.user_id
                    WHERE ai.provider = 'email'
                      AND ai.provider_subject = ?
                      AND u.deleted_at IS NULL
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, emailHash)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) rs.toEmailIdentityRecord() else null
                    }
                }
        }

    suspend fun findUser(userId: UUID): UserRecord? =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement("SELECT id, status FROM users WHERE id = ? AND deleted_at IS NULL")
                .use { statement ->
                    statement.setObject(1, userId)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) rs.toUserRecord() else null
                    }
                }
        }
}

/**
 * Whether a non-deleted account holds the address (its SHA-256 [emailHash]): users.email_hash (any
 * sign-in method), or an email identity. The one predicate for "this address has an account", used by
 * email sign-up's notice decision and by social sign-in's SOCIAL_EMAIL_HAS_ACCOUNT, so they never
 * disagree.
 */
internal fun Connection.addressHasAccount(emailHash: String): Boolean =
    prepareStatement(
        """
        SELECT 1 FROM users WHERE email_hash = ? AND deleted_at IS NULL
        UNION ALL
        SELECT 1 FROM auth_identities ai JOIN users u ON u.id = ai.user_id
        WHERE ai.provider = 'email' AND ai.provider_subject = ? AND u.deleted_at IS NULL
        LIMIT 1
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, emailHash)
        statement.setString(2, emailHash)
        statement.executeQuery().use { rs -> rs.next() }
    }

/**
 * Every row a new email account starts with: see [insertAccount]. The verified sign-up
 * (EmailSignupService) and the local-only dev shortcut both come here. [emailVerifiedAt] is set for
 * accounts whose address was proven with an emailed code.
 */
internal fun Connection.insertEmailAccount(
    userId: UUID,
    identityId: UUID,
    emailHash: String,
    passwordHash: PasswordHash,
    birthdate: LocalDate,
    displayAge: Int,
    adultConfirmedAt: Instant,
    termsAcceptedAt: Instant,
    termsVersion: String,
    privacyAcceptedAt: Instant,
    privacyVersion: String,
    emailVerifiedAt: Instant?,
): UserRecord =
    insertAccount(
        userId = userId,
        emailHash = emailHash,
        birthdate = birthdate,
        displayAge = displayAge,
        adultConfirmedAt = adultConfirmedAt,
        termsAcceptedAt = termsAcceptedAt,
        termsVersion = termsVersion,
        privacyAcceptedAt = privacyAcceptedAt,
        privacyVersion = privacyVersion,
        emailVerifiedAt = emailVerifiedAt,
    ) { insertEmailIdentity(identityId, userId, emailHash, passwordHash) }

/** A Google or Apple identity a new account starts with (no password). */
class NewSocialIdentity(
    val identityId: UUID,
    val provider: String,
    val subject: String,
    val emailVerified: Boolean,
    val emailHint: String?,
)

/**
 * Every row a new social account starts with (Continue with Google / Apple): the same account rows as
 * email sign-up, with the provider identity instead of the email one. [emailHash] and [emailVerifiedAt]
 * are set only when the provider vouched for the person's real address (SocialIdentityVerifier
 * `authoritativeEmail`); otherwise both are null and the account has no address at all.
 */
internal fun Connection.insertSocialAccount(
    userId: UUID,
    identity: NewSocialIdentity,
    emailHash: String?,
    birthdate: LocalDate,
    displayAge: Int,
    acceptedAt: Instant,
    termsVersion: String,
    privacyVersion: String,
    emailVerifiedAt: Instant?,
): UserRecord =
    insertAccount(
        userId = userId,
        emailHash = emailHash,
        birthdate = birthdate,
        displayAge = displayAge,
        adultConfirmedAt = acceptedAt,
        termsAcceptedAt = acceptedAt,
        termsVersion = termsVersion,
        privacyAcceptedAt = acceptedAt,
        privacyVersion = privacyVersion,
        emailVerifiedAt = emailVerifiedAt,
    ) { insertSocialIdentity(userId, identity) }

/**
 * Every row a new account starts with, in the order sign-up has always written them: the user, its
 * first identity ([insertIdentity]), the profile and the discovery settings. Must run inside a
 * transaction. The one place an account is created, so an account looks the same whichever path
 * (email sign-up, the dev shortcut, Google, Apple) made it.
 */
internal fun Connection.insertAccount(
    userId: UUID,
    emailHash: String?,
    birthdate: LocalDate,
    displayAge: Int,
    adultConfirmedAt: Instant,
    termsAcceptedAt: Instant,
    termsVersion: String,
    privacyAcceptedAt: Instant,
    privacyVersion: String,
    emailVerifiedAt: Instant?,
    insertIdentity: Connection.() -> Unit,
): UserRecord {
    insertUser(
        userId = userId,
        emailHash = emailHash,
        adultConfirmedAt = adultConfirmedAt,
        termsAcceptedAt = termsAcceptedAt,
        termsVersion = termsVersion,
        privacyAcceptedAt = privacyAcceptedAt,
        privacyVersion = privacyVersion,
        emailVerifiedAt = emailVerifiedAt,
    )
    insertIdentity()
    insertProfile(userId, birthdate, displayAge)
    insertDiscoverySettings(userId)
    return UserRecord(id = userId, status = "active")
}

internal fun Connection.insertSocialIdentity(
    userId: UUID,
    identity: NewSocialIdentity,
) {
    prepareStatement(
        """
        INSERT INTO auth_identities (
            id, user_id, provider, provider_subject, email_verified, provider_email_hint, linked_at, last_used_at
        )
        VALUES (?, ?, ?, ?, ?, ?, now(), now())
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, identity.identityId)
        statement.setObject(2, userId)
        statement.setString(3, identity.provider)
        statement.setString(4, identity.subject)
        statement.setBoolean(5, identity.emailVerified)
        statement.setString(6, identity.emailHint)
        statement.executeUpdate()
    }
}

private fun Connection.insertUser(
    userId: UUID,
    emailHash: String?,
    adultConfirmedAt: Instant,
    termsAcceptedAt: Instant,
    termsVersion: String,
    privacyAcceptedAt: Instant,
    privacyVersion: String,
    emailVerifiedAt: Instant?,
) {
    prepareStatement(
        """
        INSERT INTO users (
            id, email_hash, status, adult_confirmed_at,
            terms_accepted_at, terms_version,
            privacy_accepted_at, privacy_version,
            email_verified_at
        )
        VALUES (?, ?, 'active', ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, userId)
        statement.setString(2, emailHash)
        statement.setTimestamp(3, Timestamp.from(adultConfirmedAt))
        statement.setTimestamp(4, Timestamp.from(termsAcceptedAt))
        statement.setString(5, termsVersion)
        statement.setTimestamp(6, Timestamp.from(privacyAcceptedAt))
        statement.setString(7, privacyVersion)
        statement.setTimestamp(8, emailVerifiedAt?.let(Timestamp::from))
        statement.executeUpdate()
    }
}

private fun Connection.insertEmailIdentity(
    identityId: UUID,
    userId: UUID,
    emailHash: String,
    passwordHash: PasswordHash,
) {
    prepareStatement(
        """
        INSERT INTO auth_identities (
            id, user_id, provider, provider_subject,
            password_hash, password_salt, password_iterations, last_used_at
        )
        VALUES (?, ?, 'email', ?, ?, ?, ?, now())
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, identityId)
        statement.setObject(2, userId)
        statement.setString(3, emailHash)
        statement.setString(4, passwordHash.hash)
        statement.setString(5, passwordHash.salt)
        statement.setInt(6, passwordHash.iterations)
        statement.executeUpdate()
    }
}

private fun Connection.insertProfile(
    userId: UUID,
    birthdate: LocalDate,
    displayAge: Int,
) {
    prepareStatement(
        """
        INSERT INTO profiles (user_id, birthdate, display_age, profile_completeness_score)
        VALUES (?, ?, ?, 25)
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, userId)
        statement.setDate(2, Date.valueOf(birthdate))
        statement.setInt(3, displayAge)
        statement.executeUpdate()
    }
}

private fun Connection.insertDiscoverySettings(userId: UUID) {
    prepareStatement("INSERT INTO discovery_settings (user_id) VALUES (?)").use { statement ->
        statement.setObject(1, userId)
        statement.executeUpdate()
    }
}
