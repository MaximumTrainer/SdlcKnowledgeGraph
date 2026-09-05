package com.repodatagraph.acceptance.steps

import com.repodatagraph.acceptance.support.ApiWorld
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.springframework.data.neo4j.core.Neo4jClient

/**
 * Shared HTTP steps. Scenario state lives in [ApiWorld] so other step classes can assert on the
 * same response without redefining these steps.
 */
class HealthSteps(
    private val world: ApiWorld,
    private val neo4jClient: Neo4jClient,
) {
    /** Every scenario starts from an empty graph, except for nodes the application writes at startup. */
    @Before
    fun cleanGraph() {
        neo4jClient.query("MATCH (n) WHERE NOT n:Ontology DETACH DELETE n").run()
    }

    @Given("the application is running")
    fun theApplicationIsRunning() {
        // The Spring context and its Neo4j container are started by CucumberSpringConfig.
    }

    @When("I GET {string}")
    fun iGet(path: String) {
        world.get(path)
    }

    @Then("the response status is {int}")
    fun theResponseStatusIs(expectedStatus: Int) {
        assertEquals(expectedStatus, world.lastStatus())
    }

    @Then("the health component {string} is {string}")
    fun theHealthComponentIs(
        component: String,
        expectedStatus: String,
    ) {
        val actual =
            world
                .lastBody()
                .path("components")
                .path(component)
                .path("status")
                .asText(null)
        assertEquals(expectedStatus, actual, "health component '$component'")
    }
}
