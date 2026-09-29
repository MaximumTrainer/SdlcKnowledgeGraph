package com.repodatagraph.acceptance.steps

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.repodatagraph.acceptance.support.ApiWorld
import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.assertj.core.api.Assertions.assertThat
import org.slf4j.LoggerFactory

/**
 * Drives requests with and without an `X-Request-Id` and reads what was logged while answering them.
 *
 * The web layer is logged at DEBUG for the scenario, so every request writes lines of its own to
 * check the id against, whatever the application itself chooses to log.
 */
class RequestCorrelationSteps(
    private val world: ApiWorld,
) {
    private val appender = ListAppender<ILoggingEvent>()
    private val webLogger = LoggerFactory.getLogger(WEB_LOGGER) as Logger
    private var previousLevel: Level? = null

    @Before("@correlation")
    fun capture() {
        previousLevel = webLogger.level
        webLogger.level = Level.DEBUG
        appender.start()
        (LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger).addAppender(appender)
    }

    @After("@correlation")
    fun release() {
        (LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger).detachAppender(appender)
        webLogger.level = previousLevel
    }

    @When("I GET {string} with X-Request-Id {string}")
    fun getWithRequestId(
        path: String,
        requestId: String,
    ) {
        appender.list.clear()
        world.get(path, mapOf(HEADER to requestId))
    }

    @When("I GET {string} with no X-Request-Id")
    fun getWithout(path: String) {
        appender.list.clear()
        world.get(path, emptyMap())
    }

    @Then("the response header X-Request-Id is {string}")
    fun theHeaderIs(expected: String) {
        assertThat(world.lastHeader(HEADER)).isEqualTo(expected)
    }

    @Then("the response header X-Request-Id is a safe correlation id")
    fun theHeaderIsSafe() {
        assertThat(world.lastHeader(HEADER)).matches(SAFE)
    }

    @Then("it is not {string}")
    fun itIsNot(hostile: String) {
        assertThat(world.lastHeader(HEADER)).isNotEqualTo(hostile)
    }

    @Then("every log line written while answering carries requestId {string}")
    fun everyLineCarries(requestId: String) {
        val lines = appender.list.filter { it.threadName.startsWith(REQUEST_THREAD) }
        assertThat(lines).describedAs("lines written on the request thread").isNotEmpty()
        assertThat(lines.map { it.mdcPropertyMap[MDC_KEY] }).containsOnly(requestId)
    }

    @Then("every log line written while answering carries that id")
    fun everyLineCarriesTheIssuedId() {
        everyLineCarries(checkNotNull(world.lastHeader(HEADER)))
    }

    private companion object {
        const val HEADER = "X-Request-Id"
        const val MDC_KEY = "requestId"
        const val WEB_LOGGER = "org.springframework.web.servlet.DispatcherServlet"

        /** Tomcat's request threads; anything else logging at the same time is not this request. */
        const val REQUEST_THREAD = "http-nio-"
        const val SAFE = "^[A-Za-z0-9._-]{1,64}$"
    }
}
