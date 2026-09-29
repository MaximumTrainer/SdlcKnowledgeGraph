package com.repodatagraph.observability

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import net.logstash.logback.encoder.LogstashEncoder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.slf4j.event.Level

/**
 * The contract with whatever aggregates the logs (#44, FR1): an event is one JSON object whose
 * declared fields sit at the top level beside `event`, `level`, `message` and `requestId`, encoded
 * the way `logback-spring.xml` encodes every line under the docker profile.
 */
class EventLogJsonTest {
    private val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    private val appender = ListAppender<ILoggingEvent>()
    private val encoder =
        LogstashEncoder().apply {
            context = LoggerFactory.getILoggerFactory() as LoggerContext
            addIncludeMdcKeyName("requestId")
            start()
        }

    @BeforeEach
    fun capture() {
        appender.start()
        root.addAppender(appender)
    }

    @AfterEach
    fun release() {
        root.detachAppender(appender)
        MDC.clear()
    }

    private fun json() = ObjectMapper().readTree(encoder.encode(appender.list.single()))

    @Test
    fun `an event is one JSON object with its fields at the top level`() {
        MDC.put("requestId", "abc-123")

        EventLog.emit(
            "node.created",
            Level.INFO,
            "node created",
            mapOf(
                "type" to "Team",
                "key" to "platform",
                "properties" to listOf("name"),
            ),
        )

        val line = json()
        assertThat(line.path("event").asText()).isEqualTo("node.created")
        assertThat(line.path("level").asText()).isEqualTo("INFO")
        assertThat(line.path("requestId").asText()).isEqualTo("abc-123")
        assertThat(line.path("type").asText()).isEqualTo("Team")
        assertThat(line.path("key").asText()).isEqualTo("platform")
        assertThat(line.path("properties")[0].asText()).isEqualTo("name")
    }

    @Test
    fun `a throwable is a separate stack_trace, not part of the message`() {
        EventLog.emit(
            "graph.store.failed",
            Level.ERROR,
            "graph store failed",
            mapOf("operation" to "upsertNode"),
            cause = IllegalStateException("boom"),
        )

        val line = json()
        assertThat(line.path("message").asText()).doesNotContain("IllegalStateException")
        assertThat(line.path("stack_trace").asText()).contains("IllegalStateException: boom")
    }

    @Test
    fun `a security event is marked so it can be routed apart`() {
        EventLog.emit("ingest.unauthorized", Level.WARN, "ingest refused", mapOf("endpoint" to "/x"), security = true)

        assertThat(json().path("tags").map { it.asText() }).contains("SECURITY")
    }

    @Test
    fun `a field named like a secret is masked wherever it is`() {
        EventLog.emit("node.rejected", Level.INFO, "node rejected", mapOf("type" to "Team", "detail" to mapOf("apiKey" to "k-123")))

        assertThat(json().toString()).doesNotContain("k-123").contains("***")
    }

    @Test
    fun `each event logs under its own name, so its level can be set on its own`() {
        EventLog.emit("node.created", Level.INFO, "node created", emptyMap())

        assertThat(appender.list.single().loggerName).isEqualTo("com.repodatagraph.event.node.created")
    }
}
