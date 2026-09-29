package com.repodatagraph.acceptance.steps

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import com.repodatagraph.acceptance.support.ApiWorld
import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.assertj.core.api.Assertions.assertThat
import org.slf4j.LoggerFactory

/**
 * Drives the application over HTTP and reads the events it logged, from a Logback appender attached
 * to the root logger for the scenario. An event's fields are SLF4J key-value pairs, which is what the
 * JSON encoder writes at the top level of each line.
 */
class ObservabilitySteps(
    private val world: ApiWorld,
    private val objectMapper: ObjectMapper,
) {
    private val appender = ListAppender<ILoggingEvent>()
    private val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    private var event: ILoggingEvent? = null

    @Before("@events")
    fun capture() {
        appender.start()
        root.addAppender(appender)
    }

    @After("@events")
    fun release() {
        root.detachAppender(appender)
    }

    @When("I POST {string} with props {} and X-Request-Id {string}")
    fun postWithProps(
        path: String,
        props: String,
        requestId: String,
    ) {
        appender.list.clear()
        val body = objectMapper.writeValueAsString(mapOf("props" to objectMapper.readValue(props, Map::class.java)))
        world.postSigned(path, body, mapOf("X-Request-Id" to requestId))
    }

    @When("the deploy pipeline reports a deployment with the token {string}")
    fun reportWithToken(token: String) {
        appender.list.clear()
        val report =
            mapOf(
                "repository" to "github.com/maximumtrainer/sdlcknowledgegraph",
                "commitSha" to "c1",
                "artifacts" to listOf(mapOf("name" to "ghcr.io/maximumtrainer/sdlc-graph-backend", "digest" to "sha256:abc")),
                "environment" to "staging",
                "status" to "SUCCESS",
                "deployedAt" to "2026-09-29T12:00:00Z",
                "runUrl" to "https://github.com/maximumtrainer/sdlcknowledgegraph/actions/runs/1",
                "pipeline" to mapOf("workflowPath" to ".github/workflows/deploy-dogfood.yml"),
                // Not part of a report; here to show that a field named like a secret is not logged
                // even when a caller puts one somewhere unexpected.
                "token" to token,
            )
        world.postSigned("/api/v1/ingest/deployment", objectMapper.writeValueAsString(report), mapOf("Authorization" to "Bearer $token"))
    }

    @Then("exactly one {string} event is logged")
    fun exactlyOneEvent(name: String) {
        val matching = appender.list.filter { fieldsOf(it)["event"] == name }
        assertThat(matching).describedAs("events named $name among ${appender.list.map { fieldsOf(it)["event"] }}").hasSize(1)
        event = matching.single()
    }

    @Then("it carries requestId {string}")
    fun itCarriesRequestId(requestId: String) {
        assertThat(checkNotNull(event).mdcPropertyMap["requestId"]).isEqualTo(requestId)
    }

    @Then("its field {string} is {string}")
    fun itsFieldIs(
        field: String,
        value: String,
    ) {
        assertThat(fieldsOf(checkNotNull(event))[field]).isEqualTo(value)
    }

    /** A number is logged as a number, so it is compared as one rather than as its text. */
    @Then("its field {string} is the number {long}")
    fun itsFieldIsTheNumber(
        field: String,
        value: Long,
    ) {
        assertThat((fieldsOf(checkNotNull(event))[field] as? Number)?.toLong()).isEqualTo(value)
    }

    @Then("its field {string} lists {string} and {string}")
    fun itsFieldLists(
        field: String,
        first: String,
        second: String,
    ) {
        assertThat(fieldsOf(checkNotNull(event))[field] as Collection<*>).containsExactlyInAnyOrder(first, second)
    }

    @Then("no log line contains {string}")
    fun noLineContains(value: String) {
        appender.list.forEach { line ->
            val rendered = line.formattedMessage + fieldsOf(line) + (line.throwableProxy?.let(ThrowableProxyUtil::asString) ?: "")
            assertThat(rendered).describedAs("a line from ${line.loggerName}").doesNotContain(value)
        }
    }

    private fun fieldsOf(event: ILoggingEvent): Map<String, Any?> = event.keyValuePairs.orEmpty().associate { it.key to it.value }
}
