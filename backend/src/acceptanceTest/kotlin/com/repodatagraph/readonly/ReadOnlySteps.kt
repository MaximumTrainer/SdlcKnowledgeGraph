package com.repodatagraph.readonly

import com.fasterxml.jackson.databind.ObjectMapper
import io.cucumber.java.Before
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity

/**
 * Steps for the read-only suite. Self-contained rather than reusing the main suite's step classes,
 * which carry dependencies (fake connectors, sync state) this context does not start.
 *
 * Cucumber creates a new instance of this class per scenario, so [response] is scenario state.
 */
class ReadOnlySteps(
    private val restTemplate: TestRestTemplate,
    private val objectMapper: ObjectMapper,
    private val neo4jClient: Neo4jClient,
) {
    private var response: ResponseEntity<String>? = null

    @Before
    fun cleanGraph() {
        neo4jClient.query("MATCH (n) WHERE NOT n:Ontology DETACH DELETE n").run()
    }

    @When("I send a {word} to {string}")
    fun iSend(
        verb: String,
        path: String,
    ) {
        // A well-formed body, so a write that got past the guard would succeed rather than fail
        // validation and make a missing guard look like a refusal.
        val body = if (verb == "GET" || verb == "DELETE") null else jsonEntity("""{"props":{"name":"platform"}}""")
        response = restTemplate.exchange(path, HttpMethod.valueOf(verb), body, String::class.java)
    }

    @When("I send a POST to {string} with X-Request-Id {string}")
    fun iSendAPostWithRequestId(
        path: String,
        requestId: String,
    ) {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        headers.add("X-Request-Id", requestId)
        response =
            restTemplate.exchange(path, HttpMethod.POST, HttpEntity("""{"props":{"name":"platform"}}""", headers), String::class.java)
    }

    @Then("the response header X-Request-Id is {string}")
    fun theResponseHeaderIs(expected: String) {
        assertEquals(expected, lastResponse().headers.getFirst("X-Request-Id"))
    }

    @When("I send the GraphQL document {string}")
    fun iSendTheGraphQlDocument(document: String) {
        val payload = objectMapper.writeValueAsString(mapOf("query" to document))
        response = restTemplate.exchange("/graphql", HttpMethod.POST, jsonEntity(payload), String::class.java)
    }

    @When("I send the impact query for {string}")
    fun iSendTheImpactQuery(repositoryKey: String) {
        val payload = objectMapper.writeValueAsString(mapOf("repositoryKey" to repositoryKey))
        response = restTemplate.exchange("/api/v1/impact", HttpMethod.POST, jsonEntity(payload), String::class.java)
    }

    @Then("the response status is {int}")
    fun theResponseStatusIs(expected: Int) {
        assertEquals(expected, lastResponse().statusCode.value(), "body: ${lastResponse().body}")
    }

    @Then("the error is {string}")
    fun theErrorIs(expected: String) {
        assertEquals(expected, objectMapper.readTree(lastResponse().body ?: "null").path("error").asText(null))
    }

    @Then("no node of type {string} exists")
    fun noNodeOfTypeExists(type: String) {
        val count =
            neo4jClient
                .query("MATCH (n) WHERE \$type IN labels(n) RETURN count(n) AS count")
                .bind(type)
                .to("type")
                .fetchAs(Long::class.java)
                .one()
                .orElse(0L)
        assertEquals(0L, count, "$type nodes in the graph")
    }

    @Then("the deployment info says it is read-only")
    fun theDeploymentInfoSaysItIsReadOnly() {
        val flag = objectMapper.readTree(lastResponse().body ?: "null").path("deployment").path("readOnly")
        assertEquals(true, flag.isBoolean && flag.booleanValue(), "deployment.readOnly: $flag")
    }

    @Then("the deployment info says it authenticates nobody")
    fun theDeploymentInfoSaysItAuthenticatesNobody() {
        val mode = objectMapper.readTree(lastResponse().body ?: "null").path("deployment").path("authentication")
        assertEquals("anonymous-read-only", mode.asText(null), "deployment.authentication: $mode")
    }

    private fun lastResponse(): ResponseEntity<String> = checkNotNull(response) { "No request has been made yet" }

    private fun jsonEntity(json: String): HttpEntity<String> =
        HttpEntity(json, HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON })
}
