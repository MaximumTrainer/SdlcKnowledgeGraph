package com.repodatagraph.observability

import org.slf4j.LoggerFactory
import org.slf4j.MarkerFactory
import org.slf4j.event.Level

/**
 * Where every declared event is written (#44). Called only from the generated [LogEvents], which is
 * what holds a caller to the registry; nothing else in the application creates a logger.
 *
 * An event's fields are SLF4J key-value pairs, which the JSON encoder writes at the top level of the
 * line beside `event`, so a platform can query them without parsing the message. Each event logs
 * under its own logger, `com.repodatagraph.event.<name>`, so one event can be turned up or down
 * without touching the others.
 */
object EventLog {
    const val LOGGER_PREFIX = "com.repodatagraph.event"

    /** Carried by events that matter to whoever watches for abuse, so they can be routed apart. */
    private val SECURITY = MarkerFactory.getMarker("SECURITY")

    fun emit(
        name: String,
        level: Level,
        message: String,
        fields: Map<String, Any?>,
        security: Boolean = false,
        cause: Throwable? = null,
    ) {
        val logger = LoggerFactory.getLogger("$LOGGER_PREFIX.$name")
        if (!logger.isEnabledForLevel(level)) return

        var event = logger.atLevel(level).setMessage(message).addKeyValue("event", name)
        SensitiveFieldMasker.mask(fields).forEach { (field, value) -> event = event.addKeyValue(field, value) }
        if (security) event = event.addMarker(SECURITY)
        if (cause != null) event = event.setCause(cause)
        event.log()
    }
}
