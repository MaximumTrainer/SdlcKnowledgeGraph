package com.repodatagraph.acceptance.steps

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.repodatagraph.acceptance.support.ApiWorld
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.data.neo4j.core.Neo4jClient

/**
 * Drives the generic edge API over HTTP.
 *
 * Edge bodies are given as JSON in the feature rather than as a step per shape: the point of this
 * API is that one endpoint serves every declared relationship, and a step per edge type would hide
 * exactly that.
 */
class EdgeSteps(
    private val world: ApiWorld,
    private val neo4jClient: Neo4jClient,
) {
    private val mapper = ObjectMapper()
    private var listedEdge: JsonNode? = null

    @Given("the Repository {string} is registered")
    fun theRepositoryIsRegistered(url: String) {
        createNode(
            "Repository",
            mapOf(
                "url" to url,
                "orgRepo" to url.substringAfter("github.com/"),
                "defaultBranch" to "main",
                "topics" to emptyList<String>(),
                "codeowners" to emptyList<String>(),
            ),
        )
    }

    @Given("the Team {string} is registered")
    fun theTeamIsRegistered(name: String) {
        createNode("Team", mapOf("name" to name))
    }

    @Given("the Environment {string} is registered")
    fun theEnvironmentIsRegistered(name: String) {
        createNode("Environment", mapOf("name" to name, "type" to "cloud"))
    }

    @Given("the edge {string} exists from {string} to {string} with kind {string}")
    fun theEdgeExists(
        type: String,
        fromId: String,
        toId: String,
        kind: String,
    ) {
        world.post(
            EDGES,
            mapOf("type" to type, "fromId" to fromId, "toId" to toId, "props" to mapOf("kind" to kind)),
        )
        assertTrue(world.lastStatus() in 200..299) { "creating the edge failed: " + world.lastResponse().body }
    }

    @When("I POST the edge:")
    fun iPostTheEdge(body: String) {
        world.post(EDGES, mapper.readTree(body))
    }

    @When("I DELETE the edge {string} from {string} to {string}")
    fun iDeleteTheEdge(
        type: String,
        fromId: String,
        toId: String,
    ) {
        world.delete("$EDGES?type=$type&fromId=$fromId&toId=$toId")
    }

    @When("I GET the edges of {string} {string} with direction {string}")
    fun iGetTheEdgesOf(
        type: String,
        key: String,
        direction: String,
    ) {
        world.get("/api/v1/nodes/$type/$key/edges?direction=$direction")
    }

    @Then("one edge is listed with displayName {string} and other key {string}")
    fun oneEdgeIsListed(
        displayName: String,
        otherKey: String,
    ) {
        val match =
            world
                .lastBody()
                .path("items")
                .firstOrNull {
                    it.path("displayName").asText() == displayName && it.path("other").path("key").asText() == otherKey
                }
        assertNotNull(match) { "no edge $displayName to $otherKey in ${world.lastBody().path("items")}" }
        listedEdge = match
    }

    @Then("that listed edge has prop {string} of {string}")
    fun thatListedEdgeHasProp(
        name: String,
        value: String,
    ) {
        assertEquals(value, requireListedEdge().path("props").path(name).asText())
    }

    @Then("that listed edge has provenance source {string}")
    fun thatListedEdgeHasProvenanceSource(source: String) {
        assertEquals(source, requireListedEdge().path("provenance").path("sourceSystem").asText())
    }

    @Then("the allowed pairs contain from {string} to {string}")
    fun theAllowedPairsContain(
        from: String,
        to: String,
    ) {
        val pairs = world.lastBody().path("allowed").map { it.path("from").asText() to it.path("to").asText() }
        assertTrue(pairs.contains(from to to)) { "expected $from -> $to in $pairs" }
    }

    @Then("exactly {int} {string} edges exist in the graph")
    fun exactlyNEdgesExist(
        expected: Int,
        type: String,
    ) {
        val count =
            neo4jClient
                .query("MATCH ()-[r:$type]->() RETURN count(r) AS c")
                .fetchAs(Long::class.javaObjectType)
                .one()
                .orElse(0L)
        assertEquals(expected.toLong(), count)
    }

    private fun createNode(
        type: String,
        props: Map<String, Any?>,
    ) {
        world.post("/api/v1/nodes/$type", mapOf("props" to props))
        assertTrue(world.lastStatus() in 200..299) { "creating a $type failed: " + world.lastResponse().body }
    }

    private fun requireListedEdge(): JsonNode = checkNotNull(listedEdge) { "no edge has been listed in this scenario" }

    private companion object {
        const val EDGES = "/api/v1/edges"
    }
}
