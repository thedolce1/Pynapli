package com.pynapli.server.notifications

import com.pynapli.server.config.ServerConfig
import com.pynapli.server.moderation.requireAdminActor
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

/**
 * Admin-only scrape endpoint for the privacy-safe push-delivery counters
 * (PRODUCTION_READINESS_AND_QUALITY_SPEC: "Push send success/failure metrics"). The counters are
 * counts-only, keyed by low-cardinality outcome/kind/platform labels — they never include
 * recipients, bodies, tokens, or lifestyle data — but the endpoint is still gated behind the admin
 * API token (same `X-Admin-Token` + `X-Admin-Id` scheme as the moderation admin routes) so the
 * operational surface is not public. Registered only when an admin token is configured AND the
 * dispatch worker is running.
 */
fun Application.configureAdminMetricsRoutes(
    config: ServerConfig,
    notificationDispatchWorker: NotificationDispatchWorker?,
) {
    val adminApiToken = config.adminApiToken ?: return
    if (notificationDispatchWorker == null) return

    routing {
        route("/v1/admin/metrics/notifications") {
            get {
                call.requireAdminActor(adminApiToken)
                val samples = notificationDispatchWorker.metrics().snapshot()
                call.respond(
                    NotificationMetricsResponseDto(
                        samples =
                            samples.map { sample ->
                                NotificationMetricSampleDto(
                                    outcome = sample.outcome.name,
                                    kind = sample.kind,
                                    platform = sample.platform,
                                    count = sample.count,
                                )
                            },
                        totalSent = notificationDispatchWorker.metrics().totalFor(NotificationMetrics.Outcome.SENT),
                        totalFailed = notificationDispatchWorker.metrics().totalFor(NotificationMetrics.Outcome.FAILED),
                    ),
                )
            }
        }
    }
}

@Serializable
data class NotificationMetricsResponseDto(
    val samples: List<NotificationMetricSampleDto>,
    val totalSent: Long,
    val totalFailed: Long,
)

@Serializable
data class NotificationMetricSampleDto(
    val outcome: String,
    val kind: String,
    val platform: String,
    val count: Long,
)
