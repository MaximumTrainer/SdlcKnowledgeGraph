package com.repodatagraph.acceptance.steps

import com.repodatagraph.acceptance.support.ApiWorld
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * The source systems a write may name (#117), through the development bypass: which ones the
 * ontology publishes, and that naming one outside it is refused whether or not authentication is on.
 */
class SourceSystemSteps(
    private val world: ApiWorld,
) {
    @Then("the ontology declares the source systems:")
    fun theOntologyDeclaresTheSourceSystems(expected: List<String>) {
        val declared = world.lastBody().path("sources").map { it.path("name").asText() }
        assertEquals(expected, declared, "declared source systems, in declaration order")
    }

    @When("I POST a Team named {string} with sourceSystem {string}")
    fun iPostATeamWithSource(
        name: String,
        sourceSystem: String,
    ) {
        world.post(
            "/api/v1/nodes/Team",
            mapOf("props" to mapOf("name" to name), "provenance" to mapOf("sourceSystem" to sourceSystem)),
        )
    }

    @Then("the created node's provenance names sourceSystem {string}")
    fun theCreatedNodesProvenanceNames(sourceSystem: String) {
        val created = world.lastBody()
        assertEquals(sourceSystem, created.path("provenance").path("sourceSystem").asText(null), "created: $created")
        // And as stored, not only as echoed back.
        val stored = world.get("/api/v1/nodes/${created.path("type").asText()}/${created.path("key").asText()}").body
        assertEquals(
            sourceSystem,
            world
                .lastBody()
                .path("provenance")
                .path("sourceSystem")
                .asText(null),
            "stored: $stored",
        )
    }
}
