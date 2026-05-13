package com.pynapli.server.profiling

import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asContextElement
import java.util.Collections
import java.util.Locale

data class DatabasePoolSnapshot(
    val activeConnections: Int,
    val idleConnections: Int,
    val totalConnections: Int,
    val threadsAwaitingConnection: Int,
    val maximumPoolSize: Int,
    val minimumIdle: Int
)

class RequestProfilerFactory(
    private val enabled: Boolean,
    private val headerRequired: Boolean,
    private val slowSpanThresholdMillis: Double,
    private val poolSnapshotProvider: () -> DatabasePoolSnapshot?
) {
    fun start(call: ApplicationCall): RequestProfiler {
        val requested = !headerRequired || call.request.headers[PROFILE_REQUEST_HEADER].isProfilingEnabledHeader()
        return if (enabled && requested) {
            RequestProfiler(
                enabled = true,
                slowSpanThresholdMillis = slowSpanThresholdMillis,
                poolSnapshotProvider = poolSnapshotProvider
            )
        } else {
            RequestProfiler.disabled()
        }
    }

    companion object {
        const val PROFILE_REQUEST_HEADER = "X-Pynapli-Profile"
        const val PROFILE_TIMINGS_HEADER = "X-Pynapli-Profile"
        const val PROFILE_SLOW_HEADER = "X-Pynapli-Profile-Slow"
        const val PROFILE_POOL_HEADER = "X-Pynapli-Pool"

        fun disabled(): RequestProfilerFactory =
            RequestProfilerFactory(
                enabled = false,
                headerRequired = true,
                slowSpanThresholdMillis = 0.0,
                poolSnapshotProvider = { null }
            )
    }
}

class RequestProfiler(
    val enabled: Boolean,
    private val slowSpanThresholdMillis: Double,
    private val poolSnapshotProvider: () -> DatabasePoolSnapshot?
) {
    private val spans = Collections.synchronizedList(mutableListOf<RequestProfileSpan>())

    suspend fun <T> measure(
        name: String,
        block: suspend () -> T
    ): T {
        if (!enabled) return block()
        val startedAt = System.nanoTime()
        try {
            return block()
        } finally {
            record(name, startedAt)
        }
    }

    fun <T> measureBlocking(
        name: String,
        block: () -> T
    ): T {
        if (!enabled) return block()
        val startedAt = System.nanoTime()
        try {
            return block()
        } finally {
            record(name, startedAt)
        }
    }

    fun appendHeaders(call: ApplicationCall) {
        if (!enabled) return
        call.response.headers.append(RequestProfilerFactory.PROFILE_TIMINGS_HEADER, timingsHeaderValue())
        slowHeaderValue()?.let { call.response.headers.append(RequestProfilerFactory.PROFILE_SLOW_HEADER, it) }
        poolHeaderValue()?.let { call.response.headers.append(RequestProfilerFactory.PROFILE_POOL_HEADER, it) }
    }

    private fun record(name: String, startedAt: Long) {
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000.0
        spans += RequestProfileSpan(name = name, millis = elapsedMillis)
    }

    private fun timingsHeaderValue(): String {
        val aggregates = linkedMapOf<String, RequestProfileAggregate>()
        spans.toList().forEach { span ->
            aggregates.getOrPut(span.name) { RequestProfileAggregate() }.add(span.millis)
        }
        return aggregates.entries.joinToString(",") { (name, aggregate) ->
            "$name=${formatMillis(aggregate.totalMillis)}"
        }
    }

    private fun slowHeaderValue(): String? {
        if (slowSpanThresholdMillis <= 0.0) return null
        val slowSpans = spans.toList().filter { it.millis >= slowSpanThresholdMillis }
        if (slowSpans.isEmpty()) return null
        return slowSpans.joinToString(",") { span ->
            "${span.name}=${formatMillis(span.millis)}"
        }
    }

    private fun poolHeaderValue(): String? {
        val snapshot = poolSnapshotProvider() ?: return null
        return listOf(
            "active=${snapshot.activeConnections}",
            "idle=${snapshot.idleConnections}",
            "total=${snapshot.totalConnections}",
            "waiting=${snapshot.threadsAwaitingConnection}",
            "max=${snapshot.maximumPoolSize}",
            "minIdle=${snapshot.minimumIdle}"
        ).joinToString(",")
    }

    companion object {
        fun disabled(): RequestProfiler =
            RequestProfiler(
                enabled = false,
                slowSpanThresholdMillis = 0.0,
                poolSnapshotProvider = { null }
            )
    }
}

suspend fun <T> profile(
    name: String,
    block: suspend () -> T
): T = currentProfiler.get()?.measure(name, block) ?: block()

fun <T> profileBlocking(
    name: String,
    block: () -> T
): T = currentProfiler.get()?.measureBlocking(name, block) ?: block()

fun profilerContextElement(profiler: RequestProfiler): ThreadContextElement<RequestProfiler?> =
    currentProfiler.asContextElement(profiler.takeIf { it.enabled })

private fun String?.isProfilingEnabledHeader(): Boolean =
    equals("true", ignoreCase = true) || this == "1" || equals("yes", ignoreCase = true)

private fun formatMillis(value: Double): String =
    String.format(Locale.US, "%.1f", value)

private data class RequestProfileSpan(
    val name: String,
    val millis: Double
)

private class RequestProfileAggregate {
    var totalMillis: Double = 0.0
        private set

    fun add(millis: Double) {
        totalMillis += millis
    }
}

private val currentProfiler = ThreadLocal<RequestProfiler?>()
