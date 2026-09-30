package com.repodatagraph.adapter.`in`.security

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.repodatagraph.config.AuthProperties
import com.repodatagraph.observability.EventLog
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * An instance with no identity provider says so (#118): every start in the anonymous read-only mode
 * logs it by name, at WARN, marked for security, so what an instance is serving to whom is visible in
 * its logs and not only in its environment.
 */
class AnonymousReadOnlyWarningTest {
    private val appender = ListAppender<ILoggingEvent>()
    private val eventLogger = LoggerFactory.getLogger("${EventLog.LOGGER_PREFIX}.auth.anonymous-read-only") as Logger

    @BeforeEach
    fun attach() {
        appender.start()
        eventLogger.addAppender(appender)
    }

    @AfterEach
    fun detach() {
        eventLogger.detachAppender(appender)
    }

    @Test
    fun `a start with no identity provider logs the mode, as a security warning`() {
        AnonymousReadOnlyWarning(AuthProperties(issuerUri = "")).announce()

        assertEquals(listOf(Level.WARN), appender.list.map { it.level })
        assertTrue(
            appender.list
                .single()
                .markerList
                .orEmpty()
                .any { it.name == "SECURITY" },
            "marked for security",
        )
    }

    @Test
    fun `a start with an identity provider says nothing`() {
        AnonymousReadOnlyWarning(AuthProperties(issuerUri = "https://id.example.test/realms/sdlc")).announce()

        assertEquals(emptyList<ILoggingEvent>(), appender.list)
    }
}
