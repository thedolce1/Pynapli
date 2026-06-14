package com.pynapli.server.profile

import java.util.UUID
import javax.sql.DataSource

class JdbcProfileRepository(
    dataSource: DataSource,
) : ProfileRepository {
    private val readActions = JdbcProfileReadActions(dataSource)
    private val mutationActions = JdbcProfileMutationActions(dataSource, readActions)

    override suspend fun getMe(userId: UUID): MeRecord? = readActions.getMe(userId)

    override suspend fun getPublicProfile(
        viewerUserId: UUID,
        targetUserId: UUID,
    ): PublicProfileRecord? =
        readActions.getPublicProfile(
            viewerUserId = viewerUserId,
            targetUserId = targetUserId,
        )

    override suspend fun updateProfile(
        userId: UUID,
        update: ProfileUpdate,
    ): MeRecord? =
        mutationActions.updateProfile(
            userId = userId,
            update = update,
        )

    override suspend fun updateDiscoverySettings(
        userId: UUID,
        update: DiscoverySettingsUpdate,
    ): MeRecord? =
        mutationActions.updateDiscoverySettings(
            userId = userId,
            update = update,
        )
}
