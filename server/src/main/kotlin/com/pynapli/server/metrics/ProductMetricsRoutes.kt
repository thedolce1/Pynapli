package com.pynapli.server.metrics

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
 * Admin-only scrape endpoint for the authoritative product-event KPI counters
 * (ANALYTICS_AND_METRICS_SPEC / PRODUCTION_READINESS_AND_QUALITY_SPEC). Counts-only, keyed by a
 * low-cardinality event label — never users, bodies, tokens, or lifestyle data — but still gated
 * behind the admin API token (same `X-Admin-Token` + `X-Admin-Id` scheme as the moderation/
 * notification-metrics admin routes) so the operational surface isn't public. Registered only when
 * an admin token is configured.
 */
fun Application.configureProductMetricsRoutes(
    config: ServerConfig,
    productMetrics: ProductMetrics?,
) {
    val adminApiToken = config.adminApiToken ?: return
    if (productMetrics == null) return

    routing {
        route("/v1/admin/metrics/product") {
            get {
                call.requireAdminActor(adminApiToken)
                call.respond(
                    ProductMetricsResponseDto(
                        samples = productMetrics.snapshot().map { ProductMetricSampleDto(event = it.event, count = it.count) },
                    ),
                )
            }
        }
    }
}

@Serializable
data class ProductMetricsResponseDto(
    val samples: List<ProductMetricSampleDto>,
)

@Serializable
data class ProductMetricSampleDto(
    val event: String,
    val count: Long,
)
