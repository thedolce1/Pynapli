package com.pynapli.server.verification

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.sql.DataSource

internal class JdbcAgeVerificationReadActions(
    private val dataSource: DataSource,
) {
    suspend fun getProfileState(userId: UUID): AgeVerificationProfileState? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        SELECT user_id, birthdate, age_verified_at
                        FROM profiles
                        WHERE user_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, userId)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) rs.toAgeVerificationProfileState() else null
                        }
                    }
            }
        }

    suspend fun latestForUser(userId: UUID): AgeVerificationRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        SELECT *
                        FROM age_verifications
                        WHERE user_id = ?
                        ORDER BY created_at DESC, requested_at DESC
                        LIMIT 1
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, userId)
                        statement.executeQuery().use { rs -> if (rs.next()) rs.toAgeVerificationRecord() else null }
                    }
            }
        }

    suspend fun findById(verificationId: UUID): AgeVerificationRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement("SELECT * FROM age_verifications WHERE id = ?").use { statement ->
                    statement.setObject(1, verificationId)
                    statement.executeQuery().use { rs -> if (rs.next()) rs.toAgeVerificationRecord() else null }
                }
            }
        }

    suspend fun findByProviderReference(
        provider: String,
        providerReference: String,
    ): AgeVerificationRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        SELECT *
                        FROM age_verifications
                        WHERE provider = ? AND provider_reference = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, provider)
                        statement.setString(2, providerReference)
                        statement.executeQuery().use { rs -> if (rs.next()) rs.toAgeVerificationRecord() else null }
                    }
            }
        }
}
