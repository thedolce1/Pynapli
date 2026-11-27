package com.pynapli.server.dependencies

import com.pynapli.server.config.ServerConfig
import com.pynapli.server.config.isProductionLikeEnvironment
import com.pynapli.server.contactblocks.ContactBlockVisibility
import com.pynapli.server.db.PostgresJobLock
import com.pynapli.server.metinperson.AdminMetInPersonService
import com.pynapli.server.metinperson.JdbcMetInPersonSql.storedKeyCheckValue
import com.pynapli.server.metinperson.MetInPersonAccountData
import com.pynapli.server.metinperson.MetInPersonBadgeBatch
import com.pynapli.server.metinperson.MetInPersonBadgeWorker
import com.pynapli.server.metinperson.MetInPersonService
import com.pynapli.server.metinperson.MetInPersonTokens
import org.slf4j.LoggerFactory
import javax.sql.DataSource

/** "Met in person" wiring (TRUST_AND_SAFETY_SPEC): one K3 token source shared by every part. */
internal class MetInPersonDependencies(
    val service: MetInPersonService,
    val adminService: AdminMetInPersonService,
    val badgeWorker: MetInPersonBadgeWorker,
)

internal fun metInPersonTokens(config: ServerConfig): MetInPersonTokens = MetInPersonTokens(config.metInPersonTokenSecret)

internal fun metInPersonAccountData(config: ServerConfig): MetInPersonAccountData = MetInPersonAccountData(metInPersonTokens(config))

/**
 * Startup, after migrations: when a K3 check value is stored and the configured key does not reproduce
 * it (a lost, rotated or mistyped key), staging and production refuse to start, since every
 * confirmation would stop matching (writes, deletion and export already refuse at run time). Local only
 * warns. Read only: the value is stored on first use, never here.
 */
internal fun verifyMetInPersonKeyAtStartup(
    config: ServerConfig,
    dataSource: DataSource,
) {
    val stored = dataSource.connection.use { connection -> connection.storedKeyCheckValue() } ?: return
    if (stored == metInPersonTokens(config).checkValue()) return
    if (config.isProductionLikeEnvironment()) {
        throw IllegalStateException(
            "PYNAPLI_MET_IN_PERSON_TOKEN_SECRET does not match the key the stored Met in person confirmations were made with.",
        )
    }
    LoggerFactory
        .getLogger("com.pynapli.server.metinperson.MetInPersonKey")
        .warn("Met in person key does not match the stored check value; the feature refuses to run until it does")
}

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
