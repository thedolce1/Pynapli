package com.pynapli.server.plugins

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.CoreConstants
import ch.qos.logback.core.LayoutBase
import ch.qos.logback.core.encoder.LayoutWrappingEncoder
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * One JSON object per log line, in the shape Google Cloud Logging reads from a container's stdout:
 * `severity` becomes the entry's level, `message` its text, `time` its timestamp, and the request
 * id lands in `jsonPayload.requestId` so every line of one request can be filtered together. A
 * stack trace is appended to `message` as well, which is where Error Reporting looks for it.
 *
 * Selected with PYNAPLI_LOG_FORMAT=json (logback.xml); local runs keep the readable text format.
 * Written here rather than pulled in as a dependency: it is ~60 lines and has no options to manage.
 */
class CloudJsonLayout : LayoutBase<ILoggingEvent>() {
    override fun doLayout(event: ILoggingEvent): String {
        val message =
            buildString {
                append(event.formattedMessage ?: "")
                event.throwableProxy?.let { proxy ->
                    append('\n')
                    append(ThrowableProxyUtil.asString(proxy))
                }
            }
        return buildString {
            append('{')
            field("severity", severityOf(event.level))
            append(',')
            field("message", message)
            append(',')
            field("time", Instant.ofEpochMilli(event.timeStamp).toString())
            append(',')
            field("logger", event.loggerName ?: "")
            append(',')
            field("thread", event.threadName ?: "")
            event.mdcPropertyMap["requestId"]?.let { requestId ->
                append(',')
                field("requestId", requestId)
            }
            append('}')
            append(CoreConstants.LINE_SEPARATOR)
        }
    }

    private fun StringBuilder.field(
        name: String,
        value: String,
    ) {
        append('"').append(name).append("\":")
        appendJsonString(value)
    }

    private fun severityOf(level: Level): String =
        when (level.toInt()) {
            Level.ERROR_INT -> "ERROR"
            Level.WARN_INT -> "WARNING"
            Level.INFO_INT -> "INFO"
            else -> "DEBUG"
        }
}

/** Chooses the log format once at startup: PYNAPLI_LOG_FORMAT=json for deployed environments. */
object LogFormat {
    fun apply(format: String? = System.getenv("PYNAPLI_LOG_FORMAT")) {
        if (!format.equals("json", ignoreCase = true)) return
        val context = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
        val layout = CloudJsonLayout().apply { this.context = context }
        layout.start()
        val encoder =
            LayoutWrappingEncoder<ILoggingEvent>().apply {
                this.context = context
                this.layout = layout
            }
        encoder.start()
        val appender =
            ConsoleAppender<ILoggingEvent>().apply {
                this.context = context
                name = "JSON"
                this.encoder = encoder
            }
        appender.start()
        val root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
        root.detachAndStopAllAppenders()
        root.addAppender(appender)
    }
}

internal fun StringBuilder.appendJsonString(value: String) {
    append('"')
    for (c in value) {
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> append(c)
        }
    }
    append('"')
}
