package com.pynapli.server.auth.social

import com.pynapli.server.auth.NewSocialIdentity
import com.pynapli.server.auth.UserRecord
import com.pynapli.server.auth.insertSocialAccount
import com.pynapli.server.auth.insertSocialIdentity
import com.pynapli.server.db.inTransaction
import com.pynapli.server.db.withConnection
import org.postgresql.util.PSQLException
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/** A provider identity's account, as sign-in needs it. Deleted accounts never match. */
class SocialIdentityRecord(
    val identityId: UUID,
    val userId: UUID,
    val userStatus: String,
    /** users.email_hash of the account (null when it has no proven address). */
    val accountEmailHash: String?,
    val hasAppleRefreshToken: Boolean,
)

sealed interface SocialAccountCreation {
    class Created(
        val user: UserRecord,
    ) : SocialAccountCreation

    /** users.email_hash is already some account's: nothing was created. */
    data object EmailTaken : SocialAccountCreation

    /** The provider identity got an account meanwhile (a concurrent sign-up): nothing was created. */
    data object IdentityTaken : SocialAccountCreation
}

sealed interface SocialLinkOutcome {
    data object Linked : SocialLinkOutcome

    /** This identity is already this account's: nothing changed. */
    data object AlreadyLinked : SocialLinkOutcome

    /** This identity belongs to another account. */
    data object InUse : SocialLinkOutcome

    /** The account already has a different identity of this provider. */
    data object ProviderAlreadyLinked : SocialLinkOutcome
}

sealed interface SocialUnlinkOutcome {
    class Unlinked(
        val appleToken: SealedAppleToken?,
    ) : SocialUnlinkOutcome

    data object NotLinked : SocialUnlinkOutcome

    data object LastSignInMethod : SocialUnlinkOutcome
}

class IdentitySummary(
    val provider: String,
    val linkedAt: Instant,
    val emailHint: String?,
    val hasPassword: Boolean,
)

interface SocialIdentityRepository {
    suspend fun findIdentity(
        provider: SocialProvider,
        subject: String,
    ): SocialIdentityRecord?

    /** Whether a non-deleted account holds this users.email_hash (any sign-in method). */
    suspend fun emailHasAccount(emailHash: String): Boolean

    suspend fun createAccount(
        userId: UUID,
        identity: NewSocialIdentity,
        emailHash: String?,
        birthdate: LocalDate,
        displayAge: Int,
        acceptedAt: Instant,
        termsVersion: String,
        privacyVersion: String,
        emailVerifiedAt: Instant?,
    ): SocialAccountCreation

    /** last_used_at, and what the provider says about the address now. */
    suspend fun recordSignIn(
        identityId: UUID,
        emailVerified: Boolean,
        emailHint: String?,
    )

    suspend fun storeAppleRefreshToken(
        identityId: UUID,
        clientId: String,
        sealed: ByteArray,
    )

    suspend fun listIdentities(userId: UUID): List<IdentitySummary>

    /** Under a lock on the account row. Never touches users.email_hash or the contact token. */
    suspend fun link(
        userId: UUID,
        identity: NewSocialIdentity,
    ): SocialLinkOutcome

    /** Under a lock on the account row, so two concurrent unlinks can't remove the last two methods. */
    suspend fun unlink(
        userId: UUID,
        provider: SocialProvider,
    ): SocialUnlinkOutcome
}

class JdbcSocialIdentityRepository(
    private val dataSource: DataSource,
) : SocialIdentityRepository {
    override suspend fun findIdentity(
        provider: SocialProvider,
        subject: String,
    ): SocialIdentityRecord? =
        dataSource.withConnection { connection ->
            connection.findIdentity(provider.wire, subject)
        }

    override suspend fun emailHasAccount(emailHash: String): Boolean =
        dataSource.withConnection { connection ->
            connection.prepareStatement("SELECT 1 FROM users WHERE email_hash = ? AND deleted_at IS NULL").use { statement ->
                statement.setString(1, emailHash)
                statement.executeQuery().use { rs -> rs.next() }
            }
        }

    override suspend fun createAccount(
        userId: UUID,
        identity: NewSocialIdentity,
        emailHash: String?,
        birthdate: LocalDate,
        displayAge: Int,
        acceptedAt: Instant,
        termsVersion: String,
        privacyVersion: String,
        emailVerifiedAt: Instant?,
    ): SocialAccountCreation =
        dataSource.withConnection { connection ->
            try {
                val user =
                    connection.inTransaction {
                        insertSocialAccount(
                            userId = userId,
                            identity = identity,
                            emailHash = emailHash,
                            birthdate = birthdate,
                            displayAge = displayAge,
                            acceptedAt = acceptedAt,
                            termsVersion = termsVersion,
                            privacyVersion = privacyVersion,
                            emailVerifiedAt = emailVerifiedAt,
                        )
                    }
                SocialAccountCreation.Created(user)
            } catch (error: SQLException) {
                when (error.uniqueConstraint()) {
                    null -> throw error
                    USERS_EMAIL_HASH_CONSTRAINT -> SocialAccountCreation.EmailTaken
                    else -> SocialAccountCreation.IdentityTaken
                }
            }
        }

    override suspend fun recordSignIn(
        identityId: UUID,
        emailVerified: Boolean,
        emailHint: String?,
    ) {
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    "UPDATE auth_identities SET last_used_at = now(), email_verified = ?, provider_email_hint = COALESCE(?, provider_email_hint) WHERE id = ?",
                ).use { statement ->
                    statement.setBoolean(1, emailVerified)
                    statement.setString(2, emailHint)
                    statement.setObject(3, identityId)
                    statement.executeUpdate()
                }
        }
    }

    override suspend fun storeAppleRefreshToken(
        identityId: UUID,
        clientId: String,
        sealed: ByteArray,
    ) {
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    // Only while the row is still a live Apple identity (not unlinked or anonymised meanwhile).
                    """
                    UPDATE auth_identities
                    SET apple_refresh_token_sealed = ?, apple_client_id = ?
                    WHERE id = ? AND provider = 'apple' AND provider_subject NOT LIKE 'deleted:%'
                    """.trimIndent(),
                ).use { statement ->
                    statement.setBytes(1, sealed)
                    statement.setString(2, clientId)
                    statement.setObject(3, identityId)
                    statement.executeUpdate()
                }
        }
    }

    override suspend fun listIdentities(userId: UUID): List<IdentitySummary> =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT ai.provider, ai.linked_at, ai.provider_email_hint, ai.password_hash IS NOT NULL AS has_password
                    FROM auth_identities ai
                    JOIN users u ON u.id = ai.user_id
                    WHERE ai.user_id = ? AND u.deleted_at IS NULL AND ai.provider_subject NOT LIKE 'deleted:%'
                    ORDER BY ai.linked_at, ai.provider
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    IdentitySummary(
                                        provider = rs.getString("provider"),
                                        linkedAt = rs.getTimestamp("linked_at").toInstant(),
                                        emailHint = rs.getString("provider_email_hint"),
                                        hasPassword = rs.getBoolean("has_password"),
                                    ),
                                )
                            }
                        }
                    }
                }
        }

    override suspend fun link(
        userId: UUID,
        identity: NewSocialIdentity,
    ): SocialLinkOutcome =
        dataSource.withConnection { connection ->
            try {
                connection.inTransaction<SocialLinkOutcome> {
                    if (!lockLiveUser(userId)) return@inTransaction SocialLinkOutcome.InUse
                    val existing = findIdentity(identity.provider, identity.subject)
                    when {
                        existing?.userId == userId -> SocialLinkOutcome.AlreadyLinked
                        existing != null -> SocialLinkOutcome.InUse
                        userHasProvider(userId, identity.provider) -> SocialLinkOutcome.ProviderAlreadyLinked
                        else -> {
                            insertSocialIdentity(userId, identity)
                            SocialLinkOutcome.Linked
                        }
                    }
                }
            } catch (error: SQLException) {
                // A concurrent link or sign-up took the identity between the check and the insert.
                if (error.uniqueConstraint() == null) throw error
                SocialLinkOutcome.InUse
            }
        }

    override suspend fun unlink(
        userId: UUID,
        provider: SocialProvider,
    ): SocialUnlinkOutcome =
        dataSource.withConnection { connection ->
            connection.inTransaction<SocialUnlinkOutcome> {
                if (!lockLiveUser(userId)) return@inTransaction SocialUnlinkOutcome.NotLinked
                val methods =
                    prepareStatement("SELECT provider FROM auth_identities WHERE user_id = ? AND provider_subject NOT LIKE 'deleted:%'").use { statement ->
                        statement.setObject(1, userId)
                        statement.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
                    }
                when {
                    provider.wire !in methods -> SocialUnlinkOutcome.NotLinked
                    methods.size <= 1 -> SocialUnlinkOutcome.LastSignInMethod
                    else -> {
                        val token =
                            prepareStatement(
                                """
                                DELETE FROM auth_identities
                                WHERE user_id = ? AND provider = ? AND provider_subject NOT LIKE 'deleted:%'
                                RETURNING id, apple_client_id, apple_refresh_token_sealed
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setObject(1, userId)
                                statement.setString(2, provider.wire)
                                statement.executeQuery().use { rs ->
                                    if (!rs.next()) return@use null
                                    val sealed = rs.getBytes("apple_refresh_token_sealed")
                                    val clientId = rs.getString("apple_client_id")
                                    if (sealed == null || clientId == null) null else SealedAppleToken(rs.getObject("id", UUID::class.java), clientId, sealed)
                                }
                            }
                        SocialUnlinkOutcome.Unlinked(token)
                    }
                }
            }
        }

    private fun Connection.findIdentity(
        provider: String,
        subject: String,
    ): SocialIdentityRecord? =
        prepareStatement(
            """
            SELECT ai.id, ai.user_id, u.status, u.email_hash, ai.apple_refresh_token_sealed IS NOT NULL AS has_token
            FROM auth_identities ai
            JOIN users u ON u.id = ai.user_id
            WHERE ai.provider = ? AND ai.provider_subject = ? AND u.deleted_at IS NULL
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, provider)
            statement.setString(2, subject)
            statement.executeQuery().use { rs ->
                if (!rs.next()) {
                    null
                } else {
                    SocialIdentityRecord(
                        identityId = rs.getObject("id", UUID::class.java),
                        userId = rs.getObject("user_id", UUID::class.java),
                        userStatus = rs.getString("status"),
                        accountEmailHash = rs.getString("email_hash"),
                        hasAppleRefreshToken = rs.getBoolean("has_token"),
                    )
                }
            }
        }

    private fun Connection.lockLiveUser(userId: UUID): Boolean =
        prepareStatement("SELECT 1 FROM users WHERE id = ? AND deleted_at IS NULL FOR UPDATE").use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { rs -> rs.next() }
        }

    private fun Connection.userHasProvider(
        userId: UUID,
        provider: String,
    ): Boolean =
        prepareStatement("SELECT 1 FROM auth_identities WHERE user_id = ? AND provider = ? AND provider_subject NOT LIKE 'deleted:%'").use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, provider)
            statement.executeQuery().use { rs -> rs.next() }
        }

    private companion object {
        const val UNIQUE_VIOLATION = "23505"

        // V2: `email_hash TEXT UNIQUE` on users, Postgres's default constraint name.
        const val USERS_EMAIL_HASH_CONSTRAINT = "users_email_hash_key"

        /** The violated unique constraint's name ("" when Postgres didn't say), or null for any other error. */
        fun SQLException.uniqueConstraint(): String? {
            if (sqlState != UNIQUE_VIOLATION) return null
            return (this as? PSQLException)?.serverErrorMessage?.constraint.orEmpty()
        }
    }
}
