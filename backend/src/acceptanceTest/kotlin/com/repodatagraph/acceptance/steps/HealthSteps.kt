package com.repodatagraph.acceptance.steps

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.http.ResponseEntity

class HealthSteps(
    private val restTemplate: TestRestTemplate,
    private val neo4jClient: Neo4jClient,
    private val objectMapper: ObjectMapper,
) {
    private var response: ResponseEntity<String>? = null

    /** Every scenario starts from an empty graph. */
    @Before
    fun cleanGraph() {
        neo4jClient.query("MATCH (n) DETACH DELETE n").run()
    }

    @Given("the application is running")
    fun theApplicationIsRunning() {
        // The Spring context (and its Neo4j container) is started by CucumberSpringConfig.
        assertNotNull(restTemplate.rootUri, "application base URL should be known")
    }

    @When("I GET {string}")
    fun iGet(path: String) {
        response = restTemplate.getForEntity(path, String::class.java)
    }

    @Then("the response status is {int}")
    fun theResponseStatusIs(expectedStatus: Int) {
        assertEquals(expectedStatus, lastResponse().statusCode.value())
    }

    @Then("the health component {string} is {string}")
    fun theHealthComponentIs(
        component: String,
        expectedStatus: String,
    ) {
        val body: JsonNode = objectMapper.readTree(lastResponse().body)
        val actual =
            body
                .path("components")
                .path(component)
                .path("status")
                .asText(null)
        assertEquals(expectedStatus, actual, "health component '$component' in ${lastResponse().body}")
    }

    private fun lastResponse(): ResponseEntity<String> = checkNotNull(response) { "No request has been made yet" }
}
