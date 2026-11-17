package com.pynapli.server.metrics

import com.pynapli.server.auth.bearerTokenOrNull
import com.pynapli.server.profiling.DatabasePoolSnapshot
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource

/** What /metrics reports; each source is optional so tests can mount the route on its own. */
class OperationalMetricsSources(
    val poolSnapshot: () -> DatabasePoolSnapshot? = { null },
    val realtimeSubscribers: Map<String, () -> Int> = emptyMap(),
    val dataSource: DataSource? = null,
)

/**
 * Prometheus text-format scrape endpoint (`GET /metrics`) for the operational signals that tell an
 * on-call whether the API is healthy under load: database pool saturation, open realtime sockets
 * per stream on this replica, push outbox depth and the age of its oldest pending row (how far
 * behind the dispatch worker is), HTTP responses by status class, JVM heap and uptime.
 *
 * Only installed when PYNAPLI_METRICS_TOKEN is set, and only answers a matching bearer token
 * (compared in constant time). It carries no user data: counts and gauges only. Hand-written
 * instead of a metrics library because a dozen gauges don't need one.
 */
fun Application.configurePrometheusMetrics(
    token: String?,
    sources: OperationalMetricsSources,
) {
    if (token.isNullOrBlank()) return
    val responses = ConcurrentHashMap<String, AtomicLong>()
    install(
        createApplicationPlugin("PynapliResponseCounter") {
            on(ResponseSent) { call ->
                val status = call.response.status()?.value ?: return@on
                responses.computeIfAbsent("${status / 100}xx") { AtomicLong() }.incrementAndGet()
            }
        },
    )
    val expected = token.toByteArray()
    routing {
        get("/metrics") {
            val presented = call.bearerTokenOrNull()?.toByteArray()
            if (presented == null || !MessageDigest.isEqual(presented, expected)) {
                call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)
                return@get
            }
            call.respondText(renderMetrics(sources, responses), ContentType.parse("text/plain; version=0.0.4"))
        }
    }
}

internal suspend fun renderMetrics(
    sources: OperationalMetricsSources,
    responses: Map<String, AtomicLong> = emptyMap(),
): String =
    buildString {
        sources.poolSnapshot()?.let { pool ->
            gauge("pynapli_db_pool_active_connections", "Connections in use.", pool.activeConnections)
            gauge("pynapli_db_pool_idle_connections", "Idle connections.", pool.idleConnections)
            gauge("pynapli_db_pool_total_connections", "Open connections.", pool.totalConnections)
            gauge("pynapli_db_pool_max_connections", "Pool size limit.", pool.maximumPoolSize)
            gauge("pynapli_db_pool_waiting_threads", "Requests waiting for a connection.", pool.threadsAwaitingConnection)
        }
        if (sources.realtimeSubscribers.isNotEmpty()) {
            append("# HELP pynapli_realtime_subscribers Open realtime sockets on this replica.\n")
            append("# TYPE pynapli_realtime_subscribers gauge\n")
            sources.realtimeSubscribers.forEach { (stream, count) ->
                append("pynapli_realtime_subscribers{stream=\"")
                    .append(stream)
                    .append("\"} ")
                    .append(count())
                    .append('\n')
            }
        }
        sources.dataSource?.let { dataSource ->
            val (pending, oldestSeconds) = outboxBacklog(dataSource)
            gauge("pynapli_notification_outbox_pending", "Pushes waiting to be sent.", pending)
            gauge("pynapli_notification_outbox_oldest_pending_seconds", "Age of the oldest due push.", oldestSeconds)
        }
        if (responses.isNotEmpty()) {
            append("# HELP pynapli_http_responses_total HTTP responses by status class since start.\n")
            append("# TYPE pynapli_http_responses_total counter\n")
            responses.toSortedMap().forEach { (statusClass, count) ->
                append("pynapli_http_responses_total{status=\"")
                    .append(statusClass)
                    .append("\"} ")
                    .append(count.get())
                    .append('\n')
            }
        }
        val runtime = Runtime.getRuntime()
        gauge("pynapli_jvm_heap_used_bytes", "Heap in use.", runtime.totalMemory() - runtime.freeMemory())
        gauge("pynapli_jvm_heap_max_bytes", "Heap limit.", runtime.maxMemory())
        gauge("pynapli_process_uptime_seconds", "Seconds since start.", ManagementFactory.getRuntimeMXBean().uptime / 1_000)
    }

private fun StringBuilder.gauge(
    name: String,
    help: String,
    value: Number,
) {
    append("# HELP ")
        .append(name)
        .append(' ')
        .append(help)
        .append('\n')
    append("# TYPE ").append(name).append(" gauge\n")
    append(name).append(' ').append(value).append('\n')
}

private suspend fun outboxBacklog(dataSource: DataSource): Pair<Long, Long> =
    withContext(Dispatchers.IO) {
        runCatching {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        SELECT count(*),
                               COALESCE(EXTRACT(EPOCH FROM now() - min(available_at) FILTER (WHERE available_at <= now())), 0)
                        FROM notification_outbox
                        WHERE status = 'pending'
                        """.trimIndent(),
                    ).use { statement ->
                        statement.executeQuery().use { rs ->
                            rs.next()
                            rs.getLong(1) to rs.getDouble(2).toLong().coerceAtLeast(0)
                        }
                    }
            }
        }.getOrDefault(-1L to -1L)
    }
