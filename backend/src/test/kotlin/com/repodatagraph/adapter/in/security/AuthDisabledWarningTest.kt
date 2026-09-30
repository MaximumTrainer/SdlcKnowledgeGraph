package com.repodatagraph.adapter.`in`.security

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.repodatagraph.config.AuthProperties
import com.repodatagraph.observability.EventLog
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/** The bypass is loud: every start with it on says so, at WARN, marked for security (#114, FR-5). */
class AuthDisabledWarningTest {
    private val appender = ListAppender<ILoggingEvent>()
    private val eventLogger = LoggerFactory.getLogger("${EventLog.LOGGER_PREFIX}.auth.disabled") as Logger

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
    fun `a start with the bypass on logs a warning`() {
        AuthDisabledWarning(AuthProperties(disabled = true)).announce()

        assertEquals(listOf(Level.WARN), appender.list.map { it.level })
    }

    @Test
    fun `a start with authentication on says nothing`() {
        AuthDisabledWarning(AuthProperties(disabled = false, issuerUri = "https://id.example.test/realms/sdlc")).announce()

        assertEquals(emptyList<ILoggingEvent>(), appender.list)
    }
}
