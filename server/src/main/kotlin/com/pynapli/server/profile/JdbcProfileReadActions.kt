package com.pynapli.server.profile

import com.pynapli.server.profiling.profileBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.sql.DataSource

internal class JdbcProfileReadActions(
    private val dataSource: DataSource,
) {
    suspend fun getMe(userId: UUID): MeRecord? =
        withContext(Dispatchers.IO) {
            profileBlocking("profile.db.connectionWait") { dataSource.connection }.use { connection ->
                profileBlocking("profile.db.meQuery") {
                    connection.prepareStatement(JdbcProfileSql.ME_QUERY).use { statement ->
                        statement.setObject(1, userId)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) rs.toMeRecord() else null
                        }
                    }
                }
            }
        }

    suspend fun getPublicProfile(
        viewerUserId: UUID,
        targetUserId: UUID,
    ): PublicProfileRecord? =
        withContext(Dispatchers.IO) {
            profileBlocking("profile.db.connectionWait") { dataSource.connection }.use { connection ->
                profileBlocking("profile.db.publicQuery") {
                    connection.prepareStatement(JdbcProfileSql.PUBLIC_PROFILE_QUERY).use { statement ->
                        statement.setObject(1, viewerUserId)
                        statement.setObject(2, viewerUserId)
                        statement.setObject(3, viewerUserId)
                        statement.setObject(4, viewerUserId)
                        statement.setObject(5, viewerUserId)
                        statement.setObject(6, viewerUserId) // partner block check (blocker side)
                        statement.setObject(7, viewerUserId) // partner block check (blocked side)
                        statement.setObject(8, viewerUserId) // partner-visibility verified_only gate
                        statement.setObject(9, viewerUserId) // partner-visibility matches_only gate (LEAST)
                        statement.setObject(10, viewerUserId) // partner-visibility matches_only gate (GREATEST)
                        statement.setObject(11, targetUserId)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) rs.toPublicProfileRecord() else null
                        }
                    }
                }
            }
        }
}
