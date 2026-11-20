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
 * Every row a new email account starts with, in the order sign-up has always written them: the
 * user, its email identity, the profile and the discovery settings. Must run inside a transaction.
 * The one place an email account is created: the verified sign-up (EmailSignupService) and the
 * local-only dev shortcut both come here, so an account looks the same whichever path made it.
 * [emailVerifiedAt] is set for accounts whose address was proven with an emailed code.
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
    insertEmailIdentity(identityId, userId, emailHash, passwordHash)
    insertProfile(userId, birthdate, displayAge)
    insertDiscoverySettings(userId)
    return UserRecord(id = userId, status = "active")
}

private fun Connection.insertUser(
    userId: UUID,
    emailHash: String,
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
