package com.repodatagraph.acceptance.steps

import com.fasterxml.jackson.databind.JsonNode
import com.repodatagraph.acceptance.support.ApiWorld
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.port.out.GraphStore
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import java.time.Duration
import java.time.Instant

/**
 * How fresh each connector is, and what the application says about it (#29, FR3 and FR4).
 *
 * A stale connector is set up by writing its state straight into the graph rather than by waiting
 * hours for one to go stale. The graph is emptied before every scenario, so nothing written here
 * outlives the scenario that wrote it.
 */
class FreshnessSteps(
    private val world: ApiWorld,
    private val graphStore: GraphStore,
) {
    @Given("the connector state for {string} has lastSuccessAt {int} hours ago")
    fun theStateHasAnOldSuccess(
        connector: String,
        hours: Int,
    ) {
        val succeeded = Instant.now().minus(Duration.ofHours(hours.toLong()))
        graphStore.upsertNode(
            GraphNode(
                key = NodeKey("ConnectorState", connector),
                props =
                    mapOf(
                        "connector" to connector,
                        "lastRunId" to "an-old-run",
                        "lastStatus" to "SUCCESS",
                        "lastRunStatus" to "SUCCESS",
                        "lastFinishedAt" to succeeded,
                        "lastSuccessAt" to succeeded,
                        "consecutiveFailures" to 0,
                    ),
                provenance = Provenance.manual(),
            ),
        )
    }

    @Then("the health component {string} lists {string} as stale")
    fun theComponentListsAsStale(
        component: String,
        connector: String,
    ) {
        val stale =
            world
                .lastBody()
                .path("components")
                .path(component)
                .path("details")
                .path("stale")
        assertTrue(stale.any { it.asText() == connector }) { "expected $connector among the stale, got: $stale" }
    }

    @Then("the listed connector {string} has freshness stale {word}")
    fun theListedConnectorHasStale(
        name: String,
        stale: String,
    ) {
        assertEquals(stale.toBooleanStrict(), freshnessOf(name).path("stale").asBoolean()) { "freshness of $name" }
    }

    @Then("the listed connector {string} has a freshness age of at least {long} seconds")
    fun theAgeIsAtLeast(
        name: String,
        seconds: Long,
    ) {
        val age = freshnessOf(name).path("ageSeconds")
        assertTrue(age.isNumber && age.asLong() >= seconds) { "ageSeconds of $name was $age" }
    }

    @Then("the listed connector {string} has a freshness age of less than {long} seconds")
    fun theAgeIsLessThan(
        name: String,
        seconds: Long,
    ) {
        val age = freshnessOf(name).path("ageSeconds")
        assertTrue(age.isNumber && age.asLong() < seconds) { "ageSeconds of $name was $age" }
    }

    @Then("the listed connector {string} has a freshness threshold of {long} seconds")
    fun theThresholdIs(
        name: String,
        seconds: Long,
    ) {
        assertEquals(seconds, freshnessOf(name).path("thresholdSeconds").asLong())
    }

    @Then("the listed connector {string} has a lastSuccessAt")
    fun theListedConnectorHasASuccess(name: String) {
        val at = freshnessOf(name).path("lastSuccessAt")
        assertTrue(at.isTextual) { "lastSuccessAt of $name was $at" }
    }

    @Then("the state of connector {string} has {int} consecutive failures and last run status {string}")
    fun theStateCountsFailures(
        name: String,
        failures: Int,
        status: String,
    ) {
        val state = stateOf(name)
        assertEquals(failures, state.path("consecutiveFailures").asInt(-1)) { "state of $name: $state" }
        assertEquals(status, state.path("lastRunStatus").asText()) { "state of $name: $state" }
    }

    @Then("the state of connector {string} has no lastSuccessAt")
    fun theStateHasNoSuccess(name: String) {
        val state = stateOf(name)
        assertFalse(state.path("lastSuccessAt").isTextual) { "state of $name: $state" }
    }

    @Then("the state of connector {string} has a lastSuccessAt")
    fun theStateHasASuccess(name: String) {
        val state = stateOf(name)
        assertTrue(state.path("lastSuccessAt").isTextual) { "state of $name: $state" }
    }

    private fun freshnessOf(name: String): JsonNode {
        val entry = world.lastBody().firstOrNull { it.path("name").asText() == name }
        assertNotNull(entry) { "no connector named $name in " + world.lastResponse().body }
        return entry!!.path("freshness")
    }

    private fun stateOf(name: String): JsonNode {
        world.get("/api/v1/connectors/$name")
        assertEquals(200, world.lastStatus()) { world.lastResponse().body }
        return world.lastBody().path("state")
    }
}
