package com.pynapli.server.dependencies

import com.pynapli.server.config.ServerConfig
import com.pynapli.server.contactblocks.ContactBlockVisibility
import com.pynapli.server.db.PostgresJobLock
import com.pynapli.server.metinperson.AdminMetInPersonService
import com.pynapli.server.metinperson.MetInPersonAccountData
import com.pynapli.server.metinperson.MetInPersonBadgeBatch
import com.pynapli.server.metinperson.MetInPersonBadgeWorker
import com.pynapli.server.metinperson.MetInPersonService
import com.pynapli.server.metinperson.MetInPersonTokens
import javax.sql.DataSource

/** "Met in person" wiring (TRUST_AND_SAFETY_SPEC): one K3 token source shared by every part. */
internal class MetInPersonDependencies(
    val service: MetInPersonService,
    val adminService: AdminMetInPersonService,
    val badgeWorker: MetInPersonBadgeWorker,
)

internal fun metInPersonTokens(config: ServerConfig): MetInPersonTokens = MetInPersonTokens(config.metInPersonTokenSecret)

internal fun metInPersonAccountData(config: ServerConfig): MetInPersonAccountData = MetInPersonAccountData(metInPersonTokens(config))

internal fun buildMetInPersonDependencies(
    config: ServerConfig,
    dataSource: DataSource,
    // "Hide from people you know": the viewer-side rule every Met in person answer applies.
    contactBlockVisibility: ContactBlockVisibility,
): MetInPersonDependencies {
    val tokens = metInPersonTokens(config)
    return MetInPersonDependencies(
        service = MetInPersonService(dataSource = dataSource, tokens = tokens, contactBlockVisibility = contactBlockVisibility),
        adminService = AdminMetInPersonService(dataSource = dataSource, tokens = tokens),
        // Weekly at a random time; ticks are cheap until it is due. One replica per tick (JobLock),
        // and the schedule row makes it once per week even across replicas.
        badgeWorker =
            MetInPersonBadgeWorker(
                batch = MetInPersonBadgeBatch(dataSource = dataSource, tokens = tokens),
                jobLock = PostgresJobLock(dataSource),
            ),
    )
}
