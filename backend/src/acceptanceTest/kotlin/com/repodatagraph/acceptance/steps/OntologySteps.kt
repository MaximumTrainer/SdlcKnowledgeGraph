package com.repodatagraph.acceptance.steps

import com.fasterxml.jackson.databind.JsonNode
import com.repodatagraph.acceptance.support.ApiWorld
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.data.neo4j.core.Neo4jClient

class OntologySteps(
    private val world: ApiWorld,
    private val neo4jClient: Neo4jClient,
) {
    private var ontologyNodeVersions: List<String> = emptyList()

    @Then("the ontology version is {string}")
    fun theOntologyVersionIs(expected: String) {
        assertEquals(expected, world.lastBody().path("version").asText())
    }

    @Then("the ontology declares the node types:")
    fun theOntologyDeclaresTheNodeTypes(expectedTypes: List<String>) {
        val declared = world.lastBody().path("nodeTypes").map { it.path("name").asText() }
        assertEquals(expectedTypes.sorted(), declared.sorted(), "declared node types")
    }

    @Then("every edge type declares a non-empty inverse")
    fun everyEdgeTypeDeclaresANonEmptyInverse() {
        val edges = world.lastBody().path("edgeTypes")
        assertTrue(edges.size() > 0, "expected at least one edge type")
        edges.forEach { edge ->
            val inverse = edge.path("inverse").asText()
            assertFalse(inverse.isNullOrBlank(), "edge ${edge.path("name").asText()} has no inverse")
        }
    }

    @Then("the edge type {string} has inverse {string}")
    fun theEdgeTypeHasInverse(
        edgeName: String,
        expectedInverse: String,
    ) {
        assertEquals(expectedInverse, edge(edgeName).path("inverse").asText())
    }

    @Then("the edge type {string} goes from {string} to {string}")
    fun theEdgeTypeGoesFromTo(
        edgeName: String,
        from: String,
        to: String,
    ) {
        val edge = edge(edgeName)
        assertEquals(listOf(from), edge.path("from").map { it.asText() })
        assertEquals(listOf(to), edge.path("to").map { it.asText() })
    }

    @Then("the node type identity is {string}")
    fun theNodeTypeIdentityIs(expected: String) {
        val identity = world.lastBody().path("identity").map { it.asText() }
        assertEquals(expected, identity.joinToString(", "))
    }

    @Then("the error message is {string}")
    fun theErrorMessageIs(expected: String) {
        assertEquals(expected, world.lastBody().path("error").asText())
    }

    @Then("the response has an ETag")
    fun theResponseHasAnETag() {
        assertNotNull(world.lastHeader("ETag"), "ETag header")
    }

    @Then("the response is cacheable for {int} seconds")
    fun theResponseIsCacheableForSeconds(seconds: Int) {
        val cacheControl = world.lastHeader("Cache-Control")
        assertNotNull(cacheControl, "Cache-Control header")
        assertTrue(
            cacheControl!!.contains("max-age=$seconds"),
            "expected max-age=$seconds in '$cacheControl'",
        )
    }

    @When("I count the Ontology nodes in the graph")
    fun iCountTheOntologyNodesInTheGraph() {
        ontologyNodeVersions =
            neo4jClient
                .query("MATCH (o:Ontology) RETURN o.version AS version")
                .fetchAs(String::class.java)
                .all()
                .toList()
    }

    @Then("there is exactly one Ontology node with version {string}")
    fun thereIsExactlyOneOntologyNodeWithVersion(expected: String) {
        assertEquals(listOf(expected), ontologyNodeVersions)
    }

    private fun edge(name: String): JsonNode =
        world.lastBody().path("edgeTypes").firstOrNull { it.path("name").asText() == name }
            ?: error("edge type '$name' not found in ${world.lastBody().path("edgeTypes")}")
}
